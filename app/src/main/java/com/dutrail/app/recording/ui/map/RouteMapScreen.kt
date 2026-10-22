package com.dutrail.app.recording.ui.map

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dutrail.app.R
import com.dutrail.app.core.ui.CenteredMessage
import com.dutrail.app.recording.data.RecordingDao
import com.dutrail.app.recording.data.RoutePoint
import com.dutrail.app.recording.service.RecordingManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RouteMapViewModel @Inject constructor(manager: RecordingManager, dao: RecordingDao) : ViewModel() {
    /** null = não há gravação ativa. Atualiza no máximo a cada 3 s: a rota inteira é redesenhada a cada emissão. */
    val route: StateFlow<List<RoutePoint>?> = manager.active
        .map { it?.recordingId }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else dao.observeRoute(id) }
        .conflate()
        .transform {
            emit(it)
            delay(3_000)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteMapScreen(onBack: () -> Unit, viewModel: RouteMapViewModel = hiltViewModel()) {
    val route by viewModel.route.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.route_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        val points = route
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (points == null) {
                CenteredMessage(stringResource(R.string.route_no_recording))
            } else {
                RouteMap(points, Modifier.fillMaxSize())
                if (points.isEmpty()) {
                    Text(
                        stringResource(R.string.route_waiting_points),
                        modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)).padding(8.dp),
                    )
                }
                // Atribuição sempre visível, como pede a política do OSM.
                Text(
                    MapTiles.attribution,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Black,
                    modifier = Modifier.align(Alignment.BottomEnd).background(Color.White.copy(alpha = 0.8f)).padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun RouteMap(points: List<RoutePoint>, modifier: Modifier) {
    val context = LocalContext.current
    val routeColor = MaterialTheme.colorScheme.primary.toArgb()
    val mapView = remember { createMapView(context) }
    var style by remember { mutableStateOf<Style?>(null) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var cameraPlaced by remember { mutableStateOf(false) }

    MapViewLifecycle(mapView)

    AndroidView(
        factory = {
            mapView.apply {
                getMapAsync { mapLibreMap ->
                    mapLibreMap.uiSettings.isLogoEnabled = false
                    mapLibreMap.uiSettings.isAttributionEnabled = false
                    mapLibreMap.setStyle(Style.Builder().fromJson(MapTiles.styleJson())) { loaded ->
                        loaded.addRouteLayers(routeColor)
                        style = loaded
                    }
                    map = mapLibreMap
                }
            }
        },
        modifier = modifier,
    )

    LaunchedEffect(style, points) {
        val loaded = style ?: return@LaunchedEffect
        loaded.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(lineGeoJson(points))
        loaded.getSourceAs<GeoJsonSource>(POSITION_SOURCE)?.setGeoJson(pointGeoJson(points.lastOrNull()))
        val currentMap = map
        if (!cameraPlaced && currentMap != null && points.isNotEmpty()) {
            currentMap.moveCamera(cameraFor(points))
            cameraPlaced = true
        }
    }
}

private fun createMapView(context: Context): MapView {
    MapTiles.ensureInitialized(context)
    return MapView(context).apply { onCreate(null) }
}

/** Repassa o ciclo de vida da tela ao MapView e o destrói quando a tela sai da composição. */
@Composable
private fun MapViewLifecycle(mapView: MapView) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        var started = false
        var resumed = false
        fun moveTo(state: Lifecycle.State) {
            val shouldStart = state.isAtLeast(Lifecycle.State.STARTED)
            val shouldResume = state.isAtLeast(Lifecycle.State.RESUMED)
            if (shouldStart && !started) mapView.onStart().also { started = true }
            if (shouldResume && !resumed) mapView.onResume().also { resumed = true }
            if (!shouldResume && resumed) mapView.onPause().also { resumed = false }
            if (!shouldStart && started) mapView.onStop().also { started = false }
        }
        val observer = LifecycleEventObserver { _, _ -> moveTo(lifecycle.currentState) }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            moveTo(Lifecycle.State.CREATED)
            mapView.onDestroy()
        }
    }
}

private fun Style.addRouteLayers(color: Int) {
    addSource(GeoJsonSource(ROUTE_SOURCE))
    addSource(GeoJsonSource(POSITION_SOURCE))
    addLayer(
        LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
            PropertyFactory.lineColor(color),
            PropertyFactory.lineWidth(5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    addLayer(
        CircleLayer(POSITION_LAYER, POSITION_SOURCE).withProperties(
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleColor(color),
            PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
            PropertyFactory.circleStrokeWidth(2f),
        ),
    )
}

private fun cameraFor(points: List<RoutePoint>) =
    if (points.distinct().size < 2) {
        CameraUpdateFactory.newLatLngZoom(LatLng(points.last().latitude, points.last().longitude), 16.0)
    } else {
        val bounds = LatLngBounds.Builder().apply { points.forEach { include(LatLng(it.latitude, it.longitude)) } }.build()
        CameraUpdateFactory.newLatLngBounds(bounds, 64)
    }

private fun lineGeoJson(points: List<RoutePoint>): String =
    if (points.size < 2) {
        EMPTY_COLLECTION
    } else {
        points.joinToString(",", prefix = """{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[""", postfix = "]}}") {
            "[${it.longitude},${it.latitude}]"
        }
    }

private fun pointGeoJson(point: RoutePoint?): String =
    if (point == null) {
        EMPTY_COLLECTION
    } else {
        """{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[${point.longitude},${point.latitude}]}}"""
    }

private const val EMPTY_COLLECTION = """{"type":"FeatureCollection","features":[]}"""
private const val ROUTE_SOURCE = "route"
private const val ROUTE_LAYER = "route-line"
private const val POSITION_SOURCE = "position"
private const val POSITION_LAYER = "position-dot"
