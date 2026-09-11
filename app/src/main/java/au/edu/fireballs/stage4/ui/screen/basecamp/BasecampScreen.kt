package au.edu.fireballs.stage4.ui.screen.basecamp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Polyline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import au.edu.fireballs.stage4.domain.model.Claim
import au.edu.fireballs.stage4.domain.model.GeoCoordinate
import au.edu.fireballs.stage4.domain.model.Stage4Candidate
import au.edu.fireballs.stage4.domain.model.Stage4State
import au.edu.fireballs.stage4.domain.model.resolveInitialCamera
import au.edu.fireballs.stage4.ui.screen.stage4map.LayerToggleState
import au.edu.fireballs.stage4.ui.screen.stage4map.marker.CandidateMarkers
import com.mapbox.bindgen.Value
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
import kotlinx.coroutines.launch

private const val BASE_STYLE_URI = "mapbox://styles/mapbox/standard-satellite"
private const val POLYGON_SOURCE_ID = "claim-polygon-source"
private const val POLYGON_FILL_LAYER = "claim-polygon-fill"
private const val POLYGON_STROKE_LAYER = "claim-polygon-stroke"
private const val POLYGON_VERTEX_LAYER = "claim-polygon-vertices"
private const val POLYGON_COLOR = "#007bff"
private const val POLYGON_FILL_OPACITY = 0.2
private const val POLYGON_STROKE_WIDTH = 2.0
private const val VERTEX_RADIUS = 6.0
private const val VERTEX_STROKE_WIDTH = 2.0
private const val CLAIM_LIST_HEIGHT = 240

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BasecampScreen(
    surveyId: Long,
    onAuthExpired: () -> Unit,
    viewModel: BasecampViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

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
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Basecamp") },
                actions = {
                    when (val state = uiState) {
                        is BasecampUiState.Loaded -> {
                            BasecampToolbarActions(
                                drawing = state.polygonVertices != null,
                                mineOnly = state.mineOnly,
                                isRefreshing = state.isRefreshing,
                                onTogglePolygon = {
                                    if (state.polygonVertices != null) {
                                        viewModel.cancelPolygon()
                                    } else {
                                        viewModel.startPolygon()
                                    }
                                },
                                onToggleMine = { viewModel.setMineFilter(!state.mineOnly) },
                                onRefresh = viewModel::refresh,
                            )
                        }

                        else -> Unit
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
}

@Composable
private fun BasecampToolbarActions(
    drawing: Boolean,
    mineOnly: Boolean,
    isRefreshing: Boolean,
    onTogglePolygon: () -> Unit,
    onToggleMine: () -> Unit,
    onRefresh: () -> Unit,
) {
    FilterChip(
        selected = drawing,
        onClick = onTogglePolygon,
        label = { Text(text = if (drawing) "Drawing" else "Polygon") },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Polyline,
                contentDescription = null,
            )
        },
    )
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

    key(vertices.size) {
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
            circleColor = ColorValue(Value(POLYGON_COLOR))
            circleStrokeWidth = DoubleValue(VERTEX_STROKE_WIDTH)
            circleStrokeColor = ColorValue(Value("#ffffff"))
        }
    }
}

private fun polygonData(
    vertices: List<GeoCoordinate>,
    points: List<Point>,
): GeoJSONData =
    GeoJSONData(
        if (vertices.size >= 3) {
            Polygon.fromLngLats(listOf(points))
        } else {
            LineString.fromLngLats(points)
        },
    )

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
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(CLAIM_LIST_HEIGHT.dp),
    ) {
        Text(
            text = "Claims (${claims.size})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (claims.isEmpty()) {
            Text(
                text = "No claims",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(claims, key = { it.inferenceResultId }) { claim ->
                    ClaimRow(
                        claim = claim,
                        onRelease = {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    "Releasing candidate ${claim.inferenceResultId}",
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
