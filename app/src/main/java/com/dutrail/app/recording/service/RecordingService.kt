package com.dutrail.app.recording.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dutrail.app.MainActivity
import com.dutrail.app.R
import com.dutrail.app.core.common.Clock
import com.dutrail.app.core.common.Formatters
import com.dutrail.app.recording.data.LocationClient
import com.dutrail.app.recording.domain.TimerState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service (tipo location) que mantém a gravação viva com a tela apagada ou o app em
 * segundo plano. Ele só recebe os fixes e os repassa ao [RecordingManager], que concentra a lógica.
 *
 * Não pedimos ACCESS_BACKGROUND_LOCATION: o serviço sempre é iniciado com o app em primeiro plano
 * (botão Iniciar/Retomar), e isso basta para manter o acesso à localização "durante o uso". Por isso
 * o serviço não se reinicia sozinho (START_NOT_STICKY): se o processo morrer, a gravação é retomada
 * ou finalizada pelo usuário ao reabrir o app.
 */
@AndroidEntryPoint
class RecordingService : Service() {

    @Inject lateinit var manager: RecordingManager
    @Inject lateinit var locationClient: LocationClient
    @Inject lateinit var clock: Clock

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> scope.launch { manager.pause() }
            ACTION_RESUME -> scope.launch { manager.resume() }
        }
        if (!enterForeground()) return START_NOT_STICKY
        if (!observing) {
            observing = true
            observeRecording()
            collectLocations()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun enterForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(manager.active.value?.stats?.timerState, distanceText = null),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
        true
    } catch (e: SecurityException) {
        // Permissão de localização revogada entre o clique em Iniciar e aqui.
        Log.e(TAG, "Sem permissão para o serviço de localização", e)
        stopSelf()
        false
    }

    private fun observeRecording() = scope.launch {
        manager.active
            .map { active -> active?.let { it.stats.timerState to Formatters.distanceKm(it.stats.distanceMeters) } }
            .distinctUntilChanged()
            .collect { content ->
                if (content == null) {
                    ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    notify(buildNotification(content.first, content.second))
                }
            }
    }

    private fun collectLocations() = scope.launch {
        if (!locationClient.hasFineLocationPermission()) {
            Log.e(TAG, "Permissão de localização ausente; gravação sem pontos")
            return@launch
        }
        locationClient.updates().collect { manager.onLocation(it) }
    }

    private fun notify(notification: Notification) {
        // Sem POST_NOTIFICATIONS (Android 13+) a atualização é ignorada; o serviço continua rodando.
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(state: TimerState?, distanceText: String?): Notification {
        ensureChannel(this)
        val paused = state == TimerState.PAUSED
        val active = manager.active.value
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_recording)
            .setContentTitle(getString(if (paused) R.string.notification_paused else R.string.notification_recording))
            .setContentText(distanceText?.let { getString(R.string.notification_distance, it) })
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (!paused && active != null) {
            // Cronômetro nativo da notificação: atualiza sozinho, sem precisar re-notificar a cada segundo.
            builder.setUsesChronometer(true).setShowWhen(true).setWhen(clock.nowMillis() - active.stats.activeMillis)
        }
        if (state != null) {
            val (action, label) = if (paused) ACTION_RESUME to R.string.action_resume else ACTION_PAUSE to R.string.action_pause
            builder.addAction(0, getString(label), servicePendingIntent(action))
        }
        return builder.build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun servicePendingIntent(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        Intent(this, RecordingService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_PAUSE = "com.dutrail.app.recording.PAUSE"
        private const val ACTION_RESUME = "com.dutrail.app.recording.RESUME"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel_recording), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }
}
