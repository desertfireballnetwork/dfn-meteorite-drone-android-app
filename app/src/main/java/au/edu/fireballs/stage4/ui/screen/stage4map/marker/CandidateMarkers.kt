package au.edu.fireballs.stage4.ui.screen.stage4map.marker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import au.edu.fireballs.stage4.domain.model.Stage4Candidate
import au.edu.fireballs.stage4.domain.model.Stage4State
import au.edu.fireballs.stage4.ui.screen.stage4map.LayerToggleState
import com.mapbox.geojson.Point
import com.mapbox.maps.AnnotatedFeature
import com.mapbox.maps.ViewAnnotationAnchor
import com.mapbox.maps.ViewAnnotationAnchorConfig
import com.mapbox.maps.ViewAnnotationOptions
import com.mapbox.maps.extension.compose.MapboxMapComposable
import com.mapbox.maps.extension.compose.annotation.ViewAnnotation

@Composable
@MapboxMapComposable
fun CandidateMarkers(
    state: Stage4State,
    toggleState: LayerToggleState,
    onMarkerClick: (Stage4Candidate) -> Unit,
) {
    if (toggleState.showYes) {
        state.yesMeteorites.forEach { candidate ->
            CandidateMarker(candidate, verdict = 1, onClick = onMarkerClick)
        }
    }
    if (toggleState.showNo) {
        state.noMeteorites.forEach { candidate ->
            CandidateMarker(candidate, verdict = 2, onClick = onMarkerClick)
        }
    }
    if (toggleState.showUnprocessed) {
        state.unprocessedCandidates.forEach { candidate ->
            CandidateMarker(candidate, verdict = 0, onClick = onMarkerClick)
        }
    }
}

@Composable
@MapboxMapComposable
private fun CandidateMarker(
    candidate: Stage4Candidate,
    verdict: Int,
    onClick: (Stage4Candidate) -> Unit,
) {
    val coordinate = candidate.geoCentroid ?: return
    val style = getCandidateMarkerStyle(candidate, verdict)

    val options =
        ViewAnnotationOptions
            .Builder()
            .annotatedFeature(
                AnnotatedFeature(
                    Point.fromLngLat(coordinate.longitude, coordinate.latitude),
                ),
            ).variableAnchors(
                listOf(
                    ViewAnnotationAnchorConfig
                        .Builder()
                        .anchor(ViewAnnotationAnchor.BOTTOM)
                        .build(),
                ),
            ).build()

    ViewAnnotation(options = options) {
        Box(
            modifier =
                Modifier
                    .clickable { onClick(candidate) }
                    .testTag("candidate-marker-${candidate.inferenceResultId}"),
            contentAlignment = Alignment.Center,
        ) {
            if (style.claimBorderColor != null) {
                Canvas(modifier = Modifier.size(style.size + 6.dp)) {
                    drawCircle(
                        color = style.claimBorderColor,
                        style = Stroke(width = 4.dp.toPx()),
                    )
                }
            }
            Image(
                painter = painterResource(style.iconRes),
                contentDescription = "Candidate #${candidate.inferenceResultId}",
                modifier = Modifier.size(style.size),
                alpha = style.opacity,
            )
        }
    }
}
