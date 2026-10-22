package com.dutrail.app.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dutrail.app.R
import com.dutrail.app.core.network.ApiError

/** Categorias de erro genéricas (as telas tratam os casos específicos antes de cair aqui). */
enum class ErrorKind { NETWORK, RATE_LIMITED, SERVER, NOT_FOUND, UNKNOWN }

fun Throwable.toErrorKind(): ErrorKind = when (this) {
    is ApiError.Network -> ErrorKind.NETWORK
    is ApiError.Http -> when {
        code == 404 -> ErrorKind.NOT_FOUND
        code == 429 -> ErrorKind.RATE_LIMITED
        code >= 500 -> ErrorKind.SERVER
        else -> ErrorKind.UNKNOWN
    }
    else -> ErrorKind.UNKNOWN
}

@Composable
fun ErrorKind.message(): String = stringResource(
    when (this) {
        ErrorKind.NETWORK -> R.string.error_network
        ErrorKind.RATE_LIMITED -> R.string.error_rate_limited
        ErrorKind.SERVER -> R.string.error_server
        ErrorKind.NOT_FOUND -> R.string.error_not_found
        ErrorKind.UNKNOWN -> R.string.error_unknown
    },
)

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** Estado vazio ou de erro centralizado, com ação opcional. */
@Composable
fun CenteredMessage(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) {
            Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** Mensagem de erro curta para o rodapé da lista ou de um formulário. */
@Composable
fun InlineError(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

@Composable
fun retryLabel() = stringResource(R.string.action_retry)
