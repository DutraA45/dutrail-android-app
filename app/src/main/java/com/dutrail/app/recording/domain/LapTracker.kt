package com.dutrail.app.recording.domain

import kotlin.math.roundToLong

/**
 * Parciais por distância (1 km por padrão). O instante em que a fronteira foi cruzada é
 * interpolado entre os dois pontos vizinhos, e o tempo do lap exclui pausas.
 */
class LapTracker(private val lapDistanceMeters: Double = RecordingConfig.LAP_DISTANCE_METERS) {

    private val completed = mutableListOf<Lap>()
    val laps: List<Lap> get() = completed

    private var activityStartMillis: Long? = null
    private var previous: TrackPoint? = null

    private val currentLapStartMillis: Long?
        get() = completed.lastOrNull()?.endTimeMillis ?: activityStartMillis

    private val completedDistance: Double get() = completed.size * lapDistanceMeters

    fun start(timeMillis: Long) {
        if (activityStartMillis == null) activityStartMillis = timeMillis
    }

    /** @return os laps fechados por este ponto (normalmente nenhum ou um). */
    fun add(point: TrackPoint, events: List<TimerEvent>): List<Lap> {
        val prev = previous
        previous = point
        if (prev == null) return emptyList()

        val closed = mutableListOf<Lap>()
        while (point.distanceMeters >= completedDistance + lapDistanceMeters) {
            val boundary = completedDistance + lapDistanceMeters
            val fraction = (boundary - prev.distanceMeters) / (point.distanceMeters - prev.distanceMeters)
            val crossedAt = prev.timeMillis + ((point.timeMillis - prev.timeMillis) * fraction).roundToLong()
            val lap = lapUntil(crossedAt, lapDistanceMeters, events)
            completed += lap
            closed += lap
        }
        return closed
    }

    /** Lap em andamento, do último km fechado até [atMillis]. */
    fun currentLap(totalDistanceMeters: Double, atMillis: Long, events: List<TimerEvent>): Lap? {
        currentLapStartMillis ?: return null
        return lapUntil(atMillis, totalDistanceMeters - completedDistance, events)
    }

    /** Laps para o arquivo: os completos mais o restante até o fim (sempre pelo menos um). */
    fun finalLaps(totalDistanceMeters: Double, endMillis: Long, events: List<TimerEvent>): List<Lap> {
        val remaining = currentLap(totalDistanceMeters, endMillis, events) ?: return completed.toList()
        val hasRemainder = remaining.distanceMeters > 0.5 || remaining.timerMillis >= 1_000
        return if (hasRemainder || completed.isEmpty()) completed + remaining else completed.toList()
    }

    private fun lapUntil(endMillis: Long, distanceMeters: Double, events: List<TimerEvent>): Lap {
        val start = checkNotNull(currentLapStartMillis) { "LapTracker.start() não foi chamado" }
        return Lap(
            index = completed.size + 1,
            startTimeMillis = start,
            endTimeMillis = endMillis,
            distanceMeters = distanceMeters,
            timerMillis = Timeline.activeMillis(events, endMillis) - Timeline.activeMillis(events, start),
        )
    }
}

/** Ganho/perda de altitude com histerese, para não somar o ruído vertical do GPS. */
class ElevationTracker(private val thresholdMeters: Double = RecordingConfig.ELEVATION_THRESHOLD_METERS) {
    var gainMeters = 0.0
        private set
    var lossMeters = 0.0
        private set

    private var reference: Double? = null

    fun add(altitudeMeters: Double?) {
        if (altitudeMeters == null) return
        val ref = reference ?: run {
            reference = altitudeMeters
            return
        }
        val delta = altitudeMeters - ref
        when {
            delta >= thresholdMeters -> {
                gainMeters += delta
                reference = altitudeMeters
            }
            delta <= -thresholdMeters -> {
                lossMeters -= delta
                reference = altitudeMeters
            }
        }
    }
}
