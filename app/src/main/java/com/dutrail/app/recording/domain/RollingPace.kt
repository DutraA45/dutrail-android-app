package com.dutrail.app.recording.domain

/**
 * Pace atual suavizado: velocidade média numa janela curta (distância percorrida / tempo), em vez
 * da velocidade instantânea, que oscila muito a cada fix.
 */
class RollingPace(
    private val windowMillis: Long = 10_000,
    /** Abaixo disso (~33 min/km) o corredor está praticamente parado: não exibe pace. */
    private val minSpeedMps: Double = 0.5,
    private val staleAfterMillis: Long = RecordingConfig.FIX_STALE_AFTER_MILLIS,
    private val minSpanMillis: Long = 3_000,
) {
    private data class Sample(val timeMillis: Long, val distanceMeters: Double)

    private val samples = ArrayDeque<Sample>()

    fun add(timeMillis: Long, distanceMeters: Double) {
        samples.addLast(Sample(timeMillis, distanceMeters))
        while (samples.first().timeMillis < timeMillis - windowMillis) samples.removeFirst()
    }

    /** Chamado na pausa: o pace depois de retomar não deve misturar pontos de antes. */
    fun reset() = samples.clear()

    fun paceSecondsPerKm(nowMillis: Long): Double? {
        if (samples.size < 2) return null
        val first = samples.first()
        val last = samples.last()
        if (nowMillis - last.timeMillis > staleAfterMillis) return null
        val spanMillis = last.timeMillis - first.timeMillis
        if (spanMillis < minSpanMillis) return null
        val speed = (last.distanceMeters - first.distanceMeters) / (spanMillis / 1000.0)
        return if (speed < minSpeedMps) null else 1000.0 / speed
    }
}
