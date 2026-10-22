package com.dutrail.app.auth.data

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/** Rotas de /auth que não usam Bearer. Ficam no cliente sem Authenticator, para nunca entrarem em loop de refresh. */
interface AuthApi {
    @POST("auth/signup")
    suspend fun signup(@Body body: SignupRequest): AuthResponse

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshTokenRequest): TokenPairResponse

    @POST("auth/logout")
    suspend fun logout(@Body body: RefreshTokenRequest)
}

/** Rotas autenticadas da conta (Bearer + refresh automático). */
interface AccountApi {
    @GET("me")
    suspend fun me(): UserDto

    @POST("auth/logout-all")
    suspend fun logoutAll()
}

@Serializable
data class SignupRequest(val email: String, val password: String, val name: String? = null)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class RefreshTokenRequest(val refreshToken: String)

@Serializable
data class AuthResponse(val accessToken: String, val refreshToken: String, val user: UserDto)

@Serializable
data class TokenPairResponse(val accessToken: String, val refreshToken: String)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val name: String? = null,
    val avatarUrl: String? = null,
    val emailVerified: Boolean = false,
    val hasPassword: Boolean = true,
    val createdAt: String? = null,
)
