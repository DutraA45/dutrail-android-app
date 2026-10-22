package com.dutrail.app.core.network

import android.util.Log
import com.dutrail.app.auth.data.AuthHeaderInterceptor
import com.dutrail.app.auth.data.SessionStore
import com.dutrail.app.auth.data.TokenAuthenticator
import com.dutrail.app.auth.data.TokenRefresher
import java.util.concurrent.TimeUnit
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Montagem dos clientes HTTP, fora do Hilt para ser reutilizada nos testes (MockWebServer e smoke
 * test contra o backend real).
 *
 * - [baseClient]: sem Bearer e sem Authenticator. Usado por signup/login/refresh/logout.
 * - [authenticatedClient]: Bearer + refresh automático em 401. Usado por /me, /activities e logout-all.
 */
object HttpClients {

    fun baseClient(baseUrl: HttpUrl, debug: Boolean): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // Upload de .fit (até 10 MiB) em rede móvel ruim.
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(ClientTypeInterceptor(baseUrl, logClientErrors = debug))
        .apply {
            // BASIC: método, caminho, status e tempo. Nunca corpo ou headers, que trazem tokens, e
            // sem o host, para a URL do ambiente não ir para o logcat.
            if (debug) {
                val origin = baseUrl.resolve("/")!!.toString().removeSuffix("/")
                addInterceptor(
                    HttpLoggingInterceptor { Log.d("OkHttp", it.replace(origin, "")) }
                        .setLevel(HttpLoggingInterceptor.Level.BASIC),
                )
            }
        }
        .build()

    fun authenticatedClient(base: OkHttpClient, sessionStore: SessionStore, refresher: TokenRefresher): OkHttpClient =
        base.newBuilder()
            // Dispatcher próprio: o Authenticator bloqueia a chamada enquanto espera o /auth/refresh.
            // Com o dispatcher compartilhado (limite de 5 por host), 5 chamadas com 401 ocupariam
            // todas as vagas e o refresh nunca sairia da fila: deadlock.
            .dispatcher(Dispatcher())
            .addInterceptor(AuthHeaderInterceptor(sessionStore))
            .authenticator(TokenAuthenticator(refresher))
            .build()

    fun retrofit(baseUrl: HttpUrl, client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType()))
        .build()
}
