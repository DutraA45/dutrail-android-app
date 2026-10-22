package com.dutrail.app.recording.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {

    @Insert
    suspend fun insertRecording(recording: RecordingEntity): Long

    @Update
    suspend fun updateRecording(recording: RecordingEntity)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteRecording(id: Long)

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun recording(id: Long): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE id = :id")
    fun observeRecording(id: Long): Flow<RecordingEntity?>

    @Query("SELECT * FROM recordings WHERE status IN ('RECORDING', 'PAUSED') ORDER BY startedAtMillis DESC LIMIT 1")
    suspend fun unfinishedRecording(): RecordingEntity?

    @Query("SELECT id FROM recordings WHERE status = 'FINISHING'")
    suspend fun finishingIds(): List<Long>

    @Query("SELECT id FROM recordings WHERE status = 'PENDING_UPLOAD' AND ownerUserId = :userId")
    suspend fun pendingUploadIds(userId: String): List<Long>

    @Query(
        "SELECT * FROM recordings WHERE status IN ('FINISHING', 'PENDING_UPLOAD', 'UPLOAD_FAILED') " +
            "AND ownerUserId = :userId ORDER BY startedAtMillis DESC",
    )
    fun observeNotUploaded(userId: String): Flow<List<RecordingEntity>>

    @Query("SELECT COUNT(*) FROM recordings WHERE status = 'UPLOADED'")
    fun observeUploadedCount(): Flow<Int>

    @Insert
    suspend fun insertPoint(point: TrackPointEntity)

    @Insert
    suspend fun insertEvent(event: TimerEventEntity)

    @Query("SELECT * FROM track_points WHERE recordingId = :recordingId ORDER BY timeMillis, id")
    suspend fun points(recordingId: Long): List<TrackPointEntity>

    @Query("SELECT latitude, longitude FROM track_points WHERE recordingId = :recordingId ORDER BY timeMillis, id")
    fun observeRoute(recordingId: Long): Flow<List<RoutePoint>>

    @Query("SELECT * FROM track_points WHERE recordingId = :recordingId ORDER BY timeMillis DESC, id DESC LIMIT 1")
    suspend fun lastPoint(recordingId: Long): TrackPointEntity?

    @Query("SELECT * FROM timer_events WHERE recordingId = :recordingId ORDER BY timeMillis, id")
    suspend fun events(recordingId: Long): List<TimerEventEntity>

    @Query("UPDATE recordings SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: RecordingStatus)

    @Query("DELETE FROM track_points WHERE recordingId = :recordingId")
    suspend fun deletePoints(recordingId: Long)

    @Query("DELETE FROM timer_events WHERE recordingId = :recordingId")
    suspend fun deleteEvents(recordingId: Long)

    @Transaction
    suspend fun createRecording(recording: RecordingEntity, start: TimerEventEntity): Long {
        val id = insertRecording(recording)
        insertEvent(start.copy(recordingId = id))
        return id
    }

    @Transaction
    suspend fun addEventAndSetStatus(event: TimerEventEntity, status: RecordingStatus) {
        insertEvent(event)
        setStatus(event.recordingId, status)
    }

    /** STOP e FINISHING juntos: nunca fica uma gravação "em andamento" com evento de fim. */
    @Transaction
    suspend fun stopRecording(stop: TimerEventEntity) {
        insertEvent(stop)
        setStatus(stop.recordingId, RecordingStatus.FINISHING)
    }

    /** Depois do envio o .fit fica como cópia local; os pontos não são mais necessários. */
    @Transaction
    suspend fun markUploaded(recording: RecordingEntity, remoteActivityId: String?) {
        updateRecording(
            recording.copy(status = RecordingStatus.UPLOADED, uploadError = null, remoteActivityId = remoteActivityId, fitFileName = null),
        )
        deletePoints(recording.id)
        deleteEvents(recording.id)
    }
}
