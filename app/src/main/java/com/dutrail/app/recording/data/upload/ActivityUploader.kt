package com.dutrail.app.recording.data.upload

import android.util.Log
import com.dutrail.app.activities.data.ActivitiesApi
import com.dutrail.app.auth.data.SessionStore
import com.dutrail.app.core.network.ApiError
import com.dutrail.app.core.network.apiCall
import com.dutrail.app.recording.data.RecordingDao
import com.dutrail.app.recording.data.RecordingEntity
import com.dutrail.app.recording.data.RecordingStatus
import com.dutrail.app.recording.data.UploadError
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody

/** Envia o .fit de uma gravação para `POST /activities/import` e registra o resultado. */
class ActivityUploader(
    private val dao: RecordingDao,
    private val api: ActivitiesApi,
    private val sessionStore: SessionStore,
    /** Resolve o nome salvo no banco para o arquivo (FitFileStorage::file; nos testes, um diretório temporário). */
    private val fitFile: (String) -> File,
) {
    enum class Outcome {
        UPLOADED,

        /** 409: o mesmo arquivo já tinha sido importado. Sucesso silencioso, nada a refazer. */
        ALREADY_UPLOADED,

        /** Sem sessão do dono da gravação; volta para a fila quando ele logar de novo. */
        WAITING_FOR_OWNER,

        /** O servidor recusou o arquivo (400/413), ele passa do limite ou sumiu; tentar de novo sozinho não adianta. */
        REJECTED,

        /** Falha temporária (rede, 429, 5xx): o WorkManager tenta de novo com backoff. */
        RETRY,

        NOTHING_TO_DO,
    }

    suspend fun upload(recordingId: Long): Outcome {
        val recording = dao.recording(recordingId) ?: return Outcome.NOTHING_TO_DO
        if (recording.status != RecordingStatus.PENDING_UPLOAD && recording.status != RecordingStatus.UPLOAD_FAILED) {
            return Outcome.NOTHING_TO_DO
        }
        if (sessionStore.current()?.userId != recording.ownerUserId) {
            dao.updateRecording(recording.copy(uploadError = UploadError.SESSION_EXPIRED))
            return Outcome.WAITING_FOR_OWNER
        }
        val file = recording.fitFileName?.let(fitFile)?.takeIf { it.exists() }
            ?: return recording.fail(UploadError.FILE_MISSING)
        // Mandar sabendo que vai dar 413 só gastaria dados móveis.
        if (file.length() > ActivitiesApi.MAX_IMPORT_BYTES) return recording.fail(UploadError.FILE_TOO_LARGE)

        val part = MultipartBody.Part.createFormData(
            ActivitiesApi.IMPORT_FILE_FIELD,
            file.name,
            file.asRequestBody(FIT_MEDIA_TYPE),
        )
        return try {
            val created = apiCall { api.import(part) }
            recording.uploaded(created.id, file)
            Outcome.UPLOADED
        } catch (e: ApiError.Http) {
            Log.w(TAG, "Import da gravação $recordingId falhou: ${e.message}")
            when (e.code) {
                409 -> {
                    recording.uploaded(remoteActivityId = null, file)
                    Outcome.ALREADY_UPLOADED
                }
                400 -> recording.fail(UploadError.INVALID_FILE)
                413 -> recording.fail(UploadError.FILE_TOO_LARGE)
                // 401 aqui = refresh já falhou e a sessão foi encerrada. 404 = a conta não existe mais.
                401, 404 -> {
                    if (e.code == 404) sessionStore.clear()
                    dao.updateRecording(recording.copy(status = RecordingStatus.PENDING_UPLOAD, uploadError = UploadError.SESSION_EXPIRED))
                    Outcome.WAITING_FOR_OWNER
                }
                else -> recording.retryLater(UploadError.SERVER)
            }
        } catch (e: ApiError.Network) {
            recording.retryLater(UploadError.NETWORK)
        }
    }

    /**
     * Envio confirmado: o .fit local deixa de ser necessário. Apaga depois de gravar o status, para
     * que uma falha entre os dois passos deixe no máximo um arquivo sobrando, nunca uma pendência sem arquivo.
     */
    private suspend fun RecordingEntity.uploaded(remoteActivityId: String?, file: File) {
        dao.markUploaded(this, remoteActivityId)
        if (!file.delete()) Log.w(TAG, "Não foi possível apagar ${file.name}")
    }

    private suspend fun RecordingEntity.fail(error: UploadError): Outcome {
        dao.updateRecording(copy(status = RecordingStatus.UPLOAD_FAILED, uploadError = error))
        return Outcome.REJECTED
    }

    private suspend fun RecordingEntity.retryLater(error: UploadError): Outcome {
        dao.updateRecording(copy(status = RecordingStatus.PENDING_UPLOAD, uploadError = error))
        return Outcome.RETRY
    }

    private companion object {
        const val TAG = "ActivityUploader"
        val FIT_MEDIA_TYPE = "application/octet-stream".toMediaType()
    }
}
