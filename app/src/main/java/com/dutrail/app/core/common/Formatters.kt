package com.dutrail.app.core.common

import java.util.Locale
import kotlin.math.roundToLong

/** Formatação de métricas esportivas, compartilhada entre lista, detalhe e relógio de gravação. */
object Formatters {

    /** "10,01" (km com duas casas, separador decimal do locale). */
    fun distanceKm(meters: Double, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%.2f", meters / 1000.0)

    /** "45:07" abaixo de uma hora, "1:02:05" a partir dela. */
    fun duration(totalSeconds: Long): String {
        val seconds = totalSeconds.coerceAtLeast(0)
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    /**
     * "5:12" (min:s por km). Paces acima de [MAX_PACE_SECONDS_PER_KM] (praticamente parado) viram "--:--".
     * Arredonda para o segundo antes de separar, para 5:59,6 virar 6:00 e não 5:60.
     */
    fun pace(secondsPerKm: Double?): String {
        if (secondsPerKm == null || secondsPerKm.isNaN() || secondsPerKm <= 0 || secondsPerKm > MAX_PACE_SECONDS_PER_KM) {
            return NO_PACE
        }
        val rounded = secondsPerKm.roundToLong()
        return "%d:%02d".format(rounded / 60, rounded % 60)
    }

    const val NO_PACE = "--:--"
    private const val MAX_PACE_SECONDS_PER_KM = 59 * 60 + 59
}
