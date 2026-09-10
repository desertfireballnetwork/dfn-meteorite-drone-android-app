package au.edu.fireballs.stage4.ui.screen.candidate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import au.edu.fireballs.stage4.domain.model.GeoCoordinate
import au.edu.fireballs.stage4.domain.model.Stage4Candidate
import java.util.Locale
import kotlin.math.abs

internal fun formatCoordinates(coordinate: GeoCoordinate?): String {
    if (coordinate == null) return "N/A"
    val latHemisphere = if (coordinate.latitude >= 0.0) "N" else "S"
    val lonHemisphere = if (coordinate.longitude >= 0.0) "E" else "W"
    val lat = "%.6f".format(Locale.US, abs(coordinate.latitude))
    val lon = "%.6f".format(Locale.US, abs(coordinate.longitude))
    return "$lat°$latHemisphere, $lon°$lonHemisphere"
}

private fun Modifier.pointerHitTestEnabled(enabled: Boolean): Modifier =
    if (enabled) {
        this
    } else {
        this.pointerInput(enabled) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                }
            }
        }
    }

@Composable
fun CandidateModal(
    candidate: Stage4Candidate,
    surveyId: Long,
    onClose: () -> Unit,
    viewModel: CandidateViewModel = hiltViewModel(),
) {
    LaunchedEffect(candidate.inferenceResultId, surveyId) {
        viewModel.initialize(candidate, surveyId)
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    CandidateModal(
        candidate = candidate,
        uiState = uiState,
        onClose = onClose,
        onSelectMode = viewModel::setViewMode,
    )
}

@Composable
fun CandidateModal(
    candidate: Stage4Candidate,
    uiState: CandidateUiState?,
    onClose: () -> Unit,
    onSelectMode: (CandidateViewMode) -> Unit,
) {
    val activeCandidate = uiState?.candidate ?: candidate
    val currentMode = uiState?.viewMode ?: CandidateViewMode.MAP
    val tileUrlPattern = uiState?.tileUrlPattern ?: ""
    val imageUrl = uiState?.croppedImageUrl ?: ""

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            topBar = {
                CandidateModalHeader(
                    candidate = activeCandidate,
                    currentMode = currentMode,
                    onSelectMode = onSelectMode,
                    onClose = onClose,
                )
            },
        ) { innerPadding ->
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
            ) {
                val isMapActive = currentMode == CandidateViewMode.MAP
                val isImageActive = currentMode == CandidateViewMode.IMAGE

                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .zIndex(if (isMapActive) 1f else 0f)
                            .graphicsLayer {
                                alpha = if (isMapActive) 1f else 0f
                            }.pointerHitTestEnabled(isMapActive),
                ) {
                    CandidateMap(
                        candidate = activeCandidate,
                        tileUrlPattern = tileUrlPattern,
                    )
                }

                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .zIndex(if (isImageActive) 1f else 0f)
                            .graphicsLayer {
                                alpha = if (isImageActive) 1f else 0f
                            }.pointerHitTestEnabled(isImageActive),
                ) {
                    CandidateImageView(
                        candidate = activeCandidate,
                        imageModel = imageUrl,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun CandidateModalHeader(
    candidate: Stage4Candidate,
    currentMode: CandidateViewMode,
    onSelectMode: (CandidateViewMode) -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Candidate #${candidate.inferenceResultId}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = formatCoordinates(candidate.geoCentroid),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Confidence: ${"%.2f".format(Locale.US, candidate.confidence)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TabRow(
                selectedTabIndex =
                    when (currentMode) {
                        CandidateViewMode.MAP -> 0
                        CandidateViewMode.IMAGE -> 1
                    },
                modifier =
                    Modifier
                        .width(180.dp)
                        .testTag("candidate-view-toggle"),
            ) {
                Tab(
                    selected = currentMode == CandidateViewMode.MAP,
                    onClick = { onSelectMode(CandidateViewMode.MAP) },
                    text = { Text("Map") },
                )
                Tab(
                    selected = currentMode == CandidateViewMode.IMAGE,
                    onClick = { onSelectMode(CandidateViewMode.IMAGE) },
                    text = { Text("Image") },
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = onClose,
                modifier = Modifier.testTag("candidate-modal-close-button"),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                )
            }
        }
    }
}
