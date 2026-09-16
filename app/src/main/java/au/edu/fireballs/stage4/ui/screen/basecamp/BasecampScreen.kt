package au.edu.fireballs.stage4.ui.screen.basecamp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Polyline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import au.edu.fireballs.stage4.domain.model.Claim
import au.edu.fireballs.stage4.domain.model.GeoCoordinate
import au.edu.fireballs.stage4.domain.model.Stage4Candidate
import au.edu.fireballs.stage4.domain.model.Stage4State
import au.edu.fireballs.stage4.domain.model.resolveInitialCamera
import au.edu.fireballs.stage4.ui.screen.stage4map.BaseMarker
import au.edu.fireballs.stage4.ui.screen.stage4map.LayerToggleState
import au.edu.fireballs.stage4.ui.screen.stage4map.marker.CandidateMarkers
import com.mapbox.bindgen.Value
import com.mapbox.geojson.Feature
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.geojson.Polygon
import com.mapbox.maps.dsl.cameraOptions
import com.mapbox.maps.extension.compose.DisposableMapEffect
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.MapboxMapComposable
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.compose.style.ColorValue
import com.mapbox.maps.extension.compose.style.DoubleValue
import com.mapbox.maps.extension.compose.style.MapStyle
import com.mapbox.maps.extension.compose.style.layers.generated.CircleLayer
import com.mapbox.maps.extension.compose.style.layers.generated.FillLayer
import com.mapbox.maps.extension.compose.style.layers.generated.LineLayer
import com.mapbox.maps.extension.compose.style.sources.GeoJSONData
import com.mapbox.maps.extension.compose.style.sources.generated.rememberGeoJsonSourceState
import com.mapbox.maps.plugin.gestures.OnMapClickListener
import com.mapbox.maps.plugin.gestures.addOnMapClickListener
import com.mapbox.maps.plugin.gestures.removeOnMapClickListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private const val BASE_STYLE_URI = "mapbox://styles/mapbox/standard-satellite"
private const val POLYGON_SOURCE_ID = "claim-polygon-source"
private const val POLYGON_FILL_LAYER = "claim-polygon-fill"
private const val POLYGON_STROKE_LAYER = "claim-polygon-stroke"
private const val POLYGON_VERTEX_LAYER = "claim-polygon-vertices"
private const val POLYGON_COLOR = "#007bff"
private const val POLYGON_FILL_OPACITY = 0.3
private const val POLYGON_STROKE_WIDTH = 4.0
private const val VERTEX_RADIUS = 8.0
private const val VERTEX_STROKE_WIDTH = 3.0
private const val CLAIM_LIST_HEIGHT = 240
private const val CLAIM_LIST_HEADER_HEIGHT = 28
private const val CAR_LOCATION_SUCCESS_MS = 2_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BasecampScreen(
    surveyId: Long,
    onAuthExpired: () -> Unit,
    viewModel: BasecampViewModel = hiltViewModel(),
    preDownloadViewModel: PreDownloadViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val preDownloadState by preDownloadViewModel.uiState.collectAsStateWithLifecycle()
    val isSettingCarLocation by viewModel.isSettingCarLocation.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showDownloadDialog by rememberSaveable { mutableStateOf(false) }
    var carLocationSet by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        viewModel.openSurvey(surveyId)
        onPauseOrDispose { }
    }

    LaunchedEffect(uiState) {
        if (uiState is BasecampUiState.AuthExpired) {
            onAuthExpired()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is BasecampEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
                BasecampEvent.CarLocationSet -> {
                    carLocationSet = true
                    snackbarHostState.showSnackbar("Car location set")
                }
            }
        }
    }

    LaunchedEffect(carLocationSet) {
        if (carLocationSet) {
            delay(CAR_LOCATION_SUCCESS_MS)
            carLocationSet = false
        }
    }

    LaunchedEffect(showDownloadDialog) {
        if (showDownloadDialog) {
            preDownloadViewModel.openSurvey(surveyId)
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(text = "Basecamp") },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (val state = uiState) {
                        is BasecampUiState.Loaded -> {
                            BasecampToolbarActions(
                                drawing = state.polygonVertices != null,
                                mineOnly = state.mineOnly,
                                isRefreshing = state.isRefreshing,
                                showSuccess = carLocationSet,
                                submitting = isSettingCarLocation,
                                onTogglePolygon = {
                                    if (state.polygonVertices != null) {
                                        viewModel.cancelPolygon()
                                    } else {
                                        viewModel.startPolygon()
                                    }
                                },
                                onToggleMine = {
                                    viewModel.setMineFilter(!state.mineOnly)
                                },
                                onRefresh = viewModel::refresh,
                                onDownload = { showDownloadDialog = true },
                                onSetLocation = viewModel::setCarLocation,
                                onMessage = { message ->
                                    scope.launch {
                                        snackbarHostState.showSnackbar(message)
                                    }
                                },
                            )
                        }

                        else -> Unit
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets =
            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { paddingValues ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) {
            when (val state = uiState) {
                is BasecampUiState.Loading -> LoadingPlaceholder()
                is BasecampUiState.Error -> ErrorPlaceholder(state.message, viewModel::refresh)
                is BasecampUiState.AuthExpired -> Unit
                is BasecampUiState.Loaded -> {
                    LaunchedEffect(state.userMessage) {
                        state.userMessage?.let { message ->
                            snackbarHostState.showSnackbar(message)
                            viewModel.userMessageShown()
                        }
                    }
                    BasecampContent(
                        state = state,
                        onMarkerClick = { candidate ->
                            if (state.polygonVertices == null) {
                                viewModel.onCandidateTap(candidate.inferenceResultId)
                            }
                        },
                        onMapClick = viewModel::onPolygonVertex,
                        onCommitPolygon = viewModel::commitPolygon,
                        onCancelPolygon = viewModel::cancelPolygon,
                        onReleaseClaim = viewModel::releaseClaim,
                        snackbarHostState = snackbarHostState,
                        scope = scope,
                    )
                }
            }
        }
    }

    if (showDownloadDialog) {
        DownloadDialog(
            state = preDownloadState,
            onDismiss = { showDownloadDialog = false },
            onStart = preDownloadViewModel::startDownload,
            onCancel = preDownloadViewModel::cancel,
        )
    }
}

