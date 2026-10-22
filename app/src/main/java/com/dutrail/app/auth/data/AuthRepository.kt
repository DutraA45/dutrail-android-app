package com.dutrail.app.auth.data

import android.util.Log
import com.dutrail.app.auth.domain.Session
import com.dutrail.app.auth.domain.User
import com.dutrail.app.core.network.ApiError
import com.dutrail.app.core.network.apiCall
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class AuthRepository @Inject constructor(
    private val authApi: AuthApi,
    private val accountApi: AccountApi,
    private val sessionStore: SessionStore,
) {
    val isLoggedIn: Flow<Boolean> = sessionStore.session.map { it != null }.distinctUntilChanged()

    /** @throws ApiError 401 = credenciais inválidas, 429 = muitas tentativas. */
    suspend fun login(email: String, password: String) {
        val response = apiCall { authApi.login(LoginRequest(email.trim(), password)) }
        sessionStore.save(response.toSession())
    }

    /** @throws ApiError 409 = email já cadastrado, 400 = dados recusados (inclui senha vazada). */
    suspend fun signup(email: String, password: String, name: String?) {
        val request = SignupRequest(email.trim(), password, name?.trim()?.takeIf { it.isNotEmpty() })
        val response = apiCall { authApi.signup(request) }
        sessionStore.save(response.toSession())
    }

    suspend fun currentUser(): User {
        val dto = try {
            apiCall { accountApi.me() }
        } catch (e: ApiError.Http) {
            // 404: a conta do token não existe mais.
            if (e.code == 404) sessionStore.clear()
            throw e
        }
        return User(dto.id, dto.email, dto.name)
    }

    /** Revoga o refresh token no servidor se possível; a sessão local é apagada de qualquer forma. */
    suspend fun logout() {
        val session = sessionStore.current() ?: return
        try {
            apiCall { authApi.logout(RefreshTokenRequest(session.refreshToken)) }
        } catch (e: ApiError) {
            Log.i(TAG, "Logout no servidor falhou; limpando só a sessão local", e)
        } finally {
            withContext(NonCancellable) { sessionStore.clear() }
        }
    }

    /**
     * Encerra todas as sessões da conta.
     * @return false se o servidor não confirmou (ex.: sem rede). A sessão local é apagada mesmo assim.
     */
    suspend fun logoutAll(): Boolean = try {
        apiCall { accountApi.logoutAll() }
        true
    } catch (e: ApiError) {
        Log.i(TAG, "logout-all falhou", e)
        false
    } finally {
        withContext(NonCancellable) { sessionStore.clear() }
    }

    private fun AuthResponse.toSession() = Session(accessToken, refreshToken, user.id)

    private companion object {
        const val TAG = "AuthRepository"
    }
}
