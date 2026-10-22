package com.dutrail.app.recording.domain

/** Parâmetros da gravação, centralizados para ajuste fino. */
object RecordingConfig {
    /** Precisão horizontal máxima (m) do fix para liberar o botão Iniciar. */
    const val START_MAX_ACCURACY_METERS = 20f

    /** Pontos com precisão pior que isso são descartados durante a gravação. */
    const val POINT_MAX_ACCURACY_METERS = 25f

    const val LOCATION_INTERVAL_MILLIS = 1_000L

    /** Sem fix novo há mais tempo que isso, o sinal conta como perdido. */
    const val FIX_STALE_AFTER_MILLIS = 5_000L

    const val LAP_DISTANCE_METERS = 1_000.0

    /**
     * Deslocamento mínimo, a partir da última âncora, para a distância avançar. Evita somar o
     * "tremor" do GPS com o corredor parado (ex.: num semáforo sem pausar).
     */
    const val MIN_DISPLACEMENT_METERS = 3.0

    /** Variação mínima de altitude para contar ganho/perda; a altitude do GPS é ruidosa. */
    const val ELEVATION_THRESHOLD_METERS = 5.0
}

/** Fix de localização já convertido para o relógio de parede do aparelho. */
data class LocationSample(
    val timeMillis: Long,
    val latitude: Double,
    val longitude: Double,
    /** Precisão horizontal (raio, 68%). Null quando o provedor não informa. */
    val accuracyMeters: Float?,
    val altitudeMeters: Double?,
    /** Velocidade informada pelo GNSS, se houver. */
    val speedMps: Float?,
)

/** Ponto aceito pelo filtro e persistido. */
data class TrackPoint(
    val timeMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val accuracyMeters: Float,
    val speedMps: Float?,
    /** Distância acumulada da atividade até este ponto (monotônica). */
    val distanceMeters: Double,
    /** Trecho contínuo. Muda após pausa ou interrupção; não se soma distância entre trechos. */
    val segment: Int,
)

enum class TimerEventType { START, PAUSE, RESUME, STOP }

data class TimerEvent(val timeMillis: Long, val type: TimerEventType)

data class Lap(
    /** 1 = primeiro km. */
    val index: Int,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val distanceMeters: Double,
    /** Tempo de cronômetro (sem pausas). */
    val timerMillis: Long,
) {
    val elapsedMillis: Long get() = endTimeMillis - startTimeMillis

    val paceSecondsPerKm: Double?
        get() = if (distanceMeters > 0) (timerMillis / 1000.0) / (distanceMeters / 1000.0) else null
}
