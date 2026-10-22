package com.dutrail.app.recording.domain

import kotlin.math.max

/** Descarta fixes ruins antes de entrarem na distância e no arquivo. */
class PointFilter(private val config: Config = Config()) {

    data class Config(
        val maxAccuracyMeters: Float = RecordingConfig.POINT_MAX_ACCURACY_METERS,
        /** ~43 km/h, muito acima de qualquer corrida. Serve só para pegar saltos do GPS. */
        val maxSpeedMps: Double = 12.0,
        /** Fixes mais próximos que isso do anterior (ou fora de ordem) são duplicados. */
        val minIntervalMillis: Long = 900,
        /**
         * Depois de tantos saltos seguidos, o próximo ponto é aceito como início de trecho novo:
         * provavelmente o ponto de referência é que era o outlier, e rejeitar para sempre travaria a gravação.
         */
        val maxConsecutiveJumps: Int = 5,
    )

    enum class Rejection { NO_ACCURACY, LOW_ACCURACY, TOO_SOON, IMPOSSIBLE_SPEED }

    sealed interface Decision {
        data object Accept : Decision

        /** Aceito, mas sem somar distância desde o ponto anterior. */
        data object AcceptAsNewSegment : Decision

        data class Reject(val reason: Rejection) : Decision
    }

    /**
     * @param previous último ponto aceito do trecho atual (null no início do trecho).
     * @param consecutiveJumps quantos fixes seguidos já foram rejeitados por [Rejection.IMPOSSIBLE_SPEED].
     */
    fun evaluate(previous: TrackPoint?, sample: LocationSample, consecutiveJumps: Int): Decision {
        val accuracy = sample.accuracyMeters ?: return Decision.Reject(Rejection.NO_ACCURACY)
        if (accuracy > config.maxAccuracyMeters) return Decision.Reject(Rejection.LOW_ACCURACY)
        if (previous == null) return Decision.Accept

        val dtMillis = sample.timeMillis - previous.timeMillis
        if (dtMillis < config.minIntervalMillis) return Decision.Reject(Rejection.TOO_SOON)

        val distance = GeoMath.distanceMeters(previous.latitude, previous.longitude, sample.latitude, sample.longitude)
        // A margem de precisão evita rejeitar um ponto bom só porque o anterior estava um pouco deslocado.
        val maxPlausible = config.maxSpeedMps * dtMillis / 1000.0 + max(accuracy, previous.accuracyMeters)
        if (distance > maxPlausible) {
            return if (consecutiveJumps >= config.maxConsecutiveJumps) {
                Decision.AcceptAsNewSegment
            } else {
                Decision.Reject(Rejection.IMPOSSIBLE_SPEED)
            }
        }
        return Decision.Accept
    }
}
