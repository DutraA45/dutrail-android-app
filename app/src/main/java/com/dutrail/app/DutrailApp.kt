package com.dutrail.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.dutrail.app.auth.data.SessionStore
import com.dutrail.app.core.common.ApplicationScope
import com.dutrail.app.recording.data.RecordingDao
import com.dutrail.app.recording.data.RecordingFinalizer
import com.dutrail.app.recording.data.upload.UploadScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@HiltAndroidApp
class DutrailApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var sessionStore: SessionStore
    @Inject lateinit var recordingDao: RecordingDao
    @Inject lateinit var finalizer: RecordingFinalizer
    @Inject lateinit var uploadScheduler: UploadScheduler

    @Inject @ApplicationScope
    lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            // Gravações que pararam sem chegar a gerar o .fit (processo morto no meio).
            finalizer.finalizeInterrupted()
            // A cada login (e na abertura do app logado), coloca na fila os envios pendentes da conta.
            sessionStore.session.map { it?.userId }.distinctUntilChanged().filterNotNull().collect { userId ->
                recordingDao.pendingUploadIds(userId).forEach(uploadScheduler::enqueue)
            }
        }
    }
}
