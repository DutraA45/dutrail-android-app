package com.dutrail.app.recording.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.dutrail.app.R
import com.dutrail.app.activities.ui.statusText
import com.dutrail.app.core.common.Formatters
import com.dutrail.app.navigation.RecordingResultRoute
import com.dutrail.app.recording.data.RecordingDao
import com.dutrail.app.recording.data.RecordingEntity
import com.dutrail.app.recording.data.RecordingStatus
import com.dutrail.app.recording.data.UploadError
import com.dutrail.app.recording.data.upload.UploadScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class RecordingResultViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    dao: RecordingDao,
    private val uploadScheduler: UploadScheduler,
) : ViewModel() {
    private val recordingId = savedStateHandle.toRoute<RecordingResultRoute>().recordingId

    val recording: StateFlow<RecordingEntity?> =
        dao.observeRecording(recordingId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun retry() = uploadScheduler.enqueue(recordingId, replace = true)
}

/** Acompanha a geração do .fit e o envio. No sucesso, abre a atividade criada (ou a lista, no 409). */
@Composable
fun RecordingResultScreen(
    onOpenActivity: (String) -> Unit,
    onOpenList: () -> Unit,
    viewModel: RecordingResultViewModel = hiltViewModel(),
) {
    val recording by viewModel.recording.collectAsStateWithLifecycle()

    LaunchedEffect(recording?.status) {
        val current = recording ?: return@LaunchedEffect
        if (current.status == RecordingStatus.UPLOADED) {
            current.remoteActivityId?.let(onOpenActivity) ?: onOpenList()
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val current = recording
            val working = current == null || current.status == RecordingStatus.FINISHING ||
                (current.status == RecordingStatus.PENDING_UPLOAD && current.uploadError == null) ||
                current.status == RecordingStatus.UPLOADED

            current?.distanceMeters?.let {
                Text(stringResource(R.string.value_km, Formatters.distanceKm(it)), style = MaterialTheme.typography.displayMedium)
            }
            current?.timerMillis?.let {
                Text(Formatters.duration(it / 1000), style = MaterialTheme.typography.headlineMedium)
            }
            if (working) CircularProgressIndicator()
            if (current != null) {
                Text(stringResource(current.statusText()), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            }
            if (current?.status == RecordingStatus.UPLOAD_FAILED && current.uploadError == UploadError.INVALID_FILE) {
                Button(onClick = viewModel::retry, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_retry)) }
            }
            if (working) {
                // Sem rede o worker nem chega a rodar; a atividade já está salva e sobe sozinha depois.
                Text(stringResource(R.string.upload_background_hint), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
            OutlinedButton(onClick = onOpenList, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_go_to_list)) }
        }
    }
}
