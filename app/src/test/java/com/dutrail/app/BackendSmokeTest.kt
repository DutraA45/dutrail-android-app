package com.dutrail.app

import com.dutrail.app.activities.data.ActivitiesApi
import com.dutrail.app.activities.data.ActivityDto
import com.dutrail.app.auth.data.AuthApi
import com.dutrail.app.auth.data.AuthResponse
import com.dutrail.app.auth.data.LoginRequest
import com.dutrail.app.auth.data.RefreshTokenRequest
import com.dutrail.app.auth.data.SignupRequest
import com.dutrail.app.auth.data.TokenPairResponse
import com.dutrail.app.auth.data.TokenRefresher
import com.dutrail.app.auth.data.UserDto
import com.dutrail.app.auth.domain.Session
import com.dutrail.app.core.network.ApiJson
import com.dutrail.app.core.network.HttpClients
import com.dutrail.app.core.network.apiCall
import com.dutrail.app.recording.data.fit.FitActivityEncoder
import com.dutrail.app.testing.InMemorySessionStore
import com.dutrail.app.testing.SyntheticRun
import com.garmin.fit.Decode
import com.garmin.fit.MesgListener
import com.garmin.fit.MesgNum
import com.garmin.fit.SessionMesg
import java.io.ByteArrayInputStream
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.create

/**
 * Valida o contrato contra a API do ambiente de desenvolvimento (não é teste de unidade). Só executa com a URL:
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*BackendSmokeTest*' -PdutrailSmokeUrl=<URL base da API, terminada em /api/>
 *
 * Cria um usuário descartável (smoke+<uuid>@example.com, senha aleatória) e atividades de teste.
 * A saída mostra o que foi enviado ao lado do que a API devolveu; senha e tokens nunca são impressos.
 * Falhas no fluxo derrubam o teste; diferenças de campos ou de formato são listadas no fim.
 */
class BackendSmokeTest {

    private val baseUrl: HttpUrl? = (System.getProperty("dutrail.smoke.url") ?: System.getenv("DUTRAIL_SMOKE_URL"))
        ?.let { if (it.endsWith("/")) it else "$it/" }?.toHttpUrl()

    private val jsonType = "application/json".toMediaType()
    private val differences = mutableListOf<String>()

    @Before
    fun requireBackend() = assumeTrue("Backend não configurado (-PdutrailSmokeUrl)", baseUrl != null)

    @Test
    fun `fluxo completo de auth, refresh e import`(): Unit = runBlocking {
        val url = baseUrl!!
        val base = HttpClients.baseClient(url, debug = false)
        val store = InMemorySessionStore()
        val authApi = HttpClients.retrofit(url, base).create<AuthApi>()
        val authedClient = HttpClients.authenticatedClient(base, store, TokenRefresher(authApi, store))
        val authed = HttpClients.retrofit(url, authedClient)
        val activities = authed.create<ActivitiesApi>()
        val plain = OkHttpClient()

        step("GET health") {
            val (code, body) = plain.send(get(url, "health"))
            assertEquals(200, code)
            "$code $body"
        }

        step("POST auth/login sem X-Client-Type") {
            val (code, body) = plain.send(post(url, "auth/login", ApiJson.encodeToString(LoginRequest("nobody@example.com", "whatever123"))))
            assertEquals("corpo: $body", 400, code)
            checkErrorFormat(code, body)
            "$code $body"
        }

        val email = "smoke+${UUID.randomUUID()}@example.com"
        val password = "Dt!" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also(SecureRandom()::nextBytes))
        println("   usuário de teste: $email (senha aleatória, não impressa)")