@Composable
private fun BasecampToolbarActions(
    drawing: Boolean,
    mineOnly: Boolean,
    isRefreshing: Boolean,
    showSuccess: Boolean,
    submitting: Boolean,
    onTogglePolygon: () -> Unit,
    onToggleMine: () -> Unit,
    onRefresh: () -> Unit,
    onDownload: () -> Unit,
    onSetLocation: (Double, Double) -> Unit,
    onMessage: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconToggleButton(
                checked = drawing,
                onCheckedChange = { onTogglePolygon() },
                modifier =
                    Modifier.semantics {
                        contentDescription =
                            if (drawing) {
                                "Stop drawing polygon"
                            } else {
                                "Draw claim polygon"
                            }
                    },
            ) {
                Icon(
                    imageVector = Icons.Default.Polyline,
                    contentDescription = null,
                    tint =
                        if (drawing) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            LocalContentColor.current
                        },
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            FilterChip(
                selected = mineOnly,
                onClick = onToggleMine,
                label = { Text(text = "Mine") },
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onRefresh, enabled = !isRefreshing) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh",
                )
            }
            IconButton(onClick = onDownload) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "Download for offline",
                )
            }
        }
        SetCarLocationButton(
            showSuccess = showSuccess,
            submitting = submitting,
            onSetLocation = onSetLocation,
            onMessage = onMessage,
        )
    }
}

