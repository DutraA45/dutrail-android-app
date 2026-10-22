package com.dutrail.app.auth.domain

import java.text.Normalizer

enum class EmailError { EMPTY, INVALID }
enum class PasswordError { TOO_SHORT, TOO_LONG }
enum class NameError { TOO_LONG }

/**
 * Validação local com as mesmas regras do `SignupDto`. A API não devolve um código de erro estável
 * no 400, então validar aqui é o que permite mostrar mensagens específicas.
 */
object CredentialsValidator {
    const val PASSWORD_MIN_LENGTH = 8
    const val PASSWORD_MAX_LENGTH = 128
    const val NAME_MAX_LENGTH = 100

    private val emailPattern = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

    fun validateEmail(email: String): EmailError? {
        val trimmed = email.trim()
        return when {
            trimmed.isEmpty() -> EmailError.EMPTY
            !emailPattern.matches(trimmed) -> EmailError.INVALID
            else -> null
        }
    }

    /** O servidor limita a 128 caracteres depois da normalização NFKC, que pode expandir a senha. */
    fun validatePassword(password: String): PasswordError? {
        val normalized = Normalizer.normalize(password, Normalizer.Form.NFKC)
        return when {
            password.codePointLength() < PASSWORD_MIN_LENGTH -> PasswordError.TOO_SHORT
            normalized.codePointLength() > PASSWORD_MAX_LENGTH -> PasswordError.TOO_LONG
            else -> null
        }
    }

    fun validateName(name: String): NameError? =
        if (name.trim().codePointLength() > NAME_MAX_LENGTH) NameError.TOO_LONG else null

    private fun String.codePointLength() = codePointCount(0, length)
}
