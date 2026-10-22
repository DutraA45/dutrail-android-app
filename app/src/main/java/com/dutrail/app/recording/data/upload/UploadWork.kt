package com.dutrail.app.recording.data.upload

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dutrail.app.activities.data.ActivitiesApi
import com.dutrail.app.auth.data.SessionStore
import com.dutrail.app.recording.data.RecordingDao
import com.dutrail.app.recording.data.fit.FitFileStorage
import dagger.Module
import dagger.Provides
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fila de envio. Um trabalho único por gravação, com restrição de rede e backoff exponencial:
 * sem internet a atividade fica salva como pendente e sobe sozinha quando a conexão voltar.
 */
@Singleton
class UploadScheduler @Inject constructor(@ApplicationContext private val context: Context) {

    /** @param replace true para "tentar agora" (reinicia o backoff de um envio que já estava esperando). */
    fun enqueue(recordingId: Long, replace: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf(UploadWorker.KEY_RECORDING_ID to recordingId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "upload-recording-$recordingId",
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }
}

@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val uploader: ActivityUploader,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val recordingId = inputData.getLong(KEY_RECORDING_ID, -1L)
        if (recordingId <= 0) return Result.failure()
        return when (uploader.upload(recordingId)) {
            ActivityUploader.Outcome.RETRY -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        const val KEY_RECORDING_ID = "recordingId"
    }
}

@Module
@InstallIn(SingletonComponent::class)
object UploadModule {
    @Provides
    fun activityUploader(dao: RecordingDao, api: ActivitiesApi, sessionStore: SessionStore, storage: FitFileStorage) =
        ActivityUploader(dao, api, sessionStore, storage::file)
}
