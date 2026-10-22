package com.dutrail.app.activities.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.dutrail.app.R
import com.dutrail.app.activities.data.ActivitiesRepository
import com.dutrail.app.activities.domain.Activity
import com.dutrail.app.core.common.Formatters
import com.dutrail.app.core.ui.CenteredMessage
import com.dutrail.app.core.ui.ErrorKind
import com.dutrail.app.core.ui.LoadingBox
import com.dutrail.app.core.ui.message
import com.dutrail.app.core.ui.retryLabel
import com.dutrail.app.core.ui.toErrorKind
import com.dutrail.app.navigation.ActivityDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DetailState {
    data object Loading : DetailState
    data class Loaded(val activity: Activity) : DetailState
    data class Failed(val error: ErrorKind) : DetailState
}

@HiltViewModel
class ActivityDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ActivitiesRepository,
) : ViewModel() {
    private val id = savedStateHandle.toRoute<ActivityDetailRoute>().id

    private val _state = MutableStateFlow<DetailState>(DetailState.Loading)
    val state: StateFlow<DetailState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = DetailState.Loading
        viewModelScope.launch {
            _state.value = runCatching { repository.activity(id) }
                .fold({ DetailState.Loaded(it) }, { DetailState.Failed(it.toErrorKind()) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityDetailScreen(onBack: () -> Unit, viewModel: ActivityDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text((state as? DetailState.Loaded)?.activity?.name ?: stringResource(R.string.activity_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)
        when (val current = state) {
            DetailState.Loading -> LoadingBox(modifier)
            is DetailState.Failed -> CenteredMessage(
                title = if (current.error == ErrorKind.NOT_FOUND) stringResource(R.string.activity_not_found) else current.error.message(),
                actionLabel = if (current.error == ErrorKind.NOT_FOUND) null else retryLabel(),
                onAction = viewModel::load,
                modifier = modifier,
            )
            is DetailState.Loaded -> ActivityDetails(current.activity, modifier)
        }
    }
}

@Composable
private fun ActivityDetails(activity: Activity, modifier: Modifier) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(formatDateTime(activity.startedAt), style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider()
        DetailRow(
            stringResource(R.string.metric_distance),
            activity.distanceMeters?.let { stringResource(R.string.value_km, Formatters.distanceKm(it)) } ?: "—",
        )
        DetailRow(stringResource(R.string.metric_moving_time), activity.movingTimeSeconds?.let(Formatters::duration) ?: "—")
        DetailRow(stringResource(R.string.metric_elapsed_time), Formatters.duration(activity.elapsedTimeSeconds))
        DetailRow(stringResource(R.string.metric_avg_pace), stringResource(R.string.value_pace, Formatters.pace(activity.averagePaceSecondsPerKm)))
        activity.elevationGainMeters?.let { DetailRow(stringResource(R.string.metric_elevation_gain), stringResource(R.string.value_meters, it.toInt())) }
        activity.calories?.let { DetailRow(stringResource(R.string.metric_calories), stringResource(R.string.value_kcal, it)) }
        activity.averageHeartRateBpm?.let { DetailRow(stringResource(R.string.metric_avg_hr), stringResource(R.string.value_bpm, it)) }
        activity.maxHeartRateBpm?.let { DetailRow(stringResource(R.string.metric_max_hr), stringResource(R.string.value_bpm, it)) }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall)
    }
}
