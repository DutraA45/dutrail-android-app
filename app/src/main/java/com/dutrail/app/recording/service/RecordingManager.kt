package com.dutrail.app.recording.service

import android.content.Context
import com.dutrail.app.auth.data.SessionStore
import com.dutrail.app.core.common.ApplicationScope
import com.dutrail.app.core.common.Clock
import com.dutrail.app.recording.data.RecordingDao
import com.dutrail.app.recording.data.RecordingEntity
import com.dutrail.app.recording.data.RecordingFinalizer
import com.dutrail.app.recording.data.RecordingStatus
import com.dutrail.app.recording.data.TimerEventEntity
import com.dutrail.app.recording.data.toEntity
import com.dutrail.app.recording.domain.LocationSample
import com.dutrail.app.recording.domain.RecordingStats
import com.dutrail.app.recording.domain.RecordingTracker
import com.dutrail.app.recording.domain.TimerEvent
import com.dutrail.app.recording.domain.TimerState
import com.dutrail.app.recording.domain.TrackPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Gravação ativa neste processo, como a UI e a notificação a enxergam. */
data class ActiveRecording(
    val recordingId: Long,
    val stats: RecordingStats,
    /** Último fix recebido, mesmo rejeitado pelo filtro: alimenta o indicador de sinal. */
    val lastFix: LocationSample?,
)

/**
 * Dono da gravação em andamento. Mantém o [RecordingTracker], persiste cada evento e ponto aceito
 * no Room antes de publicar o estado, e liga/desliga o [RecordingService].
 *
 * Todas as operações passam pelo mesmo [Mutex]. Assim um ponto que chega durante "Terminar" nunca
 * fica de fora do arquivo nem é gravado depois do STOP.
 */
@Singleton
class RecordingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: RecordingDao,
    private val sessionStore: SessionStore,
    private val finalizer: RecordingFinalizer,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var tracker: RecordingTracker? = null
    private var recordingId: Long = 0
    private var lastFix: LocationSample? = null
    private var ticker: Job? = null

    private val _active = MutableStateFlow<ActiveRecording?>(null)

    /** null = nada sendo gravado neste processo. */
    val active: StateFlow<ActiveRecording?> = _active.asStateFlow()

    suspend fun start(): Long = locked {
        check(tracker == null) { "Já existe uma gravação ativa" }
        val owner = checkNotNull(sessionStore.current()) { "Gravação requer sessão" }.userId
        val newTracker = RecordingTracker()
        val start = newTracker.start(clock.nowMillis())
        val id = dao.createRecording(
            RecordingEntity(ownerUserId = owner, startedAtMillis = start.timeMillis, status = RecordingStatus.RECORDING),
            start.toEntity(recordingId = 0),
        )
        activate(id, newTracker)
        id
    }

    suspend fun pause() = locked {
        val event = tracker?.pause(clock.nowMillis()) ?: return@locked
        dao.addEventAndSetStatus(event.toEntity(recordingId), RecordingStatus.PAUSED)
        publish()
    }

    suspend fun resume() = locked {
        val event = tracker?.resume(clock.nowMillis()) ?: return@locked
        dao.addEventAndSetStatus(event.toEntity(recordingId), RecordingStatus.RECORDING)
        publish()
    }

    suspend fun onLocation(sample: LocationSample) = locked {
        val current = tracker ?: return@locked
        lastFix = sample
        current.onLocation(sample)?.let { dao.insertPoint(it.toEntity(recordingId)) }
        publish()
    }

    /**
     * Para a gravação e dispara a geração do .fit e o envio em segundo plano.
     * @return o id da gravação, ou null se não havia gravação ativa (ex.: toque duplo em Terminar).
     */
    suspend fun finish(): Long? {
        val id = locked {
            val current = tracker ?: return@locked null
            val stop = current.stop(clock.nowMillis()) ?: return@locked null
            dao.stopRecording(stop.toEntity(recordingId))
            recordingId.also { deactivate() }
        } ?: return null
        // No escopo da aplicação: não depende de a tela continuar aberta.
        scope.launch { finalizer.finalize(id) }
        return id
    }

    /** Descarta uma gravação sem nenhum ponto de GPS (não há o que salvar). */
    suspend fun discard() = locked {
        if (tracker == null) return@locked
        val id = recordingId
        deactivate()
        dao.deleteRecording(id)
    }

    /** Gravação que ficou "em andamento" no banco, mas sem processo gravando (app morto, reinício). */
    suspend fun interruptedRecording(): RecordingEntity? = locked {
        if (tracker != null) null else dao.unfinishedRecording()
    }

    /** Retoma uma gravação interrompida. O tempo em que o app ficou fora conta como pausa. */
    suspend fun resumeInterrupted(id: Long) = locked {
        check(tracker == null) { "Já existe uma gravação ativa" }
        val (events, points) = load(id)
        val restored = RecordingTracker.restore(events, points)
        if (restored.timerState == TimerState.RUNNING) {
            restored.pause(lastActivityMillis(events, points))?.let { dao.insertEvent(it.toEntity(id)) }
        }
        restored.resume(clock.nowMillis())?.let { dao.addEventAndSetStatus(it.toEntity(id), RecordingStatus.RECORDING) }
        activate(id, restored)
    }

    /** Finaliza uma gravação interrompida, encerrando-a no último momento registrado. */
    suspend fun finishInterrupted(id: Long) {
        locked {
            val (events, points) = load(id)
            RecordingTracker.restore(events, points).stop(lastActivityMillis(events, points))
                ?.let { dao.stopRecording(it.toEntity(id)) }
        }
        scope.launch { finalizer.finalize(id) }
    }

    /** Serializa as operações e as protege de cancelamento: a memória e o Room nunca divergem no meio de uma. */
    private suspend fun <T> locked(block: suspend () -> T): T = withContext(NonCancellable) { mutex.withLock { block() } }

    private suspend fun load(id: Long): Pair<List<TimerEvent>, List<TrackPoint>> =
        dao.events(id).map { it.toDomain() } to dao.points(id).map { it.toDomain() }

    private fun lastActivityMillis(events: List<TimerEvent>, points: List<TrackPoint>): Long =
        maxOf(events.last().timeMillis, points.lastOrNull()?.timeMillis ?: Long.MIN_VALUE)

    private fun activate(id: Long, newTracker: RecordingTracker) {
        tracker = newTracker
        recordingId = id
        lastFix = null
        publish()
        // O relógio da tela avança a cada segundo, mesmo sem fix novo.
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(1_000)
                mutex.withLock { if (tracker != null) publish() }
            }
        }
        RecordingService.start(context)
    }

    private fun deactivate() {
        ticker?.cancel()
        ticker = null
        tracker = null
        lastFix = null
        _active.value = null
    }

    private fun publish() {
        val current = tracker ?: return
        _active.value = ActiveRecording(recordingId, current.stats(clock.nowMillis()), lastFix)
    }

    private fun TimerEvent.toEntity(recordingId: Long) = TimerEventEntity(recordingId = recordingId, timeMillis = timeMillis, type = type)
}
