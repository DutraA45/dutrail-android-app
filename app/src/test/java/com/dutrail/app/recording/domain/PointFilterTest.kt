package com.dutrail.app.recording.domain

import com.dutrail.app.recording.domain.PointFilter.Decision
import com.dutrail.app.recording.domain.PointFilter.Rejection
import com.dutrail.app.testing.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class PointFilterTest {

    private val filter = PointFilter()
    private val previous = TrackPoint(Track.T0, Track.ORIGIN_LAT, Track.ORIGIN_LON, null, 5f, null, 0.0, 0)

    @Test
    fun `aceita o primeiro ponto do trecho com boa precisão`() {
        assertEquals(Decision.Accept, filter.evaluate(null, Track.sample(Track.T0, 0.0, accuracy = 8f), 0))
    }

    @Test
    fun `rejeita fix sem precisão informada ou com precisão ruim`() {
        assertEquals(Decision.Reject(Rejection.NO_ACCURACY), filter.evaluate(null, Track.sample(Track.T0, 0.0, accuracy = null), 0))
        assertEquals(Decision.Reject(Rejection.LOW_ACCURACY), filter.evaluate(null, Track.sample(Track.T0, 0.0, accuracy = 40f), 0))
    }

    @Test
    fun `rejeita fix duplicado ou fora de ordem`() {
        assertEquals(Decision.Reject(Rejection.TOO_SOON), filter.evaluate(previous, Track.sample(Track.T0 + 300, 1.0), 0))
        assertEquals(Decision.Reject(Rejection.TOO_SOON), filter.evaluate(previous, Track.sample(Track.T0 - 1000, 1.0), 0))
    }

    @Test
    fun `aceita deslocamento de corrida rápida`() {
        // 6 m/s ≈ 2:47 /km
        assertEquals(Decision.Accept, filter.evaluate(previous, Track.sample(Track.T0 + 1000, 6.0), 0))
    }

    @Test
    fun `rejeita salto impossível`() {
        // 150 m em 1 s
        assertEquals(Decision.Reject(Rejection.IMPOSSIBLE_SPEED), filter.evaluate(previous, Track.sample(Track.T0 + 1000, 150.0), 0))
    }

    @Test
    fun `depois de saltos seguidos aceita como novo trecho em vez de travar`() {
        val jump = Track.sample(Track.T0 + 1000, 150.0)
        assertEquals(Decision.Reject(Rejection.IMPOSSIBLE_SPEED), filter.evaluate(previous, jump, consecutiveJumps = 4))
        assertEquals(Decision.AcceptAsNewSegment, filter.evaluate(previous, jump, consecutiveJumps = 5))
    }

    @Test
    fun `margem de precisão evita rejeitar ponto bom vizinho de um ponto deslocado`() {
        val impreciseAnchor = previous.copy(accuracyMeters = 20f)
        // 30 m em 1 s: acima de 12 m/s, mas dentro de 12 m + 20 m de precisão.
        assertEquals(Decision.Accept, filter.evaluate(impreciseAnchor, Track.sample(Track.T0 + 1000, 30.0), 0))
    }
}