        val signup = stepWithResult("POST auth/signup (mobile)") {
            base.newCall(post(url, "auth/signup", ApiJson.encodeToString(SignupRequest(email, password, "Smoke Test")))).execute().use { response ->
                val body = response.body.string()
                assertEquals("corpo: $body", 201, response.code)
                assertTrue("signup mobile não deveria mandar Set-Cookie", response.headers("Set-Cookie").isEmpty())
                val auth = ApiJson.decodeFromString<AuthResponse>(body)
                assertTrue(auth.refreshToken.isNotBlank())
                auth to "${response.code}, sem Set-Cookie, ${withoutTokens(body)}"
            }
        }

        step("POST auth/signup repetido") { expectHttp(409) { authApi.signup(SignupRequest(email, password)) } }
        step("POST auth/login com senha errada") { expectHttp(401) { authApi.login(LoginRequest(email, "$password-errada")) } }

        val login = stepWithResult("POST auth/login") {
            val (code, body) = base.send(post(url, "auth/login", ApiJson.encodeToString(LoginRequest(email, password))))
            assertEquals("corpo: $body", 200, code)
            ApiJson.decodeFromString<AuthResponse>(body) to "$code ${withoutTokens(body)}"
        }
        store.save(Session(login.accessToken, login.refreshToken, login.user.id))
        assertEquals(signup.user.id, login.user.id)

        step("GET me") {
            val (code, body) = authedClient.send(get(url, "me"))
            assertEquals("corpo: $body", 200, code)
            assertEquals(email, ApiJson.decodeFromString<UserDto>(body).email)
            "$code $body"
        }

        step("POST auth/refresh") {
            val current = store.current()!!
            val (code, body) = base.send(post(url, "auth/refresh", ApiJson.encodeToString(RefreshTokenRequest(current.refreshToken))))
            assertEquals("corpo: $body", 200, code)
            val pair = ApiJson.decodeFromString<TokenPairResponse>(body)
            assertTrue("o refresh token deveria mudar a cada refresh", pair.refreshToken != current.refreshToken)
            store.save(current.copy(accessToken = pair.accessToken, refreshToken = pair.refreshToken))
            "$code ${withoutTokens(body)}, refresh token rotacionado"
        }

        step("GET activities com access token inválido (Authenticator)") {
            val before = store.current()!!
            store.save(before.copy(accessToken = "invalid"))
            val page = apiCall { activities.list(20, null) }
            val after = store.current()!!
            assertTrue("o Authenticator deveria ter renovado o access token", after.accessToken != "invalid")
            assertTrue("o refresh token deveria ter rotacionado", after.refreshToken != before.refreshToken)
            "401 → refresh → 200, itens=${page.items.size}, nextCursor=${page.nextCursor}"
        }

        val t0 = (System.currentTimeMillis() - 2 * 3_600_000L) / 1000 * 1000
        val input = SyntheticRun.input(t0 = t0, serialNumber = 42L)
        val fit = FitActivityEncoder().encode(input)
        writeArtifact("smoke-run.fit", fit)

        val (created, createdJson) = stepWithResult("POST activities/import") {
            val request = Request.Builder().url(url.resolve("activities/import")!!)
                .post(MultipartBody.Builder().setType(MultipartBody.FORM).addPart(fitPart(fit)).build()).build()
            val (code, body) = authedClient.send(request)
            assertEquals("corpo: $body", 201, code)
            (ApiJson.decodeFromString<ActivityDto>(body) to body) to "$code $body"
        }
        compareWithFit(fitSession(fit), input.summary.ascentMeters, created)

        step("POST activities/import dos mesmos bytes") { expectHttp(409) { activities.import(fitPart(fit)) } }

        // Experimento (não derruba o teste): mesma corrida, só o serial do file_id muda.
        step("POST activities/import, mesma corrida com outro serial no file_id (informativo)") {
            val other = FitActivityEncoder().encode(SyntheticRun.input(t0 = t0, serialNumber = 43L))
            try {
                val activity = activities.import(fitPart(other))
                "201: não é duplicado quando os bytes mudam (nova atividade ${activity.id})"
            } catch (e: HttpException) {
                "${e.code()} ${e.rawBody()}"
            }
        }

