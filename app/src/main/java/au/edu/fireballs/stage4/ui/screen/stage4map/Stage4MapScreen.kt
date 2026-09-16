package au.edu.fireballs.stage4.ui.screen.stage4map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import au.edu.fireballs.stage4.data.repository.NetworkState
import au.edu.fireballs.stage4.domain.model.Stage4Candidate
import au.edu.fireballs.stage4.domain.model.UserLocation
import au.edu.fireballs.stage4.ui.PendingSyncBadge
import au.edu.fireballs.stage4.ui.screen.candidate.CandidateModal
import com.mapbox.geojson.Point
import com.mapbox.maps.dsl.cameraOptions
import com.mapbox.maps.extension.compose.animation.viewport.MapViewportState
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import kotlinx.coroutines.delay

private const val LOCATION_MESSAGE_AUTO_DISMISS_MS = 4_000L
private const val SELECTED_CANDIDATE_ZOOM = 20.0

@Composable
fun Stage4MapScreen(
    surveyId: Long,
    onAuthExpired: () -> Unit,
    onOpenDownloads: () -> Unit,
    pendingCount: Int,
    viewModel: Stage4MapViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val connectionBannerState by viewModel.connectionBannerState.collectAsStateWithLifecycle()
    val networkState by viewModel.networkState.collectAsStateWithLifecycle()
    val hasOfflineBundle by viewModel.hasOfflineBundle.collectAsStateWithLifecycle()
    val mapViewportState = rememberMapViewportState()
    val locationPermission = rememberLocationPermission()
    var positionedSurveyId by rememberSaveable { mutableStateOf<Long?>(null) }

    LifecycleResumeEffect(Unit) {
        viewModel.openSurvey(surveyId)
        onPauseOrDispose {}
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

        is Stage4MapUiState.Loaded -> {
            var selectedCandidateId by rememberSaveable { mutableStateOf<Long?>(null) }
            var modalCandidateId by rememberSaveable { mutableStateOf<Long?>(null) }
            var selectedUser by remember { mutableStateOf<UserLocation?>(null) }

            val candidates =
                state.state.unprocessedCandidates +
                    state.state.yesMeteorites +
                    state.state.noMeteorites
            val selectedCandidate =
                selectedCandidateId?.let { id ->
                    candidates.firstOrNull { it.inferenceResultId == id }
                }
            val modalCandidate =
                modalCandidateId?.let { id ->
                    candidates.firstOrNull { it.inferenceResultId == id }
                }

            LaunchedEffect(selectedCandidate?.inferenceResultId) {
                selectedCandidate?.geoCentroid?.let { centroid ->
                    mapViewportState.setCameraOptions(
                        cameraOptions {
                            center(Point.fromLngLat(centroid.longitude, centroid.latitude))
                            zoom(SELECTED_CANDIDATE_ZOOM)
                        },
                    )
                }
            }

            LoadedMap(
                loaded = state,
                mapViewportState = mapViewportState,
                locationPermission = locationPermission,
                surveyPositioned = positionedSurveyId == state.state.survey.id,
                syncStatus = syncStatus,
                connectionBannerState = connectionBannerState,
                pendingCount = pendingCount,
                hasOfflineBundle = hasOfflineBundle,
                selectedUser = selectedUser,
                tileUrlPattern =
                    selectedCandidateId?.let {
                        viewModel.candidateTileUrlPattern(state.state.survey.id, it)
                    },
                candidateId = selectedCandidateId,
                onSurveyPositioned = { positionedSurveyId = state.state.survey.id },
                onToggleLayer = viewModel::toggleLayer,
                onSync = viewModel::syncNow,
                onMarkerClick = {
                    if (shouldAllowCandidateSelection(hasOfflineBundle, networkState)) {
                        selectedCandidateId = it.inferenceResultId
                        modalCandidateId = it.inferenceResultId
                    }
                },
                onUserLocationClick = { selectedUser = it },
                onDismissUser = { selectedUser = null },
                onOpenDownloads = onOpenDownloads,
            )

            modalCandidate?.let { candidate ->
                CandidateModal(
                    candidate = candidate,
                    surveyId = state.state.survey.id,
                    detectionTags = state.state.detectionTags,
                    onClose = { modalCandidateId = null },
                    onAuthExpired = onAuthExpired,
                )
            }
        }

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
    syncStatus: SyncStatus,
    connectionBannerState: ConnectionBannerState,
    pendingCount: Int,
    hasOfflineBundle: Boolean,
    selectedUser: UserLocation?,
    tileUrlPattern: String?,
    candidateId: Long?,
    onSurveyPositioned: () -> Unit,
    onToggleLayer: (LayerType, Boolean) -> Unit,
    onSync: () -> Unit,
    onMarkerClick: (Stage4Candidate) -> Unit,
    onUserLocationClick: (UserLocation) -> Unit,
    onDismissUser: () -> Unit,
    onOpenDownloads: () -> Unit,
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
    var locationServicesDisabled by remember { mutableStateOf(false) }

    LocationMessageEffects(
        userMessage = loaded.userMessage,
        locationMessage = locationMessage,
        locationServicesDisabled = locationServicesDisabled,
        onUserMessage = {
            locationServicesDisabled = false
            locationMessage = it
        },
        onAutoDismissed = {
            locationMessage = null
            locationServicesDisabled = false
        },
    )

    Column(modifier = Modifier.fillMaxSize()) {
        ConnectionStatusBanner(
            state = connectionBannerState,
            modifier = Modifier.statusBarsPadding(),
        )

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
        ) {
            MapHost(
                mapViewportState = mapViewportState,
                locationPermissionGranted = locationPermission.locationPermissionGranted,
                state = loaded.state,
                layerToggleState = loaded.layerToggleState,
                onMarkerClick = onMarkerClick,
                onUserLocationClick = onUserLocationClick,
                candidateId = candidateId,
                tileUrlPattern = tileUrlPattern,
            )

            LayerToggleBar(
                state = loaded.layerToggleState,
                onToggle = onToggleLayer,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 64.dp, end = 16.dp),
            )

            MapTopOverlay(
                loaded = loaded,
                locationPermission = locationPermission,
                locating = locating,
                locationMessage = locationMessage,
                locationServicesDisabled = locationServicesDisabled,
                syncStatus = syncStatus,
                onSync = onSync,
                onDismissMessage = {
                    locationMessage = null
                    locationServicesDisabled = false
                },
                onOpenLocationSettings = { openLocationSettings(context) },
            )

            PendingSyncBadge(
                pendingCount = pendingCount,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(start = OVERLAY_PADDING_DP.dp, top = OVERLAY_PADDING_DP.dp),
            )

            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(start = OVERLAY_PADDING_DP.dp, top = 72.dp),
            ) {
                OfflineBundleGuard(
                    hasBundle = hasOfflineBundle,
                    onOpenDownloads = onOpenDownloads,
                )
            }

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
                        onLocationServicesDisabled = { locationServicesDisabled = it },
                    )
                },
            )

            selectedUser?.let { user ->
                UserLocationPopup(
                    user = user,
                    onDismiss = onDismissUser,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun UserLocationPopup(
    user: UserLocation,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user.fullName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "@${user.username}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "Last seen: ${user.processedAt}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = "Close")
            }
        }
    }
}

@Composable
private fun LocationMessageEffects(
    userMessage: String?,
    locationMessage: String?,
    locationServicesDisabled: Boolean,
    onUserMessage: (String) -> Unit,
    onAutoDismissed: () -> Unit,
) {
    LaunchedEffect(userMessage) {
        userMessage?.let(onUserMessage)
    }

    LaunchedEffect(locationMessage, locationServicesDisabled) {
        if (locationMessage != null && !locationServicesDisabled) {
            delay(LOCATION_MESSAGE_AUTO_DISMISS_MS)
            onAutoDismissed()
        }
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

internal fun shouldAllowCandidateSelection(
    hasOfflineBundle: Boolean,
    networkState: NetworkState,
): Boolean = hasOfflineBundle || networkState == NetworkState.Online
