package com.dutrail.app.recording.domain

import com.dutrail.app.testing.Track
import com.dutrail.app.testing.Track.run
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingTrackerTest {

    private val t0 = Track.T0

    @Test
    fun `corrida em linha reta acumula a distância percorrida`() {
        val tracker = RecordingTracker()
        tracker.start(t0)
        tracker.run(t0, seconds = 600, speedMps = 3.0)

        val stats = tracker.stats(t0 + 600_000)
        assertEquals(1800.0, stats.distanceMeters, ONE_STEP)
        assertEquals(600_000, stats.activeMillis)
        assertEquals(333.3, stats.averagePaceSecondsPerKm!!, 1.0)
        assertEquals(333.3, stats.currentPaceSecondsPerKm!!, 1.0)
    }

    @Test
    fun `caminhada lenta não é cortada pelo limiar de deslocamento`() {
        val tracker = RecordingTracker()
        tracker.start(t0)
        tracker.run(t0, seconds = 300, speedMps = 1.0)
        assertEquals(300.0, tracker.distanceMeters, ONE_STEP)
    }

    @Test
    fun `tremor do GPS com o corredor parado não vira distância`() {
        val tracker = RecordingTracker()
        tracker.start(t0)
        val random = Random(42)
        for (i in 0..120) {
            tracker.onLocation(Track.sample(t0 + i * 1000L, random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0)))
        }
        assertEquals(0.0, tracker.distanceMeters, 0.0)
        assertNull(tracker.stats(t0 + 120_000).currentPaceSecondsPerKm)
    }

    @Test
    fun `salto isolado do GPS é descartado sem afetar a distância`() {
        val tracker = RecordingTracker()
        tracker.start(t0)
        val north = tracker.run(t0, seconds = 100, speedMps = 3.0)
        assertNull(tracker.onLocation(Track.sample(t0 + 101_000, north + 250.0)))
        tracker.run(t0 + 102_000, seconds = 100, speedMps = 3.0, startNorth = north + 6.0)

        assertEquals(606.0, tracker.distanceMeters, ONE_STEP)
    }

    @Test
    fun `pontos de baixa precisão são descartados`() {
        val tracker = RecordingTracker()
        tracker.start(t0)
        assertNull(tracker.onLocation(Track.sample(t0, 0.0, accuracy = 50f)))
        assertNotNull(tracker.onLocation(Track.sample(t0 + 1000, 0.0, accuracy = 6f)))
    }

    @Test
    fun `pausa exclui tempo e o deslocamento feito durante ela`() {
        val tracker = RecordingTracker()
        tracker.start(t0)
        val beforePause = tracker.run(t0, seconds = 100, speedMps = 3.0) // 300 m
        tracker.pause(t0 + 100_000)

        // Andou 180 m pausado: os fixes chegam, mas não contam.
        assertNull(tracker.onLocation(Track.sample(t0 + 130_000, beforePause + 90.0)))
        tracker.resume(t0 + 160_000)

        val resumeAt = beforePause + 180.0
        tracker.run(t0 + 160_000, seconds = 100, speedMps = 3.0, startNorth = resumeAt)

        val stats = tracker.stats(t0 + 260_000)
        assertEquals(600.0, stats.distanceMeters, 6.0)
        assertEquals(200_000, stats.activeMillis)
        assertEquals(1, tracker.lastPoint!!.segment)
    }

    @Test
    fun `fecha um lap a cada km com o tempo interpolado`() {
        val tracker = RecordingTracker()
        tracker.start(t0)
        tracker.run(t0, seconds = 1000, speedMps = 2.5) // 2,5 km, 400 s/km

        val laps = tracker.stats(t0 + 1_000_000).completedLaps
        assertEquals(2, laps.size)
        assertEquals(listOf(1, 2), laps.map { it.index })
        laps.forEach {
            assertEquals(1000.0, it.distanceMeters, 0.0)
            assertEquals(400_000.0, it.timerMillis.toDouble(), 1_500.0)
            assertEquals(400.0, it.paceSecondsPerKm!!, 1.5)
        }
        assertEquals(laps[0].endTimeMillis, laps[1].startTimeMillis)
    }

    @Test
    fun `tempo do lap não inclui a pausa`() {
        val tracker = RecordingTracker()
        tracker.start(t0)
        val north = tracker.run(t0, seconds = 200, speedMps = 2.5) // 500 m
        tracker.pause(t0 + 200_000)
        tracker.resume(t0 + 500_000) // 5 min parado
        tracker.run(t0 + 500_000, seconds = 220, speedMps = 2.5, startNorth = north)

        val lap = tracker.stats(t0 + 720_000).completedLaps.single()
        assertEquals(400_000.0, lap.timerMillis.toDouble(), 1_500.0)
        assertEquals(700_000.0, lap.elapsedMillis.toDouble(), 1_500.0)
    }

    @Test
    fun `resumo final inclui o lap parcial`() {
        val tracker = RecordingTracker()
        tracker.start(t0)
        tracker.run(t0, seconds = 1000, speedMps = 2.5)
        tracker.stop(t0 + 1_000_000)

        val summary = tracker.summary()
        assertEquals(3, summary.laps.size)
        assertEquals(500.0, summary.laps.last().distanceMeters, 3.0)
        assertEquals(summary.distanceMeters, summary.laps.sumOf { it.distanceMeters }, 0.001)
        assertEquals(1_000_000, summary.timerMillis)
    }

    @Test
    fun `restore reconstrói o mesmo estado a partir do que foi persistido`() {
        val live = RecordingTracker()
        val points = mutableListOf<TrackPoint>()
        val events = mutableListOf(live.start(t0))
        for (i in 0..700) live.onLocation(Track.sample(t0 + i * 1000L, i * 2.5, altitude = 700.0 + i * 0.1))?.let(points::add)
        events += live.pause(t0 + 700_000)!!
        events += live.resume(t0 + 760_000)!!
        for (i in 0..300) live.onLocation(Track.sample(t0 + 760_000 + i * 1000L, 1750.0 + i * 2.5))?.let(points::add)

        val restored = RecordingTracker.restore(events, points)
        val now = t0 + 1_060_000
        val expected = live.stats(now)
        val actual = restored.stats(now)
        assertEquals(TimerState.RUNNING, restored.timerState)
        assertEquals(expected.distanceMeters, actual.distanceMeters, 0.0)
        assertEquals(expected.activeMillis, actual.activeMillis)
        assertEquals(expected.completedLaps, actual.completedLaps)
        assertEquals(expected.elevationGainMeters, actual.elevationGainMeters, 0.0)
        assertTrue(actual.elevationGainMeters > 0)
    }

    @Test
    fun `comandos fora de hora são ignorados`() {
        val tracker = RecordingTracker()
        tracker.start(t0)
        assertNull(tracker.resume(t0 + 1000))
        assertNotNull(tracker.pause(t0 + 2000))
        assertNull(tracker.pause(t0 + 3000))
        assertNotNull(tracker.stop(t0 + 4000))
        assertNull(tracker.stop(t0 + 5000))
        assertNull(tracker.onLocation(Track.sample(t0 + 6000, 0.0)))
    }

    private companion object {
        /** O deslocamento desde a última âncora ainda não somado fica abaixo de um limiar. */
        const val ONE_STEP = RecordingConfig.MIN_DISPLACEMENT_METERS + 0.5
    }
}
