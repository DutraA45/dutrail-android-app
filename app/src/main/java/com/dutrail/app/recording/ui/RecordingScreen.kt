package com.dutrail.app.recording.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dutrail.app.R
import com.dutrail.app.core.common.Clock
import com.dutrail.app.core.common.Formatters
import com.dutrail.app.recording.domain.GpsSignal
import com.dutrail.app.recording.domain.Lap
import com.dutrail.app.recording.domain.TimerState
import com.dutrail.app.recording.service.ActiveRecording
import com.dutrail.app.recording.service.RecordingManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed interface RecordingEvent {
    data class Finished(val recordingId: Long) : RecordingEvent
    data object Discarded : RecordingEvent
}

@HiltViewModel
class RecordingViewModel @Inject constructor(
    private val manager: RecordingManager,
    private val clock: Clock,
) : ViewModel() {

    val active: StateFlow<ActiveRecording?> = manager.active

    private val _events = Channel<RecordingEvent>(Channel.BUFFERED)
    val events: Flow<RecordingEvent> = _events.receiveAsFlow()

    /** Evita tratar o fim da gravação (active = null) como "gravação sumiu". */
    var leaving = false
        private set

    fun now() = clock.nowMillis()

    fun pause() = viewModelScope.launch { manager.pause() }

    fun resume() = viewModelScope.launch { manager.resume() }

    fun finish() {
        if (leaving) return
        leaving = true
        viewModelScope.launch {
            // finish() devolve null num toque duplo: a gravação já foi encerrada pelo primeiro.
            manager.finish()?.let { _events.send(RecordingEvent.Finished(it)) }
        }
    }

    fun discard() {
        if (leaving) return
        leaving = true
        viewModelScope.launch {
            manager.discard()
            _events.send(RecordingEvent.Discarded)
        }
    }
}

/** Relógio de corrida: números grandes, sem mapa, tela sempre acesa. */
@Composable
fun RecordingScreen(
    onFinished: (Long) -> Unit,
    onExit: () -> Unit,
    onShowRoute: () -> Unit,
    viewModel: RecordingViewModel = hiltViewModel(),
) {
    val active by viewModel.active.collectAsStateWithLifecycle()
    var confirmFinish by rememberSaveable { mutableStateOf(false) }
    val activity = LocalActivity.current

    KeepScreenOn()
    // Voltar não encerra nada: manda o app para o fundo, como num relógio. A gravação continua no serviço.
    BackHandler { activity?.moveTaskToBack(true) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is RecordingEvent.Finished -> onFinished(event.recordingId)
                RecordingEvent.Discarded -> onExit()
            }
        }
    }
    // Sem gravação ativa (ex.: tela restaurada depois de o processo morrer): volta para a lista,
    // que oferece retomar ou finalizar.
    LaunchedEffect(active == null) {
        if (active == null && !viewModel.leaving) onExit()
    }

    val current = active ?: return
    val stats = current.stats
    val paused = stats.timerState == TimerState.PAUSED

    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val fix = current.lastFix
                GpsSignalIndicator(
                    signal = GpsSignal.of(fix?.accuracyMeters, fix?.let { viewModel.now() - it.timeMillis } ?: Long.MAX_VALUE),
                    accuracyMeters = fix?.accuracyMeters,
                    modifier = Modifier.weight(1f),
                )
                if (paused) {
                    Text(
                        stringResource(R.string.recording_paused),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            BigMetric(stringResource(R.string.metric_time), Formatters.duration(stats.activeMillis / 1000), 76.sp)
            BigMetric(stringResource(R.string.metric_distance_km), Formatters.distanceKm(stats.distanceMeters), 68.sp)
            Row(Modifier.fillMaxWidth()) {
                BigMetric(stringResource(R.string.metric_current_pace), Formatters.pace(stats.currentPaceSecondsPerKm), 44.sp, Modifier.weight(1f))
                BigMetric(stringResource(R.string.metric_avg_pace), Formatters.pace(stats.averagePaceSecondsPerKm), 44.sp, Modifier.weight(1f))
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            LapList(stats.completedLaps, Modifier.weight(1f).fillMaxWidth())

            OutlinedButton(onClick = onShowRoute, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_show_route))
            }
            Spacer(Modifier.height(8.dp))
            if (paused) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ControlButton(stringResource(R.string.action_resume), viewModel::resume, Modifier.weight(1f))
                    ControlButton(
                        stringResource(R.string.action_finish),
                        { confirmFinish = true },
                        Modifier.weight(1f),
                        destructive = true,
                    )
                }
            } else {
                // Terminar só aparece pausado, como nos relógios: evita encerrar sem querer.
                ControlButton(stringResource(R.string.action_pause), viewModel::pause, Modifier.fillMaxWidth())
            }
        }
    }

    if (confirmFinish) {
        val nothingRecorded = stats.distanceMeters <= 0.0
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            title = { Text(stringResource(if (nothingRecorded) R.string.finish_empty_title else R.string.finish_title)) },
            text = { Text(stringResource(if (nothingRecorded) R.string.finish_empty_body else R.string.finish_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmFinish = false
                    if (nothingRecorded) viewModel.discard() else viewModel.finish()
                }) { Text(stringResource(if (nothingRecorded) R.string.action_discard else R.string.action_finish)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmFinish = false }) { Text(stringResource(R.string.action_keep_recording)) }
            },
        )
    }
}

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/** Dígitos tabulares: os números não "pulam" de largura a cada segundo. */
private val tabular = TextStyle(fontFeatureSettings = "tnum", fontWeight = FontWeight.Bold)

@Composable
private fun BigMetric(label: String, value: String, size: TextUnit, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = tabular.copy(fontSize = size, lineHeight = size))
    }
}

@Composable
private fun LapList(laps: List<Lap>, modifier: Modifier) {
    if (laps.isEmpty()) {
        Text(stringResource(R.string.laps_empty), modifier = modifier, style = MaterialTheme.typography.bodyMedium)
        return
    }
    LazyColumn(modifier) {
        item {
            LapRow(stringResource(R.string.lap_header_km), stringResource(R.string.lap_header_time), stringResource(R.string.lap_header_pace), header = true)
        }
        // Mais recente primeiro: o km que acabou de fechar fica visível sem rolar.
        items(laps.asReversed(), key = { it.index }) { lap ->
            LapRow(lap.index.toString(), Formatters.duration(lap.timerMillis / 1000), Formatters.pace(lap.paceSecondsPerKm))
        }
    }
}

@Composable
private fun LapRow(km: String, time: String, pace: String, header: Boolean = false) {
    val style = if (header) MaterialTheme.typography.labelLarge else tabular.copy(fontSize = 22.sp)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(km, style = style, modifier = Modifier.weight(1f))
        Text(time, style = style, modifier = Modifier.weight(1f))
        Text(pace, style = style, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ControlButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, destructive: Boolean = false) {
    Button(
        onClick = onClick,
        modifier = modifier.height(72.dp),
        colors = if (destructive) {
            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        Text(label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}
