package com.dutrail.app.recording.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dutrail.app.R
import com.dutrail.app.recording.data.LocationSettingsState

private val locationPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

/** No Android 13+ a notificação do serviço precisa de permissão. Sem ela a gravação funciona, só não aparece. */
private val requestedPermissions =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) locationPermissions + Manifest.permission.POST_NOTIFICATIONS else locationPermissions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingSetupScreen(
    onBack: () -> Unit,
    onStarted: () -> Unit,
    viewModel: RecordingSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    var requestedOnce by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        requestedOnce = true
        viewModel.refresh()
    }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        viewModel.refresh()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    LaunchedEffect(Unit) { viewModel.started.collect { onStarted() } }

    fun openAppSettings() = context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isResume) R.string.setup_title_resume else R.string.setup_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                !state.permissionGranted -> {
                    val permanentlyDenied = requestedOnce && activity != null &&
                        !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
                    Explanation(
                        title = stringResource(R.string.setup_permission_title),
                        body = stringResource(if (permanentlyDenied) R.string.setup_permission_denied else R.string.setup_permission_body),
                    )
                    if (permanentlyDenied) {
                        Button(onClick = ::openAppSettings) { Text(stringResource(R.string.action_open_settings)) }
                    } else {
                        Button(onClick = { permissionLauncher.launch(requestedPermissions) }) {
                            Text(stringResource(R.string.action_allow_location))
                        }
                    }
                }

                state.locationSettings == null -> CircularProgressIndicator()

                state.locationSettings != LocationSettingsState.Satisfied -> {
                    Explanation(stringResource(R.string.setup_gps_off_title), stringResource(R.string.setup_gps_off_body))
                    val settings = state.locationSettings
                    Button(onClick = {
                        if (settings is LocationSettingsState.Resolvable) {
                            settingsLauncher.launch(IntentSenderRequest.Builder(settings.resolution).build())
                        } else {
                            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        }
                    }) { Text(stringResource(R.string.action_enable_location)) }
                }

                else -> {
                    GpsSignalIndicator(state.signal, state.accuracyMeters, large = true)
                    Text(
                        stringResource(if (state.signal.allowsStart) R.string.setup_signal_ready else R.string.setup_signal_waiting),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                        Text(
                            stringResource(R.string.setup_notifications_off),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                        OutlinedButton(onClick = ::openAppSettings) { Text(stringResource(R.string.action_open_settings)) }
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = viewModel::start,
                        enabled = state.canStart,
                        modifier = Modifier.fillMaxWidth().height(72.dp),
                    ) {
                        if (state.starting) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Text(
                                stringResource(if (state.isResume) R.string.action_resume else R.string.action_start),
                                style = MaterialTheme.typography.headlineSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Explanation(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    Text(body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
}
