package com.dutrail.app.testing

import com.dutrail.app.auth.data.SessionStore
import com.dutrail.app.auth.domain.Session
import com.dutrail.app.recording.data.fit.FitActivityEncoder
import com.dutrail.app.recording.domain.LocationSample
import com.dutrail.app.recording.domain.RecordingTracker
import com.dutrail.app.recording.domain.TimerEvent
import com.dutrail.app.recording.domain.TrackPoint
import kotlin.math.cos
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class InMemorySessionStore(initial: Session? = null) : SessionStore {
    private val state = MutableStateFlow(initial)
    override val session: Flow<Session?> = state
    override suspend fun current(): Session? = state.value
    override suspend fun save(session: Session) {
        state.value = session
    }
    override suspend fun clear() {
        state.value = null
    }
    fun peek(): Session? = state.value
}

/** Gera fixes sintéticos em metros a partir de uma origem (São Paulo). */
object Track {
    const val ORIGIN_LAT = -23.5505
    const val ORIGIN_LON = -46.6333
    const val T0 = 1_760_000_000_000L
    private const val METERS_PER_DEGREE = 6_371_008.8 * Math.PI / 180.0

    fun sample(
        timeMillis: Long,
        northMeters: Double,
        eastMeters: Double = 0.0,
        accuracy: Float? = 5f,
        altitude: Double? = null,
        speed: Float? = null,
    ) = LocationSample(
        timeMillis = timeMillis,
        latitude = ORIGIN_LAT + northMeters / METERS_PER_DEGREE,
        longitude = ORIGIN_LON + eastMeters / (METERS_PER_DEGREE * cos(Math.toRadians(ORIGIN_LAT))),
        accuracyMeters = accuracy,
        altitudeMeters = altitude,
        speedMps = speed,
    )

    /**
     * Corre para o norte a [speedMps] durante [seconds] s (um fix por segundo), a partir de
     * [startNorth] metros, a partir do instante [fromMillis]. Devolve a posição final.
     */
    fun RecordingTracker.run(fromMillis: Long, seconds: Int, speedMps: Double, startNorth: Double = 0.0): Double {
        for (i in 0..seconds) {
            onLocation(sample(fromMillis + i * 1000L, startNorth + i * speedMps, speed = speedMps.toFloat()))
        }
        return startNorth + seconds * speedMps
    }
}

/** Corrida sintética de 2,5 km a 2,5 m/s, com 1 min de pausa após 500 s, subindo 1 m a cada 10 s. */
object SyntheticRun {
    fun input(t0: Long = Track.T0, serialNumber: Long = 1234567L): FitActivityEncoder.Input {
        val tracker = RecordingTracker()
        val events = mutableListOf<TimerEvent>(tracker.start(t0))
        val points = mutableListOf<TrackPoint>()
        fun runSegment(fromMillis: Long, fromSecond: Int, seconds: Int) {
            for (i in 0..seconds) {
                val s = fromSecond + i
                tracker.onLocation(Track.sample(fromMillis + i * 1000L, s * 2.5, altitude = 760.0 + s / 10.0, speed = 2.5f))
                    ?.let(points::add)
            }
        }
        runSegment(t0, 0, 500)
        events += tracker.pause(t0 + 500_000)!!
        events += tracker.resume(t0 + 560_000)!!
        runSegment(t0 + 560_000, 500, 500)
        events += tracker.stop(t0 + 1_060_000)!!
        return FitActivityEncoder.Input(events, points, tracker.summary(), serialNumber, zoneOffsetSeconds = -3 * 3600)
    }
}
