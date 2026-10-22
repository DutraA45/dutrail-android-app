package com.dutrail.app.recording.data.fit

import com.dutrail.app.recording.domain.ActivitySummary
import com.dutrail.app.recording.domain.Lap
import com.dutrail.app.recording.domain.TimerEvent
import com.dutrail.app.recording.domain.TimerEventType
import com.dutrail.app.recording.domain.TrackPoint
import com.garmin.fit.Activity
import com.garmin.fit.ActivityMesg
import com.garmin.fit.BufferEncoder
import com.garmin.fit.DateTime
import com.garmin.fit.Event
import com.garmin.fit.EventMesg
import com.garmin.fit.EventType
import com.garmin.fit.File
import com.garmin.fit.FileIdMesg
import com.garmin.fit.LapMesg
import com.garmin.fit.LapTrigger
import com.garmin.fit.Manufacturer
import com.garmin.fit.Mesg
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.garmin.fit.SessionTrigger
import com.garmin.fit.Sport
import com.garmin.fit.SubSport
import com.garmin.fit.TimerTrigger
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Gera um arquivo FIT de atividade (corrida) com o Garmin FIT SDK.
 *
 * Mensagens: file_id, event (timer), record, lap (um por km + o restante), session e activity.
 * Os laps e o resumo vão no fim do arquivo ("summary last"), o que o protocolo permite.
 * Pausa e retomada são, no FIT, eventos de timer STOP/START; o fim da atividade é STOP_ALL.
 */
class FitActivityEncoder @Inject constructor() {

    data class Input(
        val events: List<TimerEvent>,
        val points: List<TrackPoint>,
        val summary: ActivitySummary,
        /** Identifica a instalação (par serial + time_created identifica o arquivo). */
        val serialNumber: Long,
        /** Fuso do aparelho no início, para o local_timestamp da activity. */
        val zoneOffsetSeconds: Int,
    )

    fun encode(input: Input): ByteArray {
        val summary = input.summary
        require(input.events.firstOrNull()?.type == TimerEventType.START) { "Primeiro evento deve ser START" }
        require(input.events.last().type == TimerEventType.STOP) { "Último evento deve ser STOP" }

        val encoder = BufferEncoder()
        encoder.write(fileId(summary.startTimeMillis, input.serialNumber))
        timeOrdered(input.events, input.points).forEach(encoder::write)
        summary.laps.forEachIndexed { i, lap -> encoder.write(lap(i, lap, isLast = i == summary.laps.lastIndex)) }
        encoder.write(session(summary, input.points.firstOrNull()))
        encoder.write(activity(summary, input.zoneOffsetSeconds))
        return encoder.close()
    }

    private fun fileId(startMillis: Long, serialNumber: Long) = FileIdMesg().apply {
        setType(File.ACTIVITY)
        setManufacturer(Manufacturer.DEVELOPMENT)
        setProduct(PRODUCT_ID)
        setProductName(PRODUCT_NAME)
        setSerialNumber(serialNumber)
        setTimeCreated(fitTime(startMillis))
    }

    /** Records e eventos de timer intercalados em ordem cronológica, um record por segundo. */
    private fun timeOrdered(events: List<TimerEvent>, points: List<TrackPoint>): List<Mesg> {
        // No mesmo segundo: START/RESUME antes dos records, PAUSE/STOP depois.
        val entries = events.map { Triple(it.timeMillis, if (it.isTimerStart()) 0 else 2, it as Any) } +
            points.map { Triple(it.timeMillis, 1, it as Any) }

        var lastRecordSecond = Long.MIN_VALUE
        return entries
            .sortedWith(compareBy({ it.first / 1000 }, { it.second }, { it.first }))
            .mapNotNull { (time, _, item) ->
                when (item) {
                    is TimerEvent -> timerEvent(item)
                    is TrackPoint -> {
                        val second = time / 1000
                        // FIT guarda o timestamp em segundos: um segundo repetido viraria dt = 0 para quem lê.
                        if (second == lastRecordSecond) null else record(item).also { lastRecordSecond = second }
                    }
                    else -> null
                }
            }
    }

    private fun timerEvent(event: TimerEvent) = EventMesg().apply {
        setTimestamp(fitTime(event.timeMillis))
        setEvent(Event.TIMER)
        setEventType(
            when (event.type) {
                TimerEventType.START, TimerEventType.RESUME -> EventType.START
                TimerEventType.PAUSE -> EventType.STOP
                TimerEventType.STOP -> EventType.STOP_ALL
            },
        )
        setTimerTrigger(TimerTrigger.MANUAL)
        setEventGroup(0)
    }