@Composable
private fun DownloadDialog(
    state: PreDownloadUiState,
    onDismiss: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Download for offline") },
        text = {
            when (state) {
                is PreDownloadUiState.Idle -> Text(text = "Preparing download…")
                is PreDownloadUiState.Ready -> {
                    Column {
                        Text(
                            text = "Claimed candidates: ${state.claimedCandidateCount}",
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Estimated size: ${formatBytes(state.estimatedSizeBytes)}",
                        )
                        if (state.isStale) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Local data is stale — re-download recommended",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = onStart) {
                            Text(text = if (state.isStale) "Re-download" else "Download")
                        }
                    }
                }

                is PreDownloadUiState.Running -> {
                    Column {
                        Text(text = state.phase)
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = {
                                if (state.total > 0) {
                                    state.done.toFloat() / state.total
                                } else {
                                    0f
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${state.done} / ${state.total}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                is PreDownloadUiState.Done -> {
                    Column {
                        Text(text = "Download complete")
                        if (state.reDownloadRecommended) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Re-download recommended — ML task changed",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable(onClick = onStart)
                                        .padding(vertical = 8.dp),
                            )
                        }
                    }
                }

                is PreDownloadUiState.Error -> Text(text = state.message)
                is PreDownloadUiState.Cancelled -> Text(text = "Download cancelled")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Done")
            }
        },
        dismissButton = {
            if (state is PreDownloadUiState.Running) {
                TextButton(onClick = onCancel) {
                    Text(text = "Cancel")
                }
            }
        },
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.ROOT, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.ROOT, "%.1f GB", gb)
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
        Text(text = "Loading basecamp")
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
private fun BasecampContent(
    state: BasecampUiState.Loaded,
    onMarkerClick: (Stage4Candidate) -> Unit,
    onMapClick: (GeoCoordinate) -> Unit,
    onCommitPolygon: () -> Unit,
    onCancelPolygon: () -> Unit,
    onReleaseClaim: (Long) -> Unit,
    snackbarHostState: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val drawing = state.polygonVertices != null
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            BasecampMap(
                state = state.state,
                polygonVertices = state.polygonVertices,
                drawing = drawing,
                onMarkerClick = onMarkerClick,
                onMapClick = onMapClick,
            )
            if (drawing) {
                PolygonControls(
                    vertexCount = state.polygonVertices.size,
                    onCommit = onCommitPolygon,
                    onCancel = onCancelPolygon,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp),
                )
            }
        }
        HorizontalDivider()
        ClaimListPanel(
            claims = state.claims,
            onReleaseClaim = onReleaseClaim,
            snackbarHostState = snackbarHostState,
            scope = scope,
        )
    }
}

