package com.dutrail.app.auth.data

import com.dutrail.app.activities.data.ActivitiesApi
import com.dutrail.app.auth.domain.Session
import com.dutrail.app.core.network.ApiError
import com.dutrail.app.core.network.ClientTypeInterceptor
import com.dutrail.app.core.network.HttpClients
import com.dutrail.app.core.network.apiCall
import com.dutrail.app.testing.InMemorySessionStore
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.create

class TokenAuthenticatorTest {

    private val server = MockWebServer()
    private val oldSession = Session("old-access", "old-refresh", "user-1")
    private val store = InMemorySessionStore(oldSession)

    private val refreshCalls = AtomicInteger()
    private val unauthorizedResponses = AtomicInteger()
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())

    /** Sessão gravada no momento em que a requisição repetida chegou ao servidor. */
    private val sessionSeenOnRetry = Collections.synchronizedList(mutableListOf<Session?>())

    @Volatile private var refreshResponse: () -> MockResponse = {
        json(200, """{"accessToken":"new-access","refreshToken":"new-refresh"}""")
    }

    private lateinit var api: ActivitiesApi

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when (request.url.encodedPath) {
                    "/api/auth/refresh" -> {
                        refreshCalls.incrementAndGet()
                        // Deixa a janela de corrida aberta: as outras chamadas recebem 401 enquanto isto roda.
                        Thread.sleep(300)
                        refreshResponse()
                    }
                    "/api/activities" -> if (request.headers["Authorization"] == "Bearer new-access") {
                        sessionSeenOnRetry += store.peek()
                        json(200, """{"items":[],"nextCursor":null}""")
                    } else {
                        unauthorizedResponses.incrementAndGet()
                        json(401, """{"statusCode":401,"error":"Unauthorized","message":"expired","path":"/activities","timestamp":"x"}""")
                    }
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
        val baseUrl = server.url("/api/")
        val base = HttpClients.baseClient(baseUrl, debug = false)
        val refresher = TokenRefresher(HttpClients.retrofit(baseUrl, base).create(), store)
        api = HttpClients.retrofit(baseUrl, HttpClients.authenticatedClient(base, store, refresher)).create()
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `401 renova, grava o par novo antes de repetir e repete com o token novo`() = runBlocking {
        apiCall { api.list(20, null) }

        assertEquals(1, refreshCalls.get())
        assertEquals(Session("new-access", "new-refresh", "user-1"), store.peek())
        // Quando a repetição chegou ao servidor, o par novo já estava salvo.
        assertEquals(listOf(Session("new-access", "new-refresh", "user-1")), sessionSeenOnRetry.toList())
    }

    @Test
    fun `vários 401 simultâneos geram um único refresh`() = runBlocking {
        withTimeout(20_000) {
            (1..8).map { async(Dispatchers.IO) { apiCall { api.list(20, null) } } }.awaitAll()
        }

        assertEquals(1, refreshCalls.get())
        assertTrue("o teste precisa de 401 concorrentes de fato", unauthorizedResponses.get() >= 2)
        assertEquals("new-refresh", store.peek()?.refreshToken)
    }

    @Test
    fun `refresh recusado limpa a sessão e não entra em loop`() = runBlocking {
        refreshResponse = { json(401, """{"statusCode":401,"error":"Unauthorized","message":"revoked","path":"/auth/refresh","timestamp":"x"}""") }

        try {
            apiCall { api.list(20, null) }
            fail("deveria propagar o 401")
        } catch (e: ApiError.Http) {
            assertEquals(401, e.code)
        }
        assertEquals(1, refreshCalls.get())
        assertEquals(1, unauthorizedResponses.get())
        assertNull(store.peek())
    }

    @Test
    fun `refresh indisponível mantém a sessão e falha como erro de rede`() = runBlocking {
        refreshResponse = { json(503, """{"statusCode":503,"error":"Service Unavailable","message":"down","path":"/auth/refresh","timestamp":"x"}""") }

        try {
            apiCall { api.list(20, null) }
            fail("deveria falhar")
        } catch (e: ApiError.Network) {
            // esperado
        }
        assertEquals(oldSession, store.peek())
    }

    @Test
    fun `X-Client-Type só nas rotas de auth, refresh token no corpo e nenhum cookie`() = runBlocking {
        apiCall { api.list(20, null) }

        val refresh = requests.single { it.url.encodedPath == "/api/auth/refresh" }
        assertEquals(ClientTypeInterceptor.VALUE, refresh.headers[ClientTypeInterceptor.HEADER])
        assertEquals("""{"refreshToken":"old-refresh"}""", refresh.body?.utf8())
        assertNull(refresh.headers["Authorization"])
        assertNull(refresh.headers["Cookie"])

        requests.filter { it.url.encodedPath == "/api/activities" }.forEach {
            assertNull(it.headers[ClientTypeInterceptor.HEADER])
            assertNull(it.headers["Cookie"])
            assertTrue(it.headers["Authorization"]!!.startsWith("Bearer "))
            // Parâmetro nulo não vai na query (a API recusa parâmetros desconhecidos).
            assertNull(it.url.queryParameter("cursor"))
        }
    }

    private fun json(code: Int, body: String) = MockResponse.Builder()
        .code(code)
        .setHeader("Content-Type", "application/json")
        .body(body)
        .build()
}