    private fun record(point: TrackPoint) = RecordMesg().apply {
        setTimestamp(fitTime(point.timeMillis))
        setPositionLat(semicircles(point.latitude))
        setPositionLong(semicircles(point.longitude))
        setDistance(point.distanceMeters.toFloat())
        point.speedMps?.let {
            setSpeed(it)
            setEnhancedSpeed(it)
        }
        point.altitudeMeters?.let {
            setAltitude(it.toFloat())
            setEnhancedAltitude(it.toFloat())
        }
    }

    private fun lap(index: Int, lap: Lap, isLast: Boolean) = LapMesg().apply {
        val timerSeconds = lap.timerMillis / 1000f
        setMessageIndex(index)
        setTimestamp(fitTime(lap.endTimeMillis))
        setStartTime(fitTime(lap.startTimeMillis))
        setEvent(Event.LAP)
        setEventType(EventType.STOP)
        setLapTrigger(if (isLast) LapTrigger.SESSION_END else LapTrigger.DISTANCE)
        setSport(Sport.RUNNING)
        setSubSport(SubSport.GENERIC)
        setTotalElapsedTime(lap.elapsedMillis / 1000f)
        setTotalTimerTime(timerSeconds)
        setTotalMovingTime(timerSeconds)
        setTotalDistance(lap.distanceMeters.toFloat())
        averageSpeed(lap.distanceMeters, timerSeconds)?.let {
            setAvgSpeed(it)
            setEnhancedAvgSpeed(it)
        }
    }

    private fun session(summary: ActivitySummary, firstPoint: TrackPoint?) = SessionMesg().apply {
        val timerSeconds = summary.timerMillis / 1000f
        setMessageIndex(0)
        setTimestamp(fitTime(summary.endTimeMillis))
        setStartTime(fitTime(summary.startTimeMillis))
        setEvent(Event.SESSION)
        setEventType(EventType.STOP)
        setTrigger(SessionTrigger.ACTIVITY_END)
        setSport(Sport.RUNNING)
        setSubSport(SubSport.GENERIC)
        setTotalElapsedTime(summary.elapsedMillis / 1000f)
        setTotalTimerTime(timerSeconds)
        // Sem auto-pause, tempo em movimento = tempo de cronômetro.
        setTotalMovingTime(timerSeconds)
        setTotalDistance(summary.distanceMeters.toFloat())
        setTotalAscent(summary.ascentMeters.roundToInt())
        setTotalDescent(summary.descentMeters.roundToInt())
        averageSpeed(summary.distanceMeters, timerSeconds)?.let {
            setAvgSpeed(it)
            setEnhancedAvgSpeed(it)
        }
        if (summary.maxSpeedMps > 0) {
            setMaxSpeed(summary.maxSpeedMps)
            setEnhancedMaxSpeed(summary.maxSpeedMps)
        }
        setFirstLapIndex(0)
        setNumLaps(summary.laps.size)
        firstPoint?.let {
            setStartPositionLat(semicircles(it.latitude))
            setStartPositionLong(semicircles(it.longitude))
        }
    }

    private fun activity(summary: ActivitySummary, zoneOffsetSeconds: Int) = ActivityMesg().apply {
        val timestamp = fitTime(summary.endTimeMillis)
        setTimestamp(timestamp)
        setLocalTimestamp(timestamp.timestamp + zoneOffsetSeconds)
        setTotalTimerTime(summary.timerMillis / 1000f)
        setNumSessions(1)
        setType(Activity.MANUAL)
        setEvent(Event.ACTIVITY)
        setEventType(EventType.STOP)
    }

    private fun TimerEvent.isTimerStart() = type == TimerEventType.START || type == TimerEventType.RESUME

    private fun averageSpeed(distanceMeters: Double, timerSeconds: Float): Float? =
        if (timerSeconds > 0) (distanceMeters / timerSeconds).toFloat() else null

    companion object {
        /** Segundos entre a época Unix e a época FIT (1989-12-31T00:00:00Z). */
        const val FIT_EPOCH_OFFSET_SECONDS = 631_065_600L
        private const val PRODUCT_ID = 1
        private const val PRODUCT_NAME = "Dutrail Android"

        fun fitTime(epochMillis: Long) = DateTime(Math.floorDiv(epochMillis, 1000L) - FIT_EPOCH_OFFSET_SECONDS)

        /** Graus para semicírculos (2^31 / 180), o formato de posição do FIT. */
        fun semicircles(degrees: Double): Int =
            (degrees * (2_147_483_648.0 / 180.0)).roundToLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }
}
