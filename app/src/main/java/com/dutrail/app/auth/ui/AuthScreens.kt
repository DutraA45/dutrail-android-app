package com.dutrail.app.auth.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dutrail.app.R
import com.dutrail.app.auth.domain.CredentialsValidator
import com.dutrail.app.auth.domain.EmailError
import com.dutrail.app.auth.domain.NameError
import com.dutrail.app.auth.domain.PasswordError
import com.dutrail.app.core.ui.InlineError

@Composable
fun LoginScreen(onCreateAccount: () -> Unit, viewModel: LoginViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AuthForm(
        title = stringResource(R.string.login_title),
        state = state,
        showName = false,
        submitLabel = stringResource(R.string.login_submit),
        onNameChange = viewModel::onNameChange,
        onEmailChange = viewModel::onEmailChange,
        onPasswordChange = viewModel::onPasswordChange,
        onSubmit = viewModel::submit,
        secondaryLabel = stringResource(R.string.login_create_account),
        onSecondary = onCreateAccount,
    )
}

@Composable
fun SignupScreen(onHaveAccount: () -> Unit, viewModel: SignupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AuthForm(
        title = stringResource(R.string.signup_title),
        state = state,
        showName = true,
        submitLabel = stringResource(R.string.signup_submit),
        onNameChange = viewModel::onNameChange,
        onEmailChange = viewModel::onEmailChange,
        onPasswordChange = viewModel::onPasswordChange,
        onSubmit = viewModel::submit,
        secondaryLabel = stringResource(R.string.signup_have_account),
        onSecondary = onHaveAccount,
    )
}

@Composable
private fun AuthForm(
    title: String,
    state: AuthFormState,
    showName: Boolean,
    submitLabel: String,
    onNameChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
) {
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleLarge)

            if (showName) {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.field_name_optional)) },
                    isError = state.nameError != null,
                    supportingText = state.nameError?.let { { Text(it.message()) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = state.email,
                onValueChange = onEmailChange,
                label = { Text(stringResource(R.string.field_email)) },
                isError = state.emailError != null,
                supportingText = state.emailError?.let { { Text(it.message()) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = onPasswordChange,
                label = { Text(stringResource(R.string.field_password)) },
                isError = state.passwordError != null || state.passwordMissing,
                supportingText = when {
                    state.passwordError != null -> ({ Text(state.passwordError.message()) })
                    state.passwordMissing -> ({ Text(stringResource(R.string.error_password_missing)) })
                    showName -> ({ Text(stringResource(R.string.hint_password_rules, CredentialsValidator.PASSWORD_MIN_LENGTH)) })
                    else -> null
                },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { passwordVisible = !passwordVisible }) {
                        Text(stringResource(if (passwordVisible) R.string.action_hide else R.string.action_show))
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
            )

            state.failure?.let { InlineError(it.message(state.retryAfterSeconds)) }

            Button(onClick = onSubmit, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                if (state.loading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) else Text(submitLabel)
            }
            TextButton(onClick = onSecondary, enabled = !state.loading, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(secondaryLabel)
            }
        }
    }
}

@Composable
private fun AuthFailure.message(retryAfterSeconds: Long?): String = if (this == AuthFailure.TOO_MANY_ATTEMPTS && retryAfterSeconds != null) {
    stringResource(R.string.error_too_many_attempts_wait, retryAfterSeconds)
} else stringResource(
    when (this) {
        AuthFailure.INVALID_CREDENTIALS -> R.string.error_invalid_credentials
        AuthFailure.EMAIL_TAKEN -> R.string.error_email_taken
        AuthFailure.SIGNUP_REJECTED -> R.string.error_signup_rejected
        AuthFailure.TOO_MANY_ATTEMPTS -> R.string.error_too_many_attempts
        AuthFailure.NETWORK -> R.string.error_network
        AuthFailure.UNKNOWN -> R.string.error_unknown
    },
)

@Composable
private fun EmailError.message(): String = stringResource(
    when (this) {
        EmailError.EMPTY -> R.string.error_email_empty
        EmailError.INVALID -> R.string.error_email_invalid
    },
)

@Composable
private fun PasswordError.message(): String = when (this) {
    PasswordError.TOO_SHORT -> stringResource(R.string.error_password_short, CredentialsValidator.PASSWORD_MIN_LENGTH)
    PasswordError.TOO_LONG -> stringResource(R.string.error_password_long, CredentialsValidator.PASSWORD_MAX_LENGTH)
}

@Composable
private fun NameError.message(): String = stringResource(R.string.error_name_long, CredentialsValidator.NAME_MAX_LENGTH)
