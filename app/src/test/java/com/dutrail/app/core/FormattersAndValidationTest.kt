package com.dutrail.app.core

import com.dutrail.app.auth.domain.CredentialsValidator
import com.dutrail.app.auth.domain.EmailError
import com.dutrail.app.auth.domain.PasswordError
import com.dutrail.app.core.common.Formatters
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormattersTest {
    private val ptBr = Locale.forLanguageTag("pt-BR")

    @Test
    fun `distância em km com vírgula`() {
        assertEquals("10,01", Formatters.distanceKm(10_012.4, ptBr))
        assertEquals("0,00", Formatters.distanceKm(0.0, ptBr))
    }

    @Test
    fun `duração com e sem horas`() {
        assertEquals("00:59", Formatters.duration(59))
        assertEquals("52:05", Formatters.duration(3125))
        assertEquals("1:02:05", Formatters.duration(3725))
    }

    @Test
    fun `pace arredonda para o segundo sem gerar 60`() {
        assertEquals("5:12", Formatters.pace(312.0))
        assertEquals("6:00", Formatters.pace(359.6))
        assertEquals(Formatters.NO_PACE, Formatters.pace(null))
        assertEquals(Formatters.NO_PACE, Formatters.pace(4_000.0))
    }
}

class CredentialsValidatorTest {
    @Test
    fun `email`() {
        assertEquals(EmailError.EMPTY, CredentialsValidator.validateEmail("  "))
        assertEquals(EmailError.INVALID, CredentialsValidator.validateEmail("ana@"))
        assertNull(CredentialsValidator.validateEmail(" ana@example.com "))
    }

    @Test
    fun `senha entre 8 e 128 caracteres`() {
        assertEquals(PasswordError.TOO_SHORT, CredentialsValidator.validatePassword("1234567"))
        assertNull(CredentialsValidator.validatePassword("12345678"))
        assertNull(CredentialsValidator.validatePassword("a".repeat(128)))
        assertEquals(PasswordError.TOO_LONG, CredentialsValidator.validatePassword("a".repeat(129)))
    }

    @Test
    fun `limite superior vale depois do NFKC`() {
        // "ﬃ" (ligadura, 1 caractere) vira "ffi" (3) no NFKC.
        val password = "ﬃ".repeat(50)
        assertEquals(PasswordError.TOO_LONG, CredentialsValidator.validatePassword(password))
    }
}