        step("GET activities/:id") {
            val (code, body) = authedClient.send(get(url, "activities/${created.id}"))
            assertEquals("corpo: $body", 200, code)
            val same = ApiJson.parseToJsonElement(body) == ApiJson.parseToJsonElement(createdJson)
            if (!same) differences += "GET activities/:id difere da resposta do import: $body"
            "$code, ${if (same) "JSON idêntico ao da resposta do import" else "DIFERENTE da resposta do import: $body"}"
        }
        step("GET activities") {
            val page = apiCall { activities.list(20, null) }
            assertTrue(page.items.any { it.id == created.id })
            "200, itens=${page.items.size}, nextCursor=${page.nextCursor}"
        }

        step("POST auth/logout") {
            val revoked = store.current()!!.refreshToken
            val (code, body) = base.send(post(url, "auth/logout", ApiJson.encodeToString(RefreshTokenRequest(revoked))))
            assertEquals("corpo: $body", 204, code)
            "$code; refresh com o token revogado → " + expectHttp(401) { authApi.refresh(RefreshTokenRequest(revoked)) }
        }

        step("POST auth/logout-all") {
            val again = apiCall { authApi.login(LoginRequest(email, password)) }
            store.save(Session(again.accessToken, again.refreshToken, again.user.id))
            val request = Request.Builder().url(url.resolve("auth/logout-all")!!).post(ByteArray(0).toRequestBody()).build()
            val (code, body) = authedClient.send(request)
            assertEquals("corpo: $body", 204, code)
            "$code; refresh da sessão encerrada → " + expectHttp(401) { authApi.refresh(RefreshTokenRequest(again.refreshToken)) }
        }