@Composable
private fun BasecampMap(
    state: Stage4State,
    polygonVertices: List<GeoCoordinate>?,
    drawing: Boolean,
    onMarkerClick: (Stage4Candidate) -> Unit,
    onMapClick: (GeoCoordinate) -> Unit,
) {
    val mapViewportState = rememberMapViewportState()
    var positionedSurveyId by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(state.survey.id) {
        if (positionedSurveyId != state.survey.id) {
            positionedSurveyId = state.survey.id
            resolveInitialCamera(state)?.let { target ->
                mapViewportState.setCameraOptions(
                    cameraOptions {
                        center(Point.fromLngLat(target.longitude, target.latitude))
                        zoom(target.zoom)
                    },
                )
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MapboxMap(
            modifier = Modifier.fillMaxSize(),
            mapViewportState = mapViewportState,
            style = {
                MapStyle(style = BASE_STYLE_URI)
            },
        ) {
            CandidateMarkers(
                state = state,
                toggleState = LayerToggleState(),
                onMarkerClick = onMarkerClick,
            )
            BaseMarker(base = state.base)
            polygonVertices?.let { vertices ->
                PolygonOverlay(vertices = vertices)
            }
            if (drawing) {
                MapClickHandler(onMapClick = onMapClick)
            }
        }
    }
}

@Composable
@MapboxMapComposable
private fun MapClickHandler(onMapClick: (GeoCoordinate) -> Unit) {
    DisposableMapEffect(onMapClick) { mapView ->
        val listener =
            OnMapClickListener { point ->
                onMapClick(GeoCoordinate(point.latitude(), point.longitude()))
                true
            }
        mapView.mapboxMap.addOnMapClickListener(listener)
        onDispose {
            mapView.mapboxMap.removeOnMapClickListener(listener)
        }
    }
}

@Composable
@MapboxMapComposable
private fun PolygonOverlay(vertices: List<GeoCoordinate>) {
    if (vertices.isEmpty()) return
    val points = vertices.map { Point.fromLngLat(it.longitude, it.latitude) }

    val sourceState =
        rememberGeoJsonSourceState(key = POLYGON_SOURCE_ID) {
            data = polygonData(vertices, points)
        }

    LaunchedEffect(vertices) {
        sourceState.data = polygonData(vertices, points)
    }

    if (vertices.size >= 3) {
        FillLayer(sourceState, POLYGON_FILL_LAYER) {
            fillColor = ColorValue(Value(POLYGON_COLOR))
            fillOpacity = DoubleValue(POLYGON_FILL_OPACITY)
        }
    }
    LineLayer(sourceState, POLYGON_STROKE_LAYER) {
        lineColor = ColorValue(Value(POLYGON_COLOR))
        lineWidth = DoubleValue(POLYGON_STROKE_WIDTH)
    }
    CircleLayer(sourceState, POLYGON_VERTEX_LAYER) {
        circleRadius = DoubleValue(VERTEX_RADIUS)
        circleColor = ColorValue(Value("#ffffff"))
        circleStrokeWidth = DoubleValue(VERTEX_STROKE_WIDTH)
        circleStrokeColor = ColorValue(Value(POLYGON_COLOR))
    }
}

private fun polygonData(
    vertices: List<GeoCoordinate>,
    points: List<Point>,
): GeoJSONData {
    val features = mutableListOf<Feature>()
    points.forEach { point ->
        features += Feature.fromGeometry(point)
    }
    if (vertices.size >= 2) {
        features += Feature.fromGeometry(LineString.fromLngLats(points))
    }
    if (vertices.size >= 3) {
        features += Feature.fromGeometry(Polygon.fromLngLats(listOf(points)))
    }
    return GeoJSONData(features)
}

@Composable
private fun PolygonControls(
    vertexCount: Int,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$vertexCount vertices",
            style = MaterialTheme.typography.labelLarge,
            modifier =
                Modifier
                    .padding(horizontal = 8.dp)
                    .align(Alignment.CenterVertically),
        )
        OutlinedButton(onClick = onCancel) {
            Text(text = "Cancel")
        }
        Button(onClick = onCommit, enabled = vertexCount >= 3) {
            Text(text = "Claim inside")
        }
    }
}

@Composable
private fun ClaimListPanel(
    claims: List<Claim>,
    onReleaseClaim: (Long) -> Unit,
    snackbarHostState: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(CLAIM_LIST_HEADER_HEIGHT.dp)
                    .padding(start = 16.dp, end = 8.dp)
                    .pointerInput(expanded) {
                        detectTapGestures { expanded = !expanded }
                    },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Claims (${claims.size})",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            Icon(
                imageVector =
                    if (expanded) {
                        Icons.Default.ExpandMore
                    } else {
                        Icons.Default.ExpandLess
                    },
                contentDescription =
                    if (expanded) {
                        "Collapse claims"
                    } else {
                        "Expand claims"
                    },
                modifier = Modifier.size(20.dp),
            )
        }
        if (expanded) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height((CLAIM_LIST_HEIGHT - CLAIM_LIST_HEADER_HEIGHT).dp),
            ) {
                if (claims.isEmpty()) {
                    Text(
                        text = "No claims",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier =
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding =
                            PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(claims, key = { it.inferenceResultId }) { claim ->
                            ClaimRow(
                                claim = claim,
                                onRelease = {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            "Releasing candidate " +
                                                claim.inferenceResultId,
                                        )
                                    }
                                    onReleaseClaim(claim.inferenceResultId)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClaimRow(
    claim: Claim,
    onRelease: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Candidate #${claim.inferenceResultId}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = claim.fullName ?: claim.username ?: "user ${claim.userId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (claim.isMe) {
            OutlinedButton(onClick = onRelease) {
                Text(text = "Release")
            }
        }
    }
}
