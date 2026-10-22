package com.dutrail.app.recording.data

import com.dutrail.app.recording.data.fit.FitActivityEncoder
import com.dutrail.app.recording.data.fit.FitFileStorage
import com.dutrail.app.recording.data.upload.UploadScheduler
import com.dutrail.app.recording.domain.RecordingTracker
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Transforma uma gravação parada (FINISHING) em .fit e a coloca na fila de envio.
 *
 * Idempotente: o .fit é gerado uma única vez. Os reenvios mandam exatamente os mesmos bytes, e o
 * servidor responde 409 a um arquivo que já recebeu em vez de criar uma atividade duplicada.
 */
@Singleton
class RecordingFinalizer @Inject constructor(
    private val dao: RecordingDao,
    private val encoder: FitActivityEncoder,
    private val storage: FitFileStorage,
    private val uploadScheduler: UploadScheduler,
) {
    private val mutex = Mutex()

    suspend fun finalize(recordingId: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val recording = dao.recording(recordingId) ?: return@withLock
            if (recording.status != RecordingStatus.FINISHING) return@withLock

            val events = dao.events(recordingId).map { it.toDomain() }
            val points = dao.points(recordingId).map { it.toDomain() }
            val summary = RecordingTracker.restore(events, points).summary()
            val bytes = encoder.encode(
                FitActivityEncoder.Input(
                    events = events,
                    points = points,
                    summary = summary,
                    serialNumber = storage.installSerialNumber(),
                    zoneOffsetSeconds = ZoneId.systemDefault().rules
                        .getOffset(Instant.ofEpochMilli(summary.startTimeMillis)).totalSeconds,
                ),
            )
            val fileName = "dutrail-${recording.id}-${recording.startedAtMillis}.fit"
            storage.write(fileName, bytes)
            dao.updateRecording(
                recording.copy(
                    status = RecordingStatus.PENDING_UPLOAD,
                    endedAtMillis = summary.endTimeMillis,
                    distanceMeters = summary.distanceMeters,
                    timerMillis = summary.timerMillis,
                    fitFileName = fileName,
                    uploadError = null,
                ),
            )
            uploadScheduler.enqueue(recordingId)
        }
    }

    /** Conclui gravações que pararam mas não chegaram a gerar o arquivo (processo morto no meio). */
    suspend fun finalizeInterrupted() {
        dao.finishingIds().forEach { finalize(it) }
    }
}
