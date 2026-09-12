package au.edu.fireballs.stage4.ui.screen.candidate

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import au.edu.fireballs.stage4.data.local.PendingPhotoUploadEntity
import au.edu.fireballs.stage4.domain.model.DetectionTag
import au.edu.fireballs.stage4.domain.model.GeoCoordinate
import au.edu.fireballs.stage4.domain.model.Stage4Candidate
import au.edu.fireballs.stage4.ui.theme.dfnMarkerNo
import au.edu.fireballs.stage4.ui.theme.dfnMarkerYes
import coil.compose.AsyncImage
import java.io.File
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
    onAuthExpired: () -> Unit = {},
    detectionTags: List<DetectionTag> = emptyList(),
    viewModel: CandidateViewModel = hiltViewModel(),
) {
    LaunchedEffect(candidate.inferenceResultId, surveyId) {
        viewModel.initialize(candidate, surveyId)
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val verdict by viewModel.verdict.collectAsStateWithLifecycle()
    val detectionTagId by viewModel.detectionTagId.collectAsStateWithLifecycle()
    val photoGallery by viewModel.photoGalleryState.collectAsStateWithLifecycle()

    CandidateModal(
        candidate = candidate,
        uiState = uiState,
        onClose = onClose,
        onSelectMode = viewModel::setViewMode,
        onRetry = viewModel::retryImage,
        onAuthExpired = onAuthExpired,
        verdict = verdict,
        detectionTagId = detectionTagId,
        detectionTags = detectionTags,
        onVerdict = viewModel::submitVerdict,
        onClearVerdict = viewModel::clearVerdict,
        photos = photoGallery,
        onPhotoCaptured = viewModel::onPhotoCaptured,
        onPhotoPicked = viewModel::onPhotoPicked,
    )
}

@Composable
fun CandidateModal(
    candidate: Stage4Candidate,
    uiState: CandidateUiState?,
    onClose: () -> Unit,
    onSelectMode: (CandidateViewMode) -> Unit,
    onRetry: () -> Unit = {},
    onAuthExpired: () -> Unit = {},
    verdict: Boolean? = null,
    detectionTagId: Long? = null,
    detectionTags: List<DetectionTag> = emptyList(),
    onVerdict: (Boolean, Long?) -> Unit = { _, _ -> },
    onClearVerdict: () -> Unit = {},
    photos: List<PendingPhotoUploadEntity> = emptyList(),
    onPhotoCaptured: (Uri) -> Unit = {},
    onPhotoPicked: (Uri) -> Unit = {},
) {
    val activeCandidate = uiState?.candidate ?: candidate
    val currentMode = uiState?.viewMode ?: CandidateViewMode.MAP
    val tileUrlPattern = uiState?.tileUrlPattern ?: ""
    val imageModel = uiState?.croppedImageModel

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
                            }.then(
                                if (isMapActive) {
                                    Modifier
                                } else {
                                    Modifier.clearAndSetSemantics {}
                                },
                            ).pointerHitTestEnabled(isMapActive),
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
                            }.then(
                                if (isImageActive) {
                                    Modifier
                                } else {
                                    Modifier.clearAndSetSemantics {}
                                },
                            ).pointerHitTestEnabled(isImageActive),
                ) {
                    CandidateImageView(
                        candidate = activeCandidate,
                        imageModel = imageModel,
                        onRetry = onRetry,
                        onAuthExpired = onAuthExpired,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                Column(
                    modifier =
                        Modifier
                            .zIndex(2f)
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                ) {
                    PhotoGallery(
                        photos = photos,
                        onPhotoCaptured = onPhotoCaptured,
                        onPhotoPicked = onPhotoPicked,
                    )
                    CandidateVerdictBar(
                        verdict = verdict,
                        detectionTagId = detectionTagId,
                        detectionTags = detectionTags,
                        onVerdict = onVerdict,
                        onClearVerdict = onClearVerdict,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoGallery(
    photos: List<PendingPhotoUploadEntity>,
    onPhotoCaptured: (Uri) -> Unit,
    onPhotoPicked: (Uri) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
    ) {
        var showCamera by remember { mutableStateOf(false) }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val cameraPermission = rememberCameraPermission()
                val galleryPicker = rememberGalleryPicker(onImagePicked = onPhotoPicked)
                PhotoCaptureButton(
                    onClick = {
                        if (cameraPermission.granted) {
                            showCamera = true
                        } else {
                            cameraPermission.request()
                        }
                    },
                )
                OutlinedButton(
                    onClick = { galleryPicker.launch() },
                    modifier = Modifier.testTag("gallery-pick-button"),
                ) {
                    Text("Pick from gallery")
                }
                if (cameraPermission.showDenied) {
                    Text(
                        text = "Camera permission needed to take photos",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { cameraPermission.dismissDenied() }) {
                        Text("Dismiss")
                    }
                }
            }
            if (showCamera) {
                CameraScreen(
                    onCapture = { uri ->
                        showCamera = false
                        onPhotoCaptured(uri)
                    },
                    onCancel = { showCamera = false },
                )
            }
            if (photos.isEmpty()) {
                Text(
                    text = "No photos yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("photo-gallery-empty"),
                )
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().testTag("photo-gallery"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(photos, key = { it.rowId }) { photo ->
                        AsyncImage(
                            model = File(photo.localFilePath),
                            contentDescription = "Evidence photo",
                            contentScale = ContentScale.Crop,
                            modifier =
                                Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateVerdictBar(
    verdict: Boolean?,
    detectionTagId: Long?,
    detectionTags: List<DetectionTag>,
    onVerdict: (Boolean, Long?) -> Unit,
    onClearVerdict: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { onVerdict(true, null) },
                    modifier =
                        Modifier
                            .weight(1f)
                            .testTag("verdict-yes"),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor =
                                if (verdict == true) {
                                    dfnMarkerYes
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                        ),
                ) {
                    Text("Yes")
                }
                OutlinedButton(
                    onClick = { onVerdict(false, null) },
                    modifier =
                        Modifier
                            .weight(1f)
                            .testTag("verdict-no"),
                    colors =
                        ButtonDefaults.outlinedButtonColors(
                            containerColor =
                                if (verdict == false) {
                                    dfnMarkerNo
                                } else {
                                    Color.Transparent
                                },
                        ),
                ) {
                    Text("No")
                }
                TextButton(
                    onClick = onClearVerdict,
                    modifier = Modifier.testTag("verdict-clear"),
                ) {
                    Text("Clear")
                }
            }
            if (verdict == false) {
                DetectionTagPicker(
                    detectionTags = detectionTags,
                    selectedTagId = detectionTagId,
                    onTagSelected = { tagId -> onVerdict(false, tagId) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetectionTagPicker(
    detectionTags: List<DetectionTag>,
    selectedTagId: Long?,
    onTagSelected: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (detectionTags.isEmpty()) {
        OutlinedTextField(
            value = "",
            onValueChange = {},
            label = { Text("Tag (optional)") },
            readOnly = true,
            modifier = modifier,
        )
    } else {
        var expanded by remember { mutableStateOf(false) }
        val selectedTag = detectionTags.firstOrNull { it.id == selectedTagId }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = modifier,
        ) {
            OutlinedTextField(
                value = selectedTag?.name ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text("Tag (optional)") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier =
                    Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                detectionTags.forEach { tag ->
                    DropdownMenuItem(
                        text = { Text(tag.name) },
                        onClick = {
                            onTagSelected(tag.id)
                            expanded = false
                        },
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
