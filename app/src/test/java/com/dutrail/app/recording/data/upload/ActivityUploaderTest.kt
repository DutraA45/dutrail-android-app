package com.dutrail.app.recording.data.upload

import com.dutrail.app.activities.data.ActivitiesApi
import com.dutrail.app.auth.data.TokenRefresher
import com.dutrail.app.auth.domain.Session
import com.dutrail.app.core.network.HttpClients
import com.dutrail.app.recording.data.RecordingDao
import com.dutrail.app.recording.data.RecordingEntity
import com.dutrail.app.recording.data.RecordingStatus
import com.dutrail.app.recording.data.RoutePoint
import com.dutrail.app.recording.data.TimerEventEntity
import com.dutrail.app.recording.data.TrackPointEntity
import com.dutrail.app.recording.data.UploadError
import com.dutrail.app.recording.data.upload.ActivityUploader.Outcome
import com.dutrail.app.testing.InMemorySessionStore
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.create

class ActivityUploaderTest {

    private val server = MockWebServer().apply { start() }
    private val dir: File = Files.createTempDirectory("fit").toFile()
    private val fitBytes = byteArrayOf(14, 16, 0x54, 0x08, 1, 2, 3, 4)
    private val dao = FakeRecordingDao()
    private val store = InMemorySessionStore(Session("access", "refresh", "user-1"))

    private val uploader: ActivityUploader = run {
        val baseUrl = server.url("/api/")
        val base = HttpClients.baseClient(baseUrl, debug = false)
        val client = HttpClients.authenticatedClient(base, store, TokenRefresher(HttpClients.retrofit(baseUrl, base).create(), store))
        ActivityUploader(dao, HttpClients.retrofit(baseUrl, client).create(), store) { File(dir, it) }
    }

    private fun pendingRecording(owner: String = "user-1"): RecordingEntity {
        File(dir, "run.fit").writeBytes(fitBytes)
        return RecordingEntity(
            id = 7,
            ownerUserId = owner,
            startedAtMillis = 1,
            status = RecordingStatus.PENDING_UPLOAD,
            fitFileName = "run.fit",
        ).also { dao.recordings[it.id] = it }
    }

