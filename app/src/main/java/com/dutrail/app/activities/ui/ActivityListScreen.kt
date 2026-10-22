package com.dutrail.app.activities.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.dutrail.app.R
import com.dutrail.app.activities.domain.Activity
import com.dutrail.app.core.common.Formatters
import com.dutrail.app.core.ui.CenteredMessage
import com.dutrail.app.core.ui.InlineError
import com.dutrail.app.core.ui.LoadingBox
import com.dutrail.app.core.ui.message
import com.dutrail.app.core.ui.retryLabel
import com.dutrail.app.core.ui.toErrorKind
import com.dutrail.app.recording.data.RecordingEntity
import com.dutrail.app.recording.data.RecordingStatus
import com.dutrail.app.recording.data.UploadError
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val dateFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    .withZone(ZoneId.systemDefault())

fun formatDateTime(instant: Instant): String = dateFormatter.format(instant)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityListScreen(
    onOpenActivity: (String) -> Unit,
    onStartRecording: () -> Unit,
    onResumeRecording: (Long) -> Unit,
    onOpenRecordingResult: (Long) -> Unit,
    viewModel: ActivityListViewModel = hiltViewModel(),
) {
    val items = viewModel.activities.collectAsLazyPagingItems()
    val notUploaded by viewModel.notUploaded.collectAsStateWithLifecycle()
    val user by viewModel.user.collectAsStateWithLifecycle()
    val interrupted by viewModel.interrupted.collectAsStateWithLifecycle()
    val loggingOut by viewModel.loggingOut.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refreshRequests.collect { items.refresh() } }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ListEvent.OpenRecordingResult -> onOpenRecordingResult(event.recordingId)
                is ListEvent.ResumeRecording -> onResumeRecording(event.recordingId)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.activities_title)) },
                actions = {
                    AccountMenu(
                        email = user?.email,
                        enabled = !loggingOut,
                        onLogout = { viewModel.logout(allDevices = false) },
                        onLogoutAll = { viewModel.logout(allDevices = true) },
                    )
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onStartRecording,
                icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                text = { Text(stringResource(R.string.action_record)) },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = items.loadState.refresh is LoadState.Loading && items.itemCount > 0,
            onRefresh = items::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            ActivityListContent(items, notUploaded, onOpenActivity, viewModel::retryUpload)
        }
    }

    interrupted?.let { recording ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.interrupted_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.interrupted_body,
                        formatDateTime(Instant.ofEpochMilli(recording.startedAtMillis)),
                        Formatters.distanceKm(recording.distanceMeters),
                    ),
                )
            },
            confirmButton = { TextButton(onClick = viewModel::resumeInterrupted) { Text(stringResource(R.string.action_resume)) } },
            dismissButton = { TextButton(onClick = viewModel::finishInterrupted) { Text(stringResource(R.string.action_finish)) } },
        )
    }
}

@Composable
private fun ActivityListContent(
    items: LazyPagingItems<Activity>,
    notUploaded: List<RecordingEntity>,
    onOpenActivity: (String) -> Unit,
    onRetryUpload: (Long) -> Unit,
) {
    val refresh = items.loadState.refresh
    if (items.itemCount == 0 && notUploaded.isEmpty()) {
        // Dentro de uma LazyColumn para o "puxar para atualizar" funcionar também com a lista vazia:
        // o PullToRefreshBox só reage a gestos de um conteúdo rolável.
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                val fullScreen = Modifier.fillParentMaxSize()
                when (refresh) {
                    is LoadState.Loading -> LoadingBox(fullScreen)
                    is LoadState.Error -> CenteredMessage(
                        title = refresh.error.toErrorKind().message(),
                        modifier = fullScreen,
                        actionLabel = retryLabel(),
                        onAction = items::retry,
                    )
                    is LoadState.NotLoading -> CenteredMessage(
                        title = stringResource(R.string.activities_empty_title),
                        modifier = fullScreen,
                        body = stringResource(R.string.activities_empty_body),
                    )
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(notUploaded, key = { "local-${it.id}" }) { PendingUploadCard(it, onRetryUpload) }

        if (refresh is LoadState.Error && items.itemCount == 0) {
            item { InlineError(refresh.error.toErrorKind().message()) }
        }
        items(count = items.itemCount, key = { index -> items.peek(index)?.id ?: index }) { index ->
            items[index]?.let { ActivityRow(it, onClick = { onOpenActivity(it.id) }) }
        }
        when (val append = items.loadState.append) {
            is LoadState.Loading -> item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            is LoadState.Error -> item {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    InlineError(append.error.toErrorKind().message())
                    TextButton(onClick = items::retry) { Text(retryLabel()) }
                }
            }
            is LoadState.NotLoading -> Unit
        }
    }
}

@Composable
private fun ActivityRow(activity: Activity, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(activity.name, style = MaterialTheme.typography.titleMedium)
            Text(formatDateTime(activity.startedAt), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Metric(
                    label = stringResource(R.string.metric_distance),
                    value = activity.distanceMeters?.let { stringResource(R.string.value_km, Formatters.distanceKm(it)) } ?: "—",
                )
                Metric(label = stringResource(R.string.metric_duration), value = Formatters.duration(activity.durationSeconds))
                Metric(
                    label = stringResource(R.string.metric_avg_pace),
                    value = stringResource(R.string.value_pace, Formatters.pace(activity.averagePaceSecondsPerKm)),
                )
            }
        }
    }
}

@Composable
fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun PendingUploadCard(recording: RecordingEntity, onRetry: (Long) -> Unit) {
    val failed = recording.status == RecordingStatus.UPLOAD_FAILED
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(
                        R.string.pending_title,
                        formatDateTime(Instant.ofEpochMilli(recording.startedAtMillis)),
                        Formatters.distanceKm(recording.distanceMeters ?: 0.0),
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(stringResource(recording.statusText()), style = MaterialTheme.typography.bodySmall)
            }
            if (failed || recording.uploadError == UploadError.NETWORK || recording.uploadError == UploadError.SERVER) {
                TextButton(onClick = { onRetry(recording.id) }) { Text(stringResource(R.string.action_retry_now)) }
            }
        }
    }
}

fun RecordingEntity.statusText(): Int = when {
    status == RecordingStatus.FINISHING -> R.string.upload_status_saving
    status == RecordingStatus.UPLOAD_FAILED && uploadError == UploadError.FILE_MISSING -> R.string.upload_status_file_missing
    status == RecordingStatus.UPLOAD_FAILED && uploadError == UploadError.FILE_TOO_LARGE -> R.string.upload_status_too_large
    status == RecordingStatus.UPLOAD_FAILED -> R.string.upload_status_rejected
    uploadError == UploadError.NETWORK -> R.string.upload_status_offline
    uploadError == UploadError.SERVER -> R.string.upload_status_server
    uploadError == UploadError.SESSION_EXPIRED -> R.string.upload_status_session
    else -> R.string.upload_status_sending
}

@Composable
private fun AccountMenu(email: String?, enabled: Boolean, onLogout: () -> Unit, onLogoutAll: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(Icons.Filled.AccountCircle, contentDescription = stringResource(R.string.account_menu))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (email != null) {
                Text(email, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                HorizontalDivider()
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.action_logout)) }, onClick = {
                expanded = false
                onLogout()
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.action_logout_all)) }, onClick = {
                expanded = false
                onLogoutAll()
            })
        }
    }
}
