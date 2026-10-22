package com.dutrail.app.activities.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.dutrail.app.activities.data.ActivitiesRepository
import com.dutrail.app.activities.domain.Activity
import com.dutrail.app.auth.data.AuthRepository
import com.dutrail.app.auth.data.SessionStore
import com.dutrail.app.auth.domain.User
import com.dutrail.app.core.ui.AppMessage
import com.dutrail.app.core.ui.AppMessages
import com.dutrail.app.recording.data.RecordingDao
import com.dutrail.app.recording.data.RecordingEntity
import com.dutrail.app.recording.data.upload.UploadScheduler
import com.dutrail.app.recording.service.RecordingManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Gravação interrompida encontrada ao abrir o app, com o resumo para o diálogo. */
data class InterruptedRecording(val id: Long, val startedAtMillis: Long, val distanceMeters: Double)

sealed interface ListEvent {
    data class OpenRecordingResult(val recordingId: Long) : ListEvent
    data class ResumeRecording(val recordingId: Long) : ListEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ActivityListViewModel @Inject constructor(
    activitiesRepository: ActivitiesRepository,
    private val authRepository: AuthRepository,
    private val recordingManager: RecordingManager,
    private val recordingDao: RecordingDao,
    private val uploadScheduler: UploadScheduler,
    private val appMessages: AppMessages,
    sessionStore: SessionStore,
) : ViewModel() {

    val activities: Flow<PagingData<Activity>> = activitiesRepository.pagedActivities().cachedIn(viewModelScope)

    /** Gravações ainda não enviadas desta conta (pendentes ou recusadas). */
    val notUploaded: StateFlow<List<RecordingEntity>> = sessionStore.session
        .flatMapLatest { session -> session?.let { recordingDao.observeNotUploaded(it.userId) } ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Conflated: vários envios enquanto a lista está fora da tela viram um único refresh ao voltar.
    private val _refreshRequests = Channel<Unit>(Channel.CONFLATED)

    /** Pede para a lista recarregar quando um envio termina (inclusive com a tela na pilha, fora da composição). */
    val refreshRequests: Flow<Unit> = _refreshRequests.receiveAsFlow()

    private val _user = MutableStateFlow<User?>(null)
    val user: StateFlow<User?> = _user.asStateFlow()

    private val _interrupted = MutableStateFlow<InterruptedRecording?>(null)
    val interrupted: StateFlow<InterruptedRecording?> = _interrupted.asStateFlow()

    private val _loggingOut = MutableStateFlow(false)
    val loggingOut: StateFlow<Boolean> = _loggingOut.asStateFlow()

    private val _events = Channel<ListEvent>(Channel.BUFFERED)
    val events: Flow<ListEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            recordingDao.observeUploadedCount().drop(1).collect { _refreshRequests.send(Unit) }
        }
        viewModelScope.launch { _user.value = runCatching { authRepository.currentUser() }.getOrNull() }
        viewModelScope.launch {
            _interrupted.value = recordingManager.interruptedRecording()?.let { recording ->
                val last = recordingDao.lastPoint(recording.id)
                InterruptedRecording(recording.id, recording.startedAtMillis, last?.distanceMeters ?: 0.0)
            }
        }
    }

    /** Retomar exige checar permissão e GPS de novo, então passa pela tela de preparo. */
    fun resumeInterrupted() {
        val recording = _interrupted.value ?: return
        _interrupted.value = null
        _events.trySend(ListEvent.ResumeRecording(recording.id))
    }

    fun finishInterrupted() {
        val recording = _interrupted.value ?: return
        _interrupted.value = null
        viewModelScope.launch {
            recordingManager.finishInterrupted(recording.id)
            _events.send(ListEvent.OpenRecordingResult(recording.id))
        }
    }

    fun retryUpload(recordingId: Long) = uploadScheduler.enqueue(recordingId, replace = true)

    fun logout(allDevices: Boolean) {
        if (_loggingOut.value) return
        _loggingOut.value = true
        viewModelScope.launch {
            if (allDevices) {
                // A tela some junto com a sessão, então o aviso vai para a raiz do app.
                if (!authRepository.logoutAll()) appMessages.post(AppMessage.LOGOUT_ALL_NOT_CONFIRMED)
            } else {
                authRepository.logout()
            }
            // A raiz troca para o login quando a sessão some; nada mais a fazer aqui.
        }
    }
}
