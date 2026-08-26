package au.edu.fireballs.stage4.ui.screen.stage4map

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import au.edu.fireballs.stage4.domain.model.GeoCoordinate
import com.google.android.gms.location.LocationServices
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraBoundsOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.dsl.cameraOptions
import com.mapbox.maps.extension.compose.MapEffect
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.MapViewportState
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.compose.style.MapStyle
import com.mapbox.maps.extension.compose.style.projection.generated.Projection
import com.mapbox.maps.extension.compose.style.rememberStyleState
import com.mapbox.maps.plugin.Plugin
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.locationcomponent.LocationComponentPlugin

private const val BASE_STYLE_URI = "mapbox://styles/mapbox/standard-satellite"
private const val MAX_CAMERA_ZOOM = 25.0
private const val LOCATION_ANIMATION_DURATION_MS = 500L
private const val OVERLAY_PADDING_DP = 12

@Composable
fun Stage4MapScreen(
    surveyId: Long,
    onAuthExpired: () -> Unit,
    viewModel: Stage4MapViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.openSurvey(surveyId)
        onPauseOrDispose {
        }
    }

    LaunchedEffect(uiState) {
        if (uiState is Stage4MapUiState.AuthExpired) {
            onAuthExpired()
        }
    }

    when (val state = uiState) {
        is Stage4MapUiState.Loading -> LoadingPlaceholder()
        is Stage4MapUiState.Error ->
            ErrorPlaceholder(
                message = state.message,
                onRetry = viewModel::retry,
            )

        is Stage4MapUiState.Loaded -> LoadedMap(loaded = state)
        is Stage4MapUiState.AuthExpired -> Unit
    }
}

@Composable
private fun LoadingPlaceholder() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Loading survey map")
    }
}

@Composable
private fun ErrorPlaceholder(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text(text = "Retry")
        }
    }
}

@Composable
private fun LoadedMap(loaded: Stage4MapUiState.Loaded) {
    val context = LocalContext.current
    val mapViewportState = rememberMapViewportState()
    var locationPermissionGranted by remember { mutableStateOf(false) }
    var showLocationDeniedNotice by remember { mutableStateOf(false) }

    RequestLocationPermissions(
        onGranted = { locationPermissionGranted = true },
        onDenied = { showLocationDeniedNotice = true },
    )

    val cameraTarget = loaded.cameraTarget
    LaunchedEffect(cameraTarget) {
        cameraTarget?.let { target ->
            mapViewportState.setCameraOptions(
                cameraOptions {
                    center(Point.fromLngLat(target.longitude, target.latitude))
                    zoom(target.zoom)
                },
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MapHost(
            mapViewportState = mapViewportState,
            locationPermissionGranted = locationPermissionGranted,
            polygons = loaded.state.surveyedAreas,
            tilesetId = loaded.state.survey.tilesetId,
            base = loaded.state.base,
        )

        MapTopOverlay(
            loaded = loaded,
            showLocationDeniedNotice = showLocationDeniedNotice,
            onDismissNotice = { showLocationDeniedNotice = false },
        )

        if (locationPermissionGranted) {
            RecenterButton(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(OVERLAY_PADDING_DP.dp),
                onClick = { flyToLastKnownLocation(context, mapViewportState) },
            )
        }
    }
}

@Composable
private fun MapTopOverlay(
    loaded: Stage4MapUiState.Loaded,
    showLocationDeniedNotice: Boolean,
    onDismissNotice: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .statusBarsPadding()
                .padding(OVERLAY_PADDING_DP.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SurveyInfoChip(loaded = loaded)
        if (showLocationDeniedNotice) {
            Spacer(modifier = Modifier.height(8.dp))
            LocationDeniedNotice(onDismiss = onDismissNotice)
        }
    }
}

@Composable
private fun RequestLocationPermissions(
    onGranted: () -> Unit,
    onDenied: () -> Unit,
) {
    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissions ->
            if (permissions.values.any { it }) {
                onGranted()
            } else {
                onDenied()
            }
        }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }
}

@Composable
private fun RecenterButton(
    modifier: Modifier,
    onClick: () -> Unit,
) {
    FloatingActionButton(onClick = onClick, modifier = modifier) {
        Icon(imageVector = Icons.Filled.LocationOn, contentDescription = "My location")
    }
}

@Composable
private fun MapHost(
    mapViewportState: MapViewportState,
    locationPermissionGranted: Boolean,
    polygons: List<List<List<Double>>>,
    tilesetId: String?,
    base: GeoCoordinate?,
) {
    val styleState =
        rememberStyleState {
            projection = Projection.GLOBE
        }

    MapEffect(Unit) { mapView ->
        mapView.mapboxMap.setBounds(
            CameraBoundsOptions.Builder().maxZoom(MAX_CAMERA_ZOOM).build(),
        )
    }

    if (locationPermissionGranted) {
        MapEffect(locationPermissionGranted) { mapView ->
            enableLocationPuck(mapView)
        }
    }

    MapboxMap(
        modifier = Modifier.fillMaxSize(),
        mapViewportState = mapViewportState,
        style = {
            MapStyle(
                style = BASE_STYLE_URI,
                styleState = styleState,
            )
        },
    ) {
        SurveyedAreaOverlay(
            polygons = polygons,
            tilesetId = tilesetId,
        )
        BaseMarker(base = base)
    }
}

@Composable
private fun SurveyInfoChip(loaded: Stage4MapUiState.Loaded) {
    val state = loaded.state
    val totalCandidates =
        state.unprocessedCandidates.size + state.yesMeteorites.size + state.noMeteorites.size

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = state.survey.eventId,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "$totalCandidates candidates loaded",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun LocationDeniedNotice(onDismiss: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.errorContainer,
        onClick = onDismiss,
    ) {
        Text(
            text = "Location permission declined",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

private fun enableLocationPuck(mapView: MapView) {
    val locationPlugin =
        mapView.getPlugin(Plugin.MAPBOX_LOCATION_COMPONENT_PLUGIN_ID) as? LocationComponentPlugin
    locationPlugin?.updateSettings {
        enabled = true
        pulsingEnabled = true
    }
}

private fun flyToLastKnownLocation(
    context: Context,
    viewportState: MapViewportState,
) {
    val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    fusedLocationClient.lastLocation.addOnSuccessListener { location ->
        location?.let {
            viewportState.flyTo(
                cameraOptions {
                    center(Point.fromLngLat(it.longitude, it.latitude))
                },
                MapAnimationOptions.mapAnimationOptions {
                    duration(LOCATION_ANIMATION_DURATION_MS)
                },
            )
        }
    }
}
