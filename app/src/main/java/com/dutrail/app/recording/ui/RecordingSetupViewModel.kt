package com.dutrail.app.recording.ui

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.dutrail.app.core.common.Clock
import com.dutrail.app.navigation.RecordingSetupRoute
import com.dutrail.app.recording.data.LocationClient
import com.dutrail.app.recording.data.LocationSettingsState
import com.dutrail.app.recording.domain.GpsSignal
import com.dutrail.app.recording.domain.LocationSample
import com.dutrail.app.recording.service.RecordingManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SetupState(
    val permissionGranted: Boolean,
    /** null enquanto verifica. */
    val locationSettings: LocationSettingsState?,
    val signal: GpsSignal,
    val accuracyMeters: Float?,
    val starting: Boolean,
    val isResume: Boolean,
) {
    val canStart: Boolean
        get() = permissionGranted && locationSettings == LocationSettingsState.Satisfied && signal.allowsStart && !starting
}

/**
 * Pré-requisitos para gravar: permissão, localização do aparelho ligada e um fix com precisão
 * aceitável. Enquanto esta tela está visível, assina a localização só para medir o sinal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RecordingSetupViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val locationClient: LocationClient,
    private val manager: RecordingManager,
    private val clock: Clock,
) : ViewModel() {

    private val resumeRecordingId = savedStateHandle.toRoute<RecordingSetupRoute>().resumeRecordingId

    private val permissionGranted = MutableStateFlow(locationClient.hasFineLocationPermission())
    private val settings = MutableStateFlow<LocationSettingsState?>(null)
    private val starting = MutableStateFlow(false)

    private val lastFix: Flow<LocationSample?> = combine(permissionGranted, settings) { granted, s ->
        granted && s == LocationSettingsState.Satisfied
    }.distinctUntilChanged().flatMapLatest { ready ->
        if (!ready) {
            flowOf(null)
        } else {
            locationClient.updates().map<LocationSample, LocationSample?> { it }.onStart { emit(null) }.catch { emit(null) }
        }
    }

    // Reavalia o sinal a cada segundo para detectar fix "velho" mesmo sem atualização nova.
    private val ticker = flow {
        while (true) {
            emit(clock.nowMillis())
            delay(1_000)
        }
    }

    val state: StateFlow<SetupState> = combine(permissionGranted, settings, lastFix, ticker, starting) { granted, s, fix, now, isStarting ->
        SetupState(
            permissionGranted = granted,
            locationSettings = s,
            signal = GpsSignal.of(fix?.accuracyMeters, fix?.let { now - it.timeMillis } ?: Long.MAX_VALUE),
            accuracyMeters = fix?.accuracyMeters,
            starting = isStarting,
            isResume = resumeRecordingId != null,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SetupState(permissionGranted.value, null, GpsSignal.NONE, null, false, resumeRecordingId != null),
    )

    private val _started = Channel<Unit>(Channel.BUFFERED)
    val started: Flow<Unit> = _started.receiveAsFlow()

    /** Chamado ao voltar para a tela (o usuário pode ter mudado algo nas configurações). */
    fun refresh() {
        permissionGranted.value = locationClient.hasFineLocationPermission()
        if (permissionGranted.value) {
            viewModelScope.launch { settings.value = locationClient.checkSettings() }
        }
    }

    fun start() {
        if (!state.value.canStart) return
        starting.value = true
        viewModelScope.launch {
            val id = resumeRecordingId
            runCatching {
                when {
                    manager.active.value != null -> Unit // já está gravando: só abre o relógio
                    id != null -> manager.resumeInterrupted(id)
                    else -> manager.start()
                }
            }.onSuccess { _started.trySend(Unit) }
                .onFailure {
                    Log.e(TAG, "Não foi possível iniciar a gravação", it)
                    starting.value = false
                }
        }
    }

    private companion object {
        const val TAG = "RecordingSetup"
    }
}
