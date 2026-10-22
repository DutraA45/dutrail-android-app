package com.dutrail.app.auth.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dutrail.app.auth.data.AuthRepository
import com.dutrail.app.auth.domain.CredentialsValidator
import com.dutrail.app.auth.domain.EmailError
import com.dutrail.app.auth.domain.NameError
import com.dutrail.app.auth.domain.PasswordError
import com.dutrail.app.core.network.ApiError
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthFailure { INVALID_CREDENTIALS, EMAIL_TAKEN, SIGNUP_REJECTED, TOO_MANY_ATTEMPTS, NETWORK, UNKNOWN }

data class AuthFormState(
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val nameError: NameError? = null,
    val emailError: EmailError? = null,
    val passwordError: PasswordError? = null,
    val passwordMissing: Boolean = false,
    val loading: Boolean = false,
    val failure: AuthFailure? = null,
    /** Com [AuthFailure.TOO_MANY_ATTEMPTS]: segundos até poder tentar de novo, se a API informou. */
    val retryAfterSeconds: Long? = null,
)

/**
 * Login e cadastro compartilham o formulário. No sucesso não há navegação explícita: a sessão
 * gravada faz a raiz do app trocar para a área logada.
 */
abstract class AuthFormViewModel : ViewModel() {
    protected val _state = MutableStateFlow(AuthFormState())
    val state = _state.asStateFlow()

    /** Fim da espera pedida por um 429 (`X-RateLimit-Reset`): até lá o formulário não chama a API. */
    private var retryAt: TimeMark? = null

    fun onNameChange(value: String) = _state.update { it.copy(name = value, nameError = null, failure = null) }
    fun onEmailChange(value: String) = _state.update { it.copy(email = value, emailError = null, failure = null) }
    fun onPasswordChange(value: String) =
        _state.update { it.copy(password = value, passwordError = null, passwordMissing = false, failure = null) }

    protected fun submit(validate: (AuthFormState) -> AuthFormState, action: suspend (AuthFormState) -> Unit, mapHttp: (Int) -> AuthFailure) {
        val current = _state.value
        if (current.loading) return
        val validated = validate(current)
        if (validated.nameError != null || validated.emailError != null || validated.passwordError != null || validated.passwordMissing) {
            _state.value = validated
            return
        }
        retryAt?.takeIf { it.hasNotPassedNow() }?.let {
            _state.value = validated.copy(failure = AuthFailure.TOO_MANY_ATTEMPTS, retryAfterSeconds = (-it.elapsedNow()).inWholeSeconds + 1)
            return
        }
        _state.value = validated.copy(loading = true, failure = null, retryAfterSeconds = null)
        viewModelScope.launch {
            try {
                action(validated)
            } catch (e: ApiError) {
                val failure = when (e) {
                    is ApiError.Network -> AuthFailure.NETWORK
                    is ApiError.Http -> if (e.code == 429) AuthFailure.TOO_MANY_ATTEMPTS else mapHttp(e.code)
                }
                val waitSeconds = (e as? ApiError.Http)?.takeIf { it.code == 429 }?.retryAfterSeconds
                retryAt = waitSeconds?.let { TimeSource.Monotonic.markNow() + it.seconds }
                _state.update { it.copy(loading = false, failure = failure, retryAfterSeconds = waitSeconds) }
            }
        }
    }
}

@HiltViewModel
class LoginViewModel @Inject constructor(private val repository: AuthRepository) : AuthFormViewModel() {
    fun submit() = submit(
        validate = { it.copy(emailError = CredentialsValidator.validateEmail(it.email), passwordMissing = it.password.isEmpty()) },
        action = { repository.login(it.email, it.password) },
        mapHttp = { code -> if (code == 401) AuthFailure.INVALID_CREDENTIALS else AuthFailure.UNKNOWN },
    )
}

@HiltViewModel
class SignupViewModel @Inject constructor(private val repository: AuthRepository) : AuthFormViewModel() {
    fun submit() = submit(
        validate = {
            it.copy(
                nameError = CredentialsValidator.validateName(it.name),
                emailError = CredentialsValidator.validateEmail(it.email),
                passwordError = CredentialsValidator.validatePassword(it.password),
            )
        },
        action = { repository.signup(it.email, it.password, it.name) },
        mapHttp = { code ->
            when (code) {
                409 -> AuthFailure.EMAIL_TAKEN
                // A validação local já passou: o mais provável é senha encontrada em vazamentos.
                400 -> AuthFailure.SIGNUP_REJECTED
                else -> AuthFailure.UNKNOWN
            }
        },
    )
}
