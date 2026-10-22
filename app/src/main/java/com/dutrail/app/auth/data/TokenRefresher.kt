package com.dutrail.app.auth.data

import android.util.Log
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

/**
 * Renova a sessão quando uma requisição recebe 401.
 *
 * Cada renovação troca o par de tokens. Por isso:
 * - os refreshes são serializados por um [Mutex]: com vários 401 simultâneos só o primeiro chama a
 *   API, e os demais reutilizam o token que ele obteve;
 * - o par novo é gravado antes de a requisição original ser repetida.
 */
class TokenRefresher(
    private val authApi: AuthApi,
    private val sessionStore: SessionStore,
) {
    private val mutex = Mutex()

    /**
     * @param failedAccessToken o access token que levou 401.
     * @return access token para repetir a requisição, ou null se a sessão acabou (é preciso logar de novo).
     * @throws IOException se não deu para renovar agora (rede, 429, 5xx). A sessão é mantida e a
     *   renovação é tentada de novo depois, no próximo 401.
     */
    suspend fun refresh(failedAccessToken: String): String? = mutex.withLock {
        val current = sessionStore.current() ?: return null
        if (current.accessToken != failedAccessToken) {
            // Outra requisição já renovou enquanto esta esperava o lock.
            return current.accessToken
        }

        val pair = try {
            authApi.refresh(RefreshTokenRequest(current.refreshToken))
        } catch (e: HttpException) {
            if (e.code() == 400 || e.code() == 401) {
                // Renovação recusada: a sessão não vale mais e é preciso logar de novo.
                Log.w(TAG, "Refresh recusado (HTTP ${e.code()}); encerrando a sessão local")
                sessionStore.clear()
                return null
            }
            throw IOException("Refresh indisponível (HTTP ${e.code()})", e)
        }

        val renewed = current.copy(accessToken = pair.accessToken, refreshToken = pair.refreshToken)
        sessionStore.save(renewed)
        renewed.accessToken
    }

    private companion object {
        const val TAG = "TokenRefresher"
    }
}
