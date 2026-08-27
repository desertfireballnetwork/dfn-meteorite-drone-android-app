package au.edu.fireballs.stage4.ui.screen.stage4map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapbox.geojson.Point
import com.mapbox.maps.dsl.cameraOptions
import com.mapbox.maps.extension.compose.animation.viewport.MapViewportState
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import kotlinx.coroutines.delay

private const val LOCATION_MESSAGE_AUTO_DISMISS_MS = 4_000L

@Composable
fun Stage4MapScreen(
    surveyId: Long,
    onAuthExpired: () -> Unit,
    viewModel: Stage4MapViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val mapViewportState = rememberMapViewportState()
    val locationPermission = rememberLocationPermission()
    var positionedSurveyId by rememberSaveable { mutableStateOf<Long?>(null) }

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

        is Stage4MapUiState.Loaded ->
            LoadedMap(
                loaded = state,
                mapViewportState = mapViewportState,
                locationPermission = locationPermission,
                surveyPositioned = positionedSurveyId == state.state.survey.id,
                onSurveyPositioned = { positionedSurveyId = state.state.survey.id },
            )

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
private fun LoadedMap(
    loaded: Stage4MapUiState.Loaded,
    mapViewportState: MapViewportState,
    locationPermission: LocationPermissionUiState,
    surveyPositioned: Boolean,
    onSurveyPositioned: () -> Unit,
) {
    val context = LocalContext.current

    PositionSurveyCamera(
        loaded = loaded,
        mapViewportState = mapViewportState,
        surveyPositioned = surveyPositioned,
        onSurveyPositioned = onSurveyPositioned,
    )

    var locating by remember { mutableStateOf(false) }
    var locationMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(loaded.userMessage) {
        loaded.userMessage?.let { locationMessage = it }
    }

    LaunchedEffect(locationMessage) {
        if (locationMessage != null) {
            delay(LOCATION_MESSAGE_AUTO_DISMISS_MS)
            locationMessage = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MapHost(
            mapViewportState = mapViewportState,
            locationPermissionGranted = locationPermission.locationPermissionGranted,
            polygons = loaded.state.surveyedAreas,
            tilesetId = loaded.state.survey.tilesetId,
            base = loaded.state.base,
        )

        MapTopOverlay(
            loaded = loaded,
            locationPermission = locationPermission,
            locating = locating,
            locationMessage = locationMessage,
            onDismissMessage = { locationMessage = null },
        )

        RecenterButton(
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(OVERLAY_PADDING_DP.dp),
            onClick = {
                recenterToUser(
                    context = context,
                    mapViewportState = mapViewportState,
                    onLocatingChanged = { locating = it },
                    onMessage = { locationMessage = it },
                )
            },
        )
    }
}

@Composable
private fun PositionSurveyCamera(
    loaded: Stage4MapUiState.Loaded,
    mapViewportState: MapViewportState,
    surveyPositioned: Boolean,
    onSurveyPositioned: () -> Unit,
) {
    LaunchedEffect(loaded.state.survey.id) {
        if (!surveyPositioned) {
            onSurveyPositioned()
            loaded.cameraTarget?.let { target ->
                mapViewportState.setCameraOptions(
                    cameraOptions {
                        center(Point.fromLngLat(target.longitude, target.latitude))
                        zoom(target.zoom)
                    },
                )
            }
        }
    }
}