        println()
        if (differences.isEmpty()) {
            println("Nenhuma diferença em relação ao contrato ou ao .fit enviado.")
        } else {
            println("DIFERENÇAS (${differences.size}):")
            differences.forEach { println(" - $it") }
        }
    }

    /** Campos do resumo do .fit (como o SDK da Garmin decodifica o arquivo enviado) ao lado da resposta. */
    private fun compareWithFit(session: SessionMesg, trackAscent: Double, activity: ActivityDto) {
        val start = session.startTime.date.toInstant()
        val rows = listOf(
            Triple("sport", "sport=${session.sport} sub_sport=${session.subSport}", activity.sport),
            Triple("startedAt", "start_time=$start", activity.startedAt),
            Triple("distanceMeters", "total_distance=${session.totalDistance}", activity.distanceMeters),
            Triple("elapsedTimeSeconds", "total_elapsed_time=${session.totalElapsedTime}", activity.elapsedTimeSeconds),
            Triple("movingTimeSeconds", "total_timer_time=${session.totalTimerTime} total_moving_time=${session.totalMovingTime}", activity.movingTimeSeconds),
            Triple("elevationGainMeters", "total_ascent=${session.totalAscent} (trilha: +%.1f m)".format(trackAscent), activity.elevationGainMeters),
            Triple("name", "(o .fit não tem campo de nome)", activity.name),
            Triple("averageHeartRateBpm", "(sem frequência cardíaca)", activity.averageHeartRateBpm),
            Triple("maxHeartRateBpm", "(sem frequência cardíaca)", activity.maxHeartRateBpm),
            Triple("calories", "(sem total_calories)", activity.calories),
        )
        println("   %-22s %-58s %s".format("campo da API", "enviado no .fit (session)", "recebido"))
        rows.forEach { (field, sent, received) -> println("   %-22s %-58s %s".format(field, sent, received)) }

        fun diff(condition: Boolean, text: String) { if (condition) differences += text }
        diff(activity.sport != "running", "sport: esperado running, veio ${activity.sport}")
        diff(Instant.parse(activity.startedAt) != start, "startedAt: esperado $start (start_time), veio ${activity.startedAt}")
        diff(activity.distanceMeters?.let { abs(it - session.totalDistance) > 1.0 } != false, "distanceMeters: esperado ~${session.totalDistance}, veio ${activity.distanceMeters}")
        diff(activity.elapsedTimeSeconds != session.totalElapsedTime.roundToLong(), "elapsedTimeSeconds: esperado ${session.totalElapsedTime}, veio ${activity.elapsedTimeSeconds}")
        diff(activity.movingTimeSeconds != session.totalTimerTime.roundToLong(), "movingTimeSeconds: esperado ${session.totalTimerTime}, veio ${activity.movingTimeSeconds}")
        diff(activity.elevationGainMeters?.let { abs(it - session.totalAscent) > 1.0 } != false, "elevationGainMeters: esperado ~${session.totalAscent}, veio ${activity.elevationGainMeters}")
        diff(activity.name.isBlank(), "name veio vazio")
    }

    private fun fitSession(bytes: ByteArray): SessionMesg {
        var session: SessionMesg? = null
        Decode().read(ByteArrayInputStream(bytes), MesgListener { if (it.num == MesgNum.SESSION) session = SessionMesg(it) })
        return session!!
    }

    private fun fitPart(bytes: ByteArray) = MultipartBody.Part.createFormData(
        ActivitiesApi.IMPORT_FILE_FIELD,
        "smoke.fit",
        bytes.toRequestBody("application/octet-stream".toMediaType()),
    )

    /** Confere o formato de erro `{ statusCode, error, message, path, timestamp }`. */
    private fun checkErrorFormat(code: Int, raw: String) {
        val obj = runCatching { ApiJson.parseToJsonElement(raw).jsonObject }.getOrNull()
        if (obj == null) {
            differences += "HTTP $code sem corpo JSON: $raw"
            return
        }
        val missing = listOf("statusCode", "error", "message", "path", "timestamp").filter { it !in obj }
        if (missing.isNotEmpty()) differences += "HTTP $code sem os campos $missing: $raw"
        if (obj["statusCode"]?.jsonPrimitive?.intOrNull != code) differences += "HTTP $code com outro statusCode no corpo: $raw"
    }

    private suspend fun <T> stepWithResult(name: String, block: suspend () -> Pair<T, String>): T {
        val (result, detail) = block()
        println("✔ $name → $detail")
        return result
    }

    private suspend fun step(name: String, block: suspend () -> String) = stepWithResult(name) { Unit to block() }

    private suspend fun expectHttp(code: Int, block: suspend () -> Unit): String {
        val error = try {
            block()
            null
        } catch (e: HttpException) {
            e
        }
        assertNotNull("esperava HTTP $code, veio sucesso", error)
        val raw = error!!.rawBody()
        assertEquals("corpo: $raw", code, error.code())
        checkErrorFormat(code, raw)
        return "$code $raw"
    }

    private fun HttpException.rawBody() = response()?.errorBody()?.string().orEmpty()

    /** Corpo cru da resposta de auth com os valores dos tokens trocados, para poder imprimir. */
    private fun withoutTokens(json: String) = JsonObject(
        ApiJson.parseToJsonElement(json).jsonObject.mapValues { (key, value) ->
            if (key == "accessToken" || key == "refreshToken") JsonPrimitive("<omitido>") else value
        },
    ).toString()

    private fun get(url: HttpUrl, path: String) = Request.Builder().url(url.resolve(path)!!).build()

    private fun post(url: HttpUrl, path: String, json: String) =
        Request.Builder().url(url.resolve(path)!!).post(json.toRequestBody(jsonType)).build()

    private fun OkHttpClient.send(request: Request): Pair<Int, String> =
        newCall(request).execute().use { it.code to it.body.string() }

    private fun writeArtifact(name: String, bytes: ByteArray) {
        val dir = System.getProperty("dutrail.test.output") ?: return
        java.io.File(dir).apply { mkdirs() }.resolve(name).writeBytes(bytes)
    }
}
