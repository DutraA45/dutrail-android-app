package com.dutrail.app.recording.data.fit

import com.dutrail.app.testing.SyntheticRun
import com.garmin.fit.ActivityMesg
import com.garmin.fit.Decode
import com.garmin.fit.Event
import com.garmin.fit.EventMesg
import com.garmin.fit.EventType
import com.garmin.fit.File
import com.garmin.fit.FileIdMesg
import com.garmin.fit.LapMesg
import com.garmin.fit.LapTrigger
import com.garmin.fit.Mesg
import com.garmin.fit.MesgListener
import com.garmin.fit.MesgNum
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.garmin.fit.Sport
import com.garmin.fit.SubSport
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FitActivityEncoderTest {

    private fun sampleRun() = SyntheticRun.input()

    private fun decode(bytes: ByteArray): List<Mesg> {
        val messages = mutableListOf<Mesg>()
        Decode().read(ByteArrayInputStream(bytes), MesgListener { messages += it })
        return messages
    }

    @Test
    fun `gera um arquivo FIT íntegro que o SDK oficial decodifica`() {
        val input = sampleRun()
        val bytes = FitActivityEncoder().encode(input)
        writeArtifact("sample-run.fit", bytes)

        assertTrue("CRC/cabeçalho inválido", Decode().checkFileIntegrity(ByteArrayInputStream(bytes)))
        val messages = decode(bytes)

        val fileId = FileIdMesg(messages.single { it.num == MesgNum.FILE_ID })
        assertEquals(File.ACTIVITY, fileId.type)
        assertEquals(1234567L, fileId.serialNumber)

        val records = messages.filter { it.num == MesgNum.RECORD }.map(::RecordMesg)
        assertEquals(input.points.size, records.size)
        val first = records.first()
        assertEquals(input.points.first().latitude, first.positionLat * (180.0 / 2_147_483_648.0), 1e-6)
        assertEquals(input.points.first().longitude, first.positionLong * (180.0 / 2_147_483_648.0), 1e-6)
        assertEquals(2.5f, first.speed, 0.01f)
        assertTrue("distância deve ser monotônica", records.zipWithNext().all { (a, b) -> b.distance >= a.distance })
        assertTrue("timestamps devem ser estritamente crescentes", records.zipWithNext().all { (a, b) -> b.timestamp.timestamp > a.timestamp.timestamp })

        val timerEvents = messages.filter { it.num == MesgNum.EVENT }.map(::EventMesg).filter { it.event == Event.TIMER }
        assertEquals(listOf(EventType.START, EventType.STOP, EventType.START, EventType.STOP_ALL), timerEvents.map { it.eventType })

        val laps = messages.filter { it.num == MesgNum.LAP }.map(::LapMesg)
        assertEquals(input.summary.laps.size, laps.size)
        assertEquals(listOf(LapTrigger.DISTANCE, LapTrigger.DISTANCE, LapTrigger.SESSION_END), laps.map { it.lapTrigger })
        assertEquals(1000f, laps[0].totalDistance, 0.5f)
        assertEquals(400f, laps[0].totalTimerTime, 1.5f)
        // O segundo km contém a pausa de 60 s: timer ~400 s, elapsed ~460 s.
        assertEquals(400f, laps[1].totalTimerTime, 1.5f)
        assertEquals(460f, laps[1].totalElapsedTime, 1.5f)

        val session = SessionMesg(messages.single { it.num == MesgNum.SESSION })
        assertEquals(Sport.RUNNING, session.sport)
        assertEquals(SubSport.GENERIC, session.subSport)
        assertEquals(input.summary.distanceMeters.toFloat(), session.totalDistance, 0.5f)
        assertEquals(1000f, session.totalTimerTime, 0.01f)
        assertEquals(1060f, session.totalElapsedTime, 0.01f)
        assertEquals(1000f, session.totalMovingTime, 0.01f)
        assertEquals(laps.size, session.numLaps)
        assertTrue("ganho de elevação ~100 m", session.totalAscent in 90..100)

        val activity = ActivityMesg(messages.single { it.num == MesgNum.ACTIVITY })
        assertEquals(1, activity.numSessions)
        assertEquals(activity.timestamp.timestamp - 3 * 3600, activity.localTimestamp)
    }

    @Test
    fun `mesmo input gera os mesmos bytes`() {
        val input = sampleRun()
        assertTrue(FitActivityEncoder().encode(input).contentEquals(FitActivityEncoder().encode(input)))
    }

    @Test
    fun `conversões de tempo e posição`() {
        // 1989-12-31T00:00:00Z é o zero do FIT.
        assertEquals(0L, FitActivityEncoder.fitTime(631_065_600_000L).timestamp)
        assertEquals(1_073_741_824, FitActivityEncoder.semicircles(90.0))
        assertEquals(Int.MAX_VALUE, FitActivityEncoder.semicircles(180.0))
    }

    private fun writeArtifact(name: String, bytes: ByteArray) {
        val dir = System.getProperty("dutrail.test.output") ?: return
        java.io.File(dir).apply { mkdirs() }.resolve(name).writeBytes(bytes)
    }
}
