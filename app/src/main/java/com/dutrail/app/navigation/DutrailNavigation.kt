package com.dutrail.app.navigation

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dutrail.app.R
import com.dutrail.app.activities.ui.ActivityDetailScreen
import com.dutrail.app.activities.ui.ActivityListScreen
import com.dutrail.app.auth.data.AuthRepository
import com.dutrail.app.auth.ui.LoginScreen
import com.dutrail.app.auth.ui.SignupScreen
import com.dutrail.app.core.ui.AppMessage
import com.dutrail.app.core.ui.AppMessages
import com.dutrail.app.core.ui.LoadingBox
import com.dutrail.app.recording.service.RecordingManager
import com.dutrail.app.recording.ui.RecordingResultScreen
import com.dutrail.app.recording.ui.RecordingScreen
import com.dutrail.app.recording.ui.RecordingSetupScreen
import com.dutrail.app.recording.ui.map.RouteMapScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface RootState {
    data object Loading : RootState
    data object LoggedOut : RootState
    data object LoggedIn : RootState
}

@HiltViewModel
class RootViewModel @Inject constructor(
    authRepository: AuthRepository,
    private val recordingManager: RecordingManager,
    appMessages: AppMessages,
) : ViewModel() {
    val state: StateFlow<RootState> = authRepository.isLoggedIn
        .map { if (it) RootState.LoggedIn else RootState.LoggedOut }
        .stateIn(viewModelScope, SharingStarted.Eagerly, RootState.Loading)

    val messages: Flow<AppMessage> = appMessages.messages

    /** Ao abrir o app com uma gravação rodando no serviço, vai direto para o relógio. */
    fun isRecording(): Boolean = recordingManager.active.value != null
}

/**
 * A sessão decide qual grafo aparece. Login, logout ou refresh recusado trocam o grafo inteiro, sem
 * navegação manual e sem deixar telas autenticadas na pilha.
 */
@Composable
fun DutrailRoot(viewModel: RootViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            val text = when (message) {
                AppMessage.LOGOUT_ALL_NOT_CONFIRMED -> R.string.logout_all_not_confirmed
            }
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }
    when (state) {
        RootState.Loading -> LoadingBox()
        RootState.LoggedOut -> AuthNavHost()
        RootState.LoggedIn -> MainNavHost(isRecording = viewModel::isRecording)
    }
}

@Composable
private fun AuthNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController, startDestination = LoginRoute) {
        composable<LoginRoute> {
            LoginScreen(onCreateAccount = { navController.navigate(SignupRoute) })
        }
        composable<SignupRoute> {
            SignupScreen(onHaveAccount = { navController.popBackStack() })
        }
    }
}

@Composable
private fun MainNavHost(isRecording: () -> Boolean, navController: NavHostController = rememberNavController()) {
    // Decidido uma vez: mudar o startDestination depois recriaria o grafo e perderia a pilha.
    val startOnRecording = rememberSaveable { isRecording() }
    NavHost(navController, startDestination = if (startOnRecording) RecordingRoute else ActivityListRoute) {
        composable<ActivityListRoute> {
            ActivityListScreen(
                onOpenActivity = { navController.navigate(ActivityDetailRoute(it)) },
                onStartRecording = { navController.navigate(RecordingSetupRoute()) },
                onResumeRecording = { navController.navigate(RecordingSetupRoute(resumeRecordingId = it)) },
                onOpenRecordingResult = { navController.navigate(RecordingResultRoute(it)) },
            )
        }
        composable<ActivityDetailRoute> {
            ActivityDetailScreen(onBack = { navController.popBackStack() })
        }
        composable<RecordingSetupRoute> {
            RecordingSetupScreen(
                onBack = { navController.popBackStack() },
                onStarted = {
                    navController.navigate(RecordingRoute) { popUpTo<RecordingSetupRoute> { inclusive = true } }
                },
            )
        }
        composable<RecordingRoute> {
            RecordingScreen(
                onFinished = { id -> navController.navigate(RecordingResultRoute(id)) { popUpTo<RecordingRoute> { inclusive = true } } },
                onExit = { navController.backToList() },
                onShowRoute = { navController.navigate(RouteMapRoute) },
            )
        }
        composable<RouteMapRoute> {
            RouteMapScreen(onBack = { navController.popBackStack() })
        }
        composable<RecordingResultRoute> {
            RecordingResultScreen(
                onOpenActivity = { id ->
                    navController.backToList()
                    navController.navigate(ActivityDetailRoute(id))
                },
                onOpenList = { navController.backToList() },
            )
        }
    }
}

/** Volta para a lista, criando-a se o app abriu direto no relógio. */
private fun NavHostController.backToList() {
    if (!popBackStack(ActivityListRoute, inclusive = false)) {
        navigate(ActivityListRoute) { popUpTo(graph.id) { inclusive = true } }
    }
}
