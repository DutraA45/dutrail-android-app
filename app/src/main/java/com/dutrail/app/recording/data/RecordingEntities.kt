package com.dutrail.app.recording.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.dutrail.app.recording.domain.TimerEvent
import com.dutrail.app.recording.domain.TimerEventType
import com.dutrail.app.recording.domain.TrackPoint

/**
 * Ciclo de vida de uma gravação no aparelho:
 * RECORDING ⇄ PAUSED → FINISHING (parada, .fit ainda não gerado) → PENDING_UPLOAD → UPLOADED,
 * ou UPLOAD_FAILED quando o servidor recusa o arquivo (o arquivo continua guardado).
 */
enum class RecordingStatus { RECORDING, PAUSED, FINISHING, PENDING_UPLOAD, UPLOAD_FAILED, UPLOADED }

/** Motivo da última falha de envio, para a UI explicar o que está acontecendo. */
enum class UploadError { NETWORK, SERVER, SESSION_EXPIRED, INVALID_FILE, FILE_TOO_LARGE, FILE_MISSING }

@Entity(tableName = "recordings", indices = [Index("status")])
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Só esta conta pode enviar a gravação (outro usuário pode logar no mesmo aparelho). */
    val ownerUserId: String,
    val startedAtMillis: Long,
    val status: RecordingStatus,
    val endedAtMillis: Long? = null,
    val distanceMeters: Double? = null,
    val timerMillis: Long? = null,
    /**
     * Nome do .fit em filesDir/fit. Gerado uma única vez; reenvios mandam os mesmos bytes.
     * Volta a null quando o envio é confirmado (201/409) e o arquivo local é apagado.
     */
    val fitFileName: String? = null,
    val uploadError: UploadError? = null,
    /** Id da atividade criada na API (null também quando o envio deu 409, ou seja, já existia). */
    val remoteActivityId: String? = null,
)

@Entity(
    tableName = "track_points",
    foreignKeys = [ForeignKey(RecordingEntity::class, ["id"], ["recordingId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("recordingId")],
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: Long,
    val timeMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val accuracyMeters: Float,
    val speedMps: Float?,
    val distanceMeters: Double,
    val segment: Int,
) {
    fun toDomain() = TrackPoint(timeMillis, latitude, longitude, altitudeMeters, accuracyMeters, speedMps, distanceMeters, segment)
}

fun TrackPoint.toEntity(recordingId: Long) = TrackPointEntity(
    recordingId = recordingId,
    timeMillis = timeMillis,
    latitude = latitude,
    longitude = longitude,
    altitudeMeters = altitudeMeters,
    accuracyMeters = accuracyMeters,
    speedMps = speedMps,
    distanceMeters = distanceMeters,
    segment = segment,
)

@Entity(
    tableName = "timer_events",
    foreignKeys = [ForeignKey(RecordingEntity::class, ["id"], ["recordingId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("recordingId")],
)
data class TimerEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: Long,
    val timeMillis: Long,
    val type: TimerEventType,
) {
    fun toDomain() = TimerEvent(timeMillis, type)
}

/** Projeção leve dos pontos para o mapa. */
data class RoutePoint(val latitude: Double, val longitude: Double)
