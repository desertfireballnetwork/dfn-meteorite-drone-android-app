package au.edu.fireballs.stage4.ui.screen.candidate

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import au.edu.fireballs.stage4.domain.model.Stage4Candidate
import coil.compose.SubcomposeAsyncImage
import coil.network.HttpException
import kotlin.math.roundToInt

fun calculateDisplayedBox(
    candidate: Stage4Candidate,
    containerSize: Size,
    scale: Float = 1.0f,
    panOffset: Offset = Offset.Zero,
    cropBounds: CropBounds = calculateCropPosition(candidate),
): BoxRect {
    if (containerSize.width <= 0f || containerSize.height <= 0f) {
        return BoxRect(0f, 0f, 0f, 0f)
    }
    if (cropBounds.width <= 0 || cropBounds.height <= 0) {
        return BoxRect(0f, 0f, 0f, 0f)
    }
    val boxInCrop = calculateBoxInCrop(candidate, cropBounds)
    val scaleFactor =
        minOf(
            containerSize.width / cropBounds.width.toFloat(),
            containerSize.height / cropBounds.height.toFloat(),
        )
    val fittedWidth = cropBounds.width.toFloat() * scaleFactor
    val fittedHeight = cropBounds.height.toFloat() * scaleFactor
    val displayedLeft = (containerSize.width - fittedWidth) / 2f
    val displayedTop = (containerSize.height - fittedHeight) / 2f

    val unzoomedX = displayedLeft + boxInCrop.x * scaleFactor
    val unzoomedY = displayedTop + boxInCrop.y * scaleFactor
    val unzoomedWidth = boxInCrop.width * scaleFactor
    val unzoomedHeight = boxInCrop.height * scaleFactor

    val centerX = containerSize.width / 2f
    val centerY = containerSize.height / 2f

    val finalX = centerX + (unzoomedX - centerX) * scale + panOffset.x
    val finalY = centerY + (unzoomedY - centerY) * scale + panOffset.y
    val finalWidth = unzoomedWidth * scale
    val finalHeight = unzoomedHeight * scale

    return BoxRect(
        x = finalX,
        y = finalY,
        width = finalWidth,
        height = finalHeight,
    )
}

@Composable
fun CandidateImageView(
    candidate: Stage4Candidate,
    imageModel: Any?,
    onRetry: () -> Unit = {},
    onAuthExpired: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableFloatStateOf(1.0f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(candidate.inferenceResultId) {
        scale = 1.0f
        offset = Offset.Zero
    }

    val zoomPercent = (scale * 100).roundToInt()

    Box(
        modifier =
            modifier
                .testTag("candidate-image-view")
                .clipToBounds()
                .semantics {
                    contentDescription = "Candidate ${candidate.inferenceResultId}"
                    stateDescription = "Zoom $zoomPercent%"
                    customActions =
                        listOf(
                            CustomAccessibilityAction("Zoom in") {
                                scale = (scale * 1.5f).coerceAtMost(10f)
                                true
                            },
                            CustomAccessibilityAction("Zoom out") {
                                scale = (scale / 1.5f).coerceAtLeast(1f)
                                true
                            },
                            CustomAccessibilityAction("Reset zoom") {
                                scale = 1.0f
                                offset = Offset.Zero
                                true
                            },
                        )
                }.pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1.0f) {
                                scale = 1.0f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                                offset = Offset.Zero
                            }
                        },
                    )
                }.pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1.0f, 10.0f)
                        scale = newScale
                        if (newScale <= 1.0f) {
                            offset = Offset.Zero
                        } else {
                            val maxOffsetX = (size.width * (newScale - 1f)) / 2f
                            val maxOffsetY = (size.height * (newScale - 1f)) / 2f
                            offset =
                                Offset(
                                    x = (offset.x + pan.x).coerceIn(-maxOffsetX, maxOffsetX),
                                    y = (offset.y + pan.y).coerceIn(-maxOffsetY, maxOffsetY),
                                )
                        }
                    }
                },
    ) {
        SubcomposeAsyncImage(
            model = imageModel,
            contentDescription = "Candidate ${candidate.inferenceResultId}",
            contentScale = ContentScale.Fit,
            modifier =
                Modifier
                    .fillMaxSize()
                    .testTag("candidate-async-image")
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            loading = {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            },
            error = { errorState ->
                LaunchedEffect(errorState.result.throwable) {
                    val throwable = errorState.result.throwable
                    if (throwable is HttpException && throwable.response.code in 401..403) {
                        onAuthExpired()
                    }
                }
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Couldn't load candidate image")
                        Button(
                            onClick = onRetry,
                            modifier = Modifier.testTag("candidate-image-retry"),
                        ) {
                            Text("Retry")
                        }
                    }
                }
            },
        )

        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .testTag("candidate-box-canvas"),
        ) {
            val displayedBox =
                calculateDisplayedBox(
                    candidate = candidate,
                    containerSize = size,
                    scale = scale,
                    panOffset = offset,
                )
            if (displayedBox.width > 0f && displayedBox.height > 0f) {
                drawRect(
                    color = Color(0, 0, 255, 128),
                    topLeft = Offset(displayedBox.x, displayedBox.y),
                    size = Size(displayedBox.width, displayedBox.height),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }
    }
}
