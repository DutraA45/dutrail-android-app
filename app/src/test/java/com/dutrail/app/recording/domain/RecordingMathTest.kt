package com.dutrail.app.recording.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeoMathTest {
    @Test
    fun `um grau de latitude mede cerca de 111,2 km`() {
        assertEquals(111_195.0, GeoMath.distanceMeters(0.0, 0.0, 1.0, 0.0), 1.0)
    }

    @Test
    fun `distância entre São Paulo e Rio`() {
        // Praça da Sé ↔ Cristo Redentor: ~357 km em linha reta.
        assertEquals(357_000.0, GeoMath.distanceMeters(-23.5505, -46.6333, -22.9519, -43.2105), 2_000.0)
    }

    @Test
    fun `pontos iguais têm distância zero`() {
        assertEquals(0.0, GeoMath.distanceMeters(-23.5, -46.6, -23.5, -46.6), 0.0)
    }
}

class TimelineTest {
    private val events = listOf(
        TimerEvent(0, TimerEventType.START),
        TimerEvent(100_000, TimerEventType.PAUSE),
        TimerEvent(160_000, TimerEventType.RESUME),
        TimerEvent(260_000, TimerEventType.STOP),
    )

    @Test
    fun `tempo ativo exclui pausas`() {
        assertEquals(50_000, Timeline.activeMillis(events, 50_000))
        assertEquals(100_000, Timeline.activeMillis(events, 130_000))
        assertEquals(140_000, Timeline.activeMillis(events, 200_000))
        assertEquals(200_000, Timeline.activeMillis(events, 260_000))
        assertEquals(200_000, Timeline.activeMillis(events, 999_000))
    }

    @Test
    fun `gravação em andamento conta até agora`() {
        assertEquals(30_000, Timeline.activeMillis(listOf(TimerEvent(0, TimerEventType.START)), 30_000))
    }
}

class RollingPaceTest {
    @Test
    fun `velocidade constante dá pace estável`() {
        val pace = RollingPace()
        for (i in 0..30) pace.add(i * 1000L, i * 3.0) // 3 m/s = 5:33 /km
        assertEquals(333.3, pace.paceSecondsPerKm(30_000)!!, 0.5)
    }

    @Test
    fun `suaviza oscilação ponto a ponto`() {
        val pace = RollingPace()
        var distance = 0.0
        // Alterna 2 m e 4 m por segundo: o pace instantâneo pularia entre 4:10 e 8:20.
        for (i in 0..30) {
            pace.add(i * 1000L, distance)
            distance += if (i % 2 == 0) 2.0 else 4.0
        }
        assertEquals(333.3, pace.paceSecondsPerKm(30_000)!!, 20.0)
    }

    @Test
    fun `sem fix recente ou parado não mostra pace`() {
        val moving = RollingPace()
        for (i in 0..10) moving.add(i * 1000L, i * 3.0)
        assertNull(moving.paceSecondsPerKm(10_000 + RecordingConfig.FIX_STALE_AFTER_MILLIS + 1))

        val stopped = RollingPace()
        for (i in 0..10) stopped.add(i * 1000L, i * 0.1)
        assertNull(stopped.paceSecondsPerKm(10_000))
    }
}

class ElevationTrackerTest {
    @Test
    fun `ignora ruído abaixo do limiar e soma subidas e descidas reais`() {
        val elevation = ElevationTracker(thresholdMeters = 5.0)
        listOf(100.0, 102.0, 99.0, 101.0, 106.0, 112.0, 111.0, 104.0, 98.0).forEach(elevation::add)
        // 100 → 106 (+6) → 112 (+6) → 104 (-8) → 98 (-6)
        assertEquals(12.0, elevation.gainMeters, 0.001)
        assertEquals(14.0, elevation.lossMeters, 0.001)
    }

    @Test
    fun `altitude ausente é ignorada`() {
        val elevation = ElevationTracker()
        elevation.add(null)
        elevation.add(100.0)
        elevation.add(null)
        elevation.add(120.0)
        assertEquals(20.0, elevation.gainMeters, 0.001)
    }
}
