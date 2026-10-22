package com.dutrail.app.auth.domain

/** Par de tokens da sessão atual, mais o dono (usado para não enviar atividades de outra conta). */
data class Session(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
) {
    override fun toString() = "Session(userId=$userId, tokens=<redacted>)"
}

data class User(
    val id: String,
    val email: String,
    val name: String?,
)
