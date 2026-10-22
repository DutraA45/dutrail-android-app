package com.dutrail.app.auth.data

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

private const val AUTHORIZATION = "Authorization"
private const val BEARER = "Bearer "

/** Adiciona `Authorization: Bearer <access>` quando há sessão. */
class AuthHeaderInterceptor(private val sessionStore: SessionStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(AUTHORIZATION) != null) return chain.proceed(request)
        // As threads do OkHttp já são de I/O; o DataStore mantém o valor em memória após a 1ª leitura.
        val token = runBlocking { sessionStore.current() }?.accessToken ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().header(AUTHORIZATION, BEARER + token).build())
    }
}

/**
 * Chamado pelo OkHttp quando uma resposta é 401: renova a sessão via [TokenRefresher] e repete a
 * requisição uma única vez. Retornar null entrega o 401 a quem chamou; lançar IOException
 * (refresh indisponível) faz a chamada falhar como erro de rede.
 */
class TokenAuthenticator(private val refresher: TokenRefresher) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        val failedToken = response.request.header(AUTHORIZATION)?.removePrefix(BEARER) ?: return null
        // Já repetimos com um token renovado e ainda veio 401: não insiste.
        if (response.priorResponse != null) return null

        val newToken = runBlocking { refresher.refresh(failedToken) } ?: return null
        return response.request.newBuilder().header(AUTHORIZATION, BEARER + newToken).build()
    }
}