    @After
    fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    @Test
    fun `201 marca como enviada e guarda o id remoto`() = runBlocking {
        pendingRecording()
        server.enqueue(json(201, ACTIVITY_JSON))

        assertEquals(Outcome.UPLOADED, uploader.upload(7))

        val saved = dao.recordings.getValue(7)
        assertEquals(RecordingStatus.UPLOADED, saved.status)
        assertEquals("act-1", saved.remoteActivityId)
        assertNull(saved.fitFileName)
        assertFalse("o .fit local é apagado depois do envio confirmado", File(dir, "run.fit").exists())

        val request = server.takeRequest()
        assertEquals("/api/activities/import", request.url.encodedPath)
        assertTrue(request.headers["Content-Type"]!!.startsWith("multipart/form-data"))
        val body = request.body!!
        assertTrue(body.utf8().contains("""Content-Disposition: form-data; name="file"; filename="run.fit""""))
        assertTrue("o arquivo vai byte a byte", body.indexOf(okio.ByteString.of(*fitBytes)) >= 0)
    }

    @Test
    fun `409 é sucesso silencioso, sem id remoto`() = runBlocking {
        pendingRecording()
        server.enqueue(json(409, error(409)))

        assertEquals(Outcome.ALREADY_UPLOADED, uploader.upload(7))
        val saved = dao.recordings.getValue(7)
        assertEquals(RecordingStatus.UPLOADED, saved.status)
        assertNull(saved.remoteActivityId)
        assertNull(saved.uploadError)
        assertFalse(File(dir, "run.fit").exists())
    }

    @Test
    fun `arquivo recusado fica salvo como falha, sem retentar sozinho`() = runBlocking {
        pendingRecording()
        server.enqueue(json(400, error(400)))

        assertEquals(Outcome.REJECTED, uploader.upload(7))
        assertEquals(RecordingStatus.UPLOAD_FAILED, dao.recordings.getValue(7).status)
        assertEquals(UploadError.INVALID_FILE, dao.recordings.getValue(7).uploadError)
        assertTrue(File(dir, "run.fit").exists())
    }

    @Test
    fun `erro do servidor e falta de rede ficam pendentes para nova tentativa`() = runBlocking {
        pendingRecording()
        server.enqueue(json(503, error(503)))
        assertEquals(Outcome.RETRY, uploader.upload(7))
        assertEquals(UploadError.SERVER, dao.recordings.getValue(7).uploadError)

        server.close()
        assertEquals(Outcome.RETRY, uploader.upload(7))
        assertEquals(RecordingStatus.PENDING_UPLOAD, dao.recordings.getValue(7).status)
        assertEquals(UploadError.NETWORK, dao.recordings.getValue(7).uploadError)
    }

    @Test
    fun `413 vira falha de arquivo grande, com o arquivo guardado`() = runBlocking {
        pendingRecording()
        server.enqueue(json(413, error(413)))

        assertEquals(Outcome.REJECTED, uploader.upload(7))
        assertEquals(UploadError.FILE_TOO_LARGE, dao.recordings.getValue(7).uploadError)
        assertTrue(File(dir, "run.fit").exists())
    }

    @Test
    fun `arquivo acima de 10 MiB nem é enviado`() = runBlocking {
        pendingRecording()
        RandomAccessFile(File(dir, "run.fit"), "rw").use { it.setLength(ActivitiesApi.MAX_IMPORT_BYTES + 1) }

        assertEquals(Outcome.REJECTED, uploader.upload(7))
        assertEquals(UploadError.FILE_TOO_LARGE, dao.recordings.getValue(7).uploadError)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `gravação de outra conta não é enviada`() = runBlocking {
        pendingRecording(owner = "someone-else")
        assertEquals(Outcome.WAITING_FOR_OWNER, uploader.upload(7))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `gravação já enviada não é reenviada`() = runBlocking {
        dao.recordings[7] = pendingRecording().copy(status = RecordingStatus.UPLOADED)
        assertEquals(Outcome.NOTHING_TO_DO, uploader.upload(7))
        assertEquals(0, server.requestCount)
    }

    private fun json(code: Int, body: String) =
        MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body(body).build()

    private fun error(code: Int) = """{"statusCode":$code,"error":"x","message":"x","path":"/activities/import","timestamp":"x"}"""

    private companion object {
        const val ACTIVITY_JSON = """{"id":"act-1","userId":"user-1","name":"Corrida","sport":"running",
            "startedAt":"2026-10-21T10:00:00.000Z","elapsedTimeSeconds":1060,"movingTimeSeconds":1000,
            "distanceMeters":2500.0,"elevationGainMeters":100.0,"averageHeartRateBpm":null,"maxHeartRateBpm":null,
            "calories":null,"createdAt":"2026-10-21T10:20:00.000Z","updatedAt":"2026-10-21T10:20:00.000Z"}"""
    }
}

/** Só o que o uploader usa; o resto não é exercitado. */
private class FakeRecordingDao : RecordingDao {
    val recordings = mutableMapOf<Long, RecordingEntity>()

    override suspend fun recording(id: Long) = recordings[id]
    override suspend fun updateRecording(recording: RecordingEntity) {
        recordings[recording.id] = recording
    }
    override suspend fun deletePoints(recordingId: Long) = Unit
    override suspend fun deleteEvents(recordingId: Long) = Unit

    override suspend fun insertRecording(recording: RecordingEntity): Long = TODO()
    override suspend fun deleteRecording(id: Long) = TODO()
    override fun observeRecording(id: Long): Flow<RecordingEntity?> = TODO()
    override suspend fun unfinishedRecording(): RecordingEntity? = TODO()
    override suspend fun finishingIds(): List<Long> = TODO()
    override suspend fun pendingUploadIds(userId: String): List<Long> = TODO()
    override fun observeNotUploaded(userId: String): Flow<List<RecordingEntity>> = TODO()
    override fun observeUploadedCount(): Flow<Int> = TODO()
    override suspend fun insertPoint(point: TrackPointEntity) = TODO()
    override suspend fun insertEvent(event: TimerEventEntity) = TODO()
    override suspend fun points(recordingId: Long): List<TrackPointEntity> = TODO()
    override fun observeRoute(recordingId: Long): Flow<List<RoutePoint>> = TODO()
    override suspend fun lastPoint(recordingId: Long): TrackPointEntity? = TODO()
    override suspend fun events(recordingId: Long): List<TimerEventEntity> = TODO()
    override suspend fun setStatus(id: Long, status: RecordingStatus) = TODO()
}
