package com.dutrail.app.recording.domain

import kotlin.math.max

enum class TimerState { RUNNING, PAUSED, STOPPED }

/** Métricas exibidas no relógio de gravação. */
data class RecordingStats(
    val timerState: TimerState,
    val activeMillis: Long,
    val distanceMeters: Double,
    val currentPaceSecondsPerKm: Double?,
    val averagePaceSecondsPerKm: Double?,
    val completedLaps: List<Lap>,
    val elevationGainMeters: Double,
)

/**
 * Estado de uma gravação: aplica o filtro, acumula distância, laps, elevação e pace. É Kotlin puro
 * (sem Android). Quem chama persiste os eventos e pontos devolvidos, e [restore] reconstrói o estado
 * a partir deles.
 *
 * Distância: soma deslocamentos a partir de uma âncora e só avança quando o ponto se afasta pelo
 * menos [minDisplacementMeters]. Assim o tremor do GPS com o corredor parado não vira distância.
 * O pace atual usa a distância "provisória" (âncora + afastamento atual), que é contínua, para não
 * oscilar a cada degrau.
 */
class RecordingTracker(
    private val filter: PointFilter = PointFilter(),
    private val minDisplacementMeters: Double = RecordingConfig.MIN_DISPLACEMENT_METERS,
    lapDistanceMeters: Double = RecordingConfig.LAP_DISTANCE_METERS,
) {
    private val _events = mutableListOf<TimerEvent>()
    val events: List<TimerEvent> get() = _events

    var timerState: TimerState? = null
        private set

    var lastPoint: TrackPoint? = null
        private set

    var distanceMeters = 0.0
        private set

    var maxSpeedMps = 0f
        private set

    private var anchor: TrackPoint? = null
    private var segment = 0
    private var startNewSegment = true
    private var consecutiveJumps = 0

    private val lapTracker = LapTracker(lapDistanceMeters)
    private val elevation = ElevationTracker()
    private val rollingPace = RollingPace()

    fun start(timeMillis: Long): TimerEvent {
        check(timerState == null) { "Gravação já iniciada" }
        timerState = TimerState.RUNNING
        lapTracker.start(timeMillis)
        return addEvent(timeMillis, TimerEventType.START)
    }

    fun pause(timeMillis: Long): TimerEvent? {
        if (timerState != TimerState.RUNNING) return null
        timerState = TimerState.PAUSED
        rollingPace.reset()
        return addEvent(timeMillis, TimerEventType.PAUSE)
    }

    fun resume(timeMillis: Long): TimerEvent? {
        if (timerState != TimerState.PAUSED) return null
        timerState = TimerState.RUNNING
        // O que se andou durante a pausa não conta: o próximo ponto abre um trecho novo.
        startNewSegment = true
        return addEvent(timeMillis, TimerEventType.RESUME)
    }

    fun stop(timeMillis: Long): TimerEvent? {
        if (timerState != TimerState.RUNNING && timerState != TimerState.PAUSED) return null
        timerState = TimerState.STOPPED
        return addEvent(timeMillis, TimerEventType.STOP)
    }

    /** @return o ponto a persistir, ou null se foi rejeitado (ou se não está gravando). */
    fun onLocation(sample: LocationSample): TrackPoint? {
        if (timerState != TimerState.RUNNING) return null

        val previous = if (startNewSegment) null else lastPoint
        val newSegment = when (val decision = filter.evaluate(previous, sample, consecutiveJumps)) {
            is PointFilter.Decision.Reject -> {
                if (decision.reason == PointFilter.Rejection.IMPOSSIBLE_SPEED) consecutiveJumps++
                return null
            }
            PointFilter.Decision.Accept -> previous == null
            PointFilter.Decision.AcceptAsNewSegment -> true
        }
        consecutiveJumps = 0
        if (newSegment && lastPoint != null) segment++

        var provisional = distanceMeters
        val currentAnchor = anchor
        if (newSegment || currentAnchor == null) {
            anchor = null
        } else {
            val fromAnchor = GeoMath.distanceMeters(currentAnchor.latitude, currentAnchor.longitude, sample.latitude, sample.longitude)
            if (fromAnchor >= minDisplacementMeters) {
                distanceMeters += fromAnchor
                provisional = distanceMeters
                anchor = null
            } else {
                provisional = distanceMeters + fromAnchor
            }
        }

        val point = TrackPoint(
            timeMillis = sample.timeMillis,
            latitude = sample.latitude,
            longitude = sample.longitude,
            altitudeMeters = sample.altitudeMeters,
            accuracyMeters = sample.accuracyMeters ?: 0f,
            speedMps = sample.speedMps,
            distanceMeters = distanceMeters,
            segment = segment,
        )
        if (anchor == null) anchor = point
        startNewSegment = false
        record(point, provisional)
        return point
    }

    fun stats(nowMillis: Long): RecordingStats {
        val active = Timeline.activeMillis(_events, nowMillis)
        val averagePace = if (distanceMeters >= MIN_DISTANCE_FOR_AVERAGE_PACE) {
            (active / 1000.0) / (distanceMeters / 1000.0)
        } else {
            null
        }
        return RecordingStats(
            timerState = timerState ?: TimerState.STOPPED,
            activeMillis = active,
            distanceMeters = distanceMeters,
            currentPaceSecondsPerKm = if (timerState == TimerState.RUNNING) rollingPace.paceSecondsPerKm(nowMillis) else null,
            averagePaceSecondsPerKm = averagePace,
            completedLaps = lapTracker.laps.toList(),
            elevationGainMeters = elevation.gainMeters,
        )
    }

    /** Resumo final (para o .fit e para a fila de envio). Requer [stop]. */
    fun summary(): ActivitySummary {
        val start = _events.first().timeMillis
        val end = _events.last().timeMillis
        check(_events.last().type == TimerEventType.STOP) { "Gravação não terminada" }
        return ActivitySummary(
            startTimeMillis = start,
            endTimeMillis = end,
            timerMillis = Timeline.activeMillis(_events, end),
            distanceMeters = distanceMeters,
            ascentMeters = elevation.gainMeters,
            descentMeters = elevation.lossMeters,
            maxSpeedMps = maxSpeedMps,
            laps = lapTracker.finalLaps(distanceMeters, end, _events),
        )
    }

    private fun addEvent(timeMillis: Long, type: TimerEventType): TimerEvent =
        TimerEvent(timeMillis, type).also { _events += it }

    private fun record(point: TrackPoint, provisionalDistance: Double) {
        lastPoint = point
        lapTracker.add(point, _events)
        elevation.add(point.altitudeMeters)
        rollingPace.add(point.timeMillis, provisionalDistance)
        point.speedMps?.let { maxSpeedMps = max(maxSpeedMps, it) }
    }

    companion object {
        private const val MIN_DISTANCE_FOR_AVERAGE_PACE = 50.0

        /**
         * Reconstrói o estado a partir do que foi persistido, por exemplo depois de o processo ser
         * morto. Os pontos já passaram pelo filtro, então entram direto.
         */
        fun restore(events: List<TimerEvent>, points: List<TrackPoint>): RecordingTracker {
            require(events.isNotEmpty() && events.first().type == TimerEventType.START) { "Eventos sem START" }
            return RecordingTracker().apply {
                _events += events
                lapTracker.start(events.first().timeMillis)
                points.forEach { record(it, it.distanceMeters) }
                distanceMeters = points.lastOrNull()?.distanceMeters ?: 0.0
                segment = points.lastOrNull()?.segment ?: 0
                anchor = points.lastOrNull()
                rollingPace.reset()
                timerState = when (events.last().type) {
                    TimerEventType.START, TimerEventType.RESUME -> TimerState.RUNNING
                    TimerEventType.PAUSE -> TimerState.PAUSED
                    TimerEventType.STOP -> TimerState.STOPPED
                }
            }
        }
    }
}

data class ActivitySummary(
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val timerMillis: Long,
    val distanceMeters: Double,
    val ascentMeters: Double,
    val descentMeters: Double,
    val maxSpeedMps: Float,
    val laps: List<Lap>,
) {
    val elapsedMillis: Long get() = endTimeMillis - startTimeMillis
}

enum class GpsSignal {
    NONE, WEAK, FAIR, GOOD;

    val allowsStart: Boolean get() = this == FAIR || this == GOOD

    companion object {
        private const val GOOD_ACCURACY_METERS = 8f

        fun of(accuracyMeters: Float?, fixAgeMillis: Long): GpsSignal = when {
            accuracyMeters == null || fixAgeMillis > RecordingConfig.FIX_STALE_AFTER_MILLIS -> NONE
            accuracyMeters <= GOOD_ACCURACY_METERS -> GOOD
            accuracyMeters <= RecordingConfig.START_MAX_ACCURACY_METERS -> FAIR
            else -> WEAK
        }
    }
}
