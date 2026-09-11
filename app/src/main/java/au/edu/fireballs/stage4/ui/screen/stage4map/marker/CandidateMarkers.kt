package au.edu.fireballs.stage4.ui.screen.stage4map.marker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import au.edu.fireballs.stage4.R
import au.edu.fireballs.stage4.domain.model.Stage4Candidate
import au.edu.fireballs.stage4.domain.model.Stage4State
import au.edu.fireballs.stage4.ui.screen.stage4map.LayerToggleState
import au.edu.fireballs.stage4.ui.theme.LocalDFNColors
import com.google.gson.JsonObject
import com.mapbox.bindgen.Value
import com.mapbox.geojson.Feature
import com.mapbox.geojson.Point
import com.mapbox.maps.ClickInteraction
import com.mapbox.maps.MapboxDelicateApi
import com.mapbox.maps.MapboxExperimental
import com.mapbox.maps.extension.compose.DisposableMapEffect
import com.mapbox.maps.extension.compose.MapboxMapComposable
import com.mapbox.maps.extension.compose.style.BooleanValue
import com.mapbox.maps.extension.compose.style.ColorValue
import com.mapbox.maps.extension.compose.style.DoubleListValue
import com.mapbox.maps.extension.compose.style.DoubleValue
import com.mapbox.maps.extension.compose.style.StyleImage
import com.mapbox.maps.extension.compose.style.layers.Filter
import com.mapbox.maps.extension.compose.style.layers.ImageValue
import com.mapbox.maps.extension.compose.style.layers.generated.CircleLayer
import com.mapbox.maps.extension.compose.style.layers.generated.IconAnchorValue
import com.mapbox.maps.extension.compose.style.layers.generated.SymbolLayer
import com.mapbox.maps.extension.compose.style.rememberStyleImage
import com.mapbox.maps.extension.compose.style.sources.GeoJSONData
import com.mapbox.maps.extension.compose.style.sources.SourceState
import com.mapbox.maps.extension.compose.style.sources.generated.rememberGeoJsonSourceState
import com.mapbox.maps.extension.style.expressions.generated.Expression

private const val CANDIDATE_SOURCE_ID = "candidate-markers"
private const val CLAIMED_SOURCE_ID = "claimed-markers"

private const val LAYER_YES = "candidate-markers-yes"
private const val LAYER_NO = "candidate-markers-no"
private const val LAYER_UNPROCESSED = "candidate-markers-unprocessed"
private const val LAYER_CLAIMED_CIRCLE = "candidate-markers-claimed-circle"

private const val PROP_INFERENCE_ID = "inferenceResultId"
private const val PROP_VERDICT = "verdict"
private const val PROP_CLAIMED = "claimed"

private const val ICON_YES = "marker-yes"
private const val ICON_NO = "marker-no"
private const val ICON_UNPROCESSED = "marker-unprocessed"

private const val VERDICT_UNPROCESSED = 0
private const val VERDICT_YES = 1
private const val VERDICT_NO = 2

private const val CLAIM_NONE = 0
private const val CLAIM_ME = 1
private const val CLAIM_OTHER = 2

private const val NO_OPACITY = 0.8
private const val ICON_SIZE = 0.4
private const val CLAIM_CIRCLE_RADIUS = 20.0
private const val CLAIM_CIRCLE_OPACITY = 0.4
private const val CLAIM_CIRCLE_STROKE = 2.0
private const val CLAIM_CIRCLE_TRANSLATE_Y = -10.0
private const val TRANSPARENT = "rgba(0,0,0,0)"

internal data class MarkerCandidate(
    val candidate: Stage4Candidate,
    val verdict: Int,
    val claim: Int,
)

@Composable
@MapboxMapComposable
@OptIn(MapboxDelicateApi::class)
fun CandidateMarkers(
    state: Stage4State,
    toggleState: LayerToggleState,
    onMarkerClick: (Stage4Candidate) -> Unit,
) {
    val candidates = remember(state, toggleState) { buildVisibleCandidates(state, toggleState) }
    if (candidates.isEmpty()) return

    val yesImage = rememberStyleImage(ICON_YES, R.drawable.marker_yes, 1f, false)
    val noImage = rememberStyleImage(ICON_NO, R.drawable.marker_no, 1f, false)
    val unprocessedImage =
        rememberStyleImage(ICON_UNPROCESSED, R.drawable.marker_unprocessed, 1f, false)

    val colors = LocalDFNColors.current
    val claimedByMeHex = colors.markerClaimedByMeOutline.toHex()
    val claimedByOtherHex = colors.markerClaimedByOtherOutline.toHex()

    val sourceState =
        rememberGeoJsonSourceState(key = CANDIDATE_SOURCE_ID) {
            data = GeoJSONData(candidates.map { it.toFeature() })
        }

    LaunchedEffect(candidates) {
        sourceState.data = GeoJSONData(candidates.map { it.toFeature() })
    }

    val claimedCandidates = candidates.filter { it.claim != CLAIM_NONE }
    val claimedSourceState =
        rememberGeoJsonSourceState(key = CLAIMED_SOURCE_ID) {
            data = GeoJSONData(claimedCandidates.map { it.toClaimedFeature() })
        }

    LaunchedEffect(claimedCandidates) {
        claimedSourceState.data = GeoJSONData(claimedCandidates.map { it.toClaimedFeature() })
    }

    if (claimedCandidates.isNotEmpty()) {
        CircleLayer(claimedSourceState, LAYER_CLAIMED_CIRCLE) {
            circleColor = ColorValue(claimColorExpression(claimedByMeHex, claimedByOtherHex))
            circleRadius = DoubleValue(CLAIM_CIRCLE_RADIUS)
            circleOpacity = DoubleValue(CLAIM_CIRCLE_OPACITY)
            circleStrokeColor = ColorValue(Value("#ffffff"))
            circleStrokeWidth = DoubleValue(CLAIM_CIRCLE_STROKE)
            circleTranslate = DoubleListValue(listOf(0.0, CLAIM_CIRCLE_TRANSLATE_Y))
        }
    }

    CandidateLayer(
        sourceState = sourceState,
        layerId = LAYER_YES,
        verdict = VERDICT_YES,
        image = yesImage,
        opacity = 1.0,
    )
    CandidateLayer(
        sourceState = sourceState,
        layerId = LAYER_NO,
        verdict = VERDICT_NO,
        image = noImage,
        opacity = NO_OPACITY,
    )
    CandidateLayer(
        sourceState = sourceState,
        layerId = LAYER_UNPROCESSED,
        verdict = VERDICT_UNPROCESSED,
        image = unprocessedImage,
        opacity = 1.0,
    )

    CandidateClickHandler(candidates = candidates, onMarkerClick = onMarkerClick)
}

@Composable
@MapboxMapComposable
private fun CandidateLayer(
    sourceState: SourceState,
    layerId: String,
    verdict: Int,
    image: StyleImage,
    opacity: Double,
) {
    SymbolLayer(sourceState, layerId) {
        filter = Filter(verdictFilterExpression(verdict))
        iconImage = ImageValue(image)
        iconSize = DoubleValue(ICON_SIZE)
        iconOpacity = DoubleValue(opacity)
        iconAllowOverlap = BooleanValue(true)
        iconIgnorePlacement = BooleanValue(true)
        iconAnchor = IconAnchorValue.BOTTOM
    }
}

@Composable
@MapboxMapComposable
@OptIn(MapboxExperimental::class)
private fun CandidateClickHandler(
    candidates: List<MarkerCandidate>,
    onMarkerClick: (Stage4Candidate) -> Unit,
) {
    val candidateById =
        remember(
            candidates,
        ) { candidates.associate { it.candidate.inferenceResultId to it.candidate } }

    DisposableMapEffect(candidateById, onMarkerClick) { mapView ->
        val cancelables =
            listOf(LAYER_YES, LAYER_NO, LAYER_UNPROCESSED).map { layerId ->
                val interaction =
                    ClickInteraction.layer(layerId) { feature, _ ->
                        val id =
                            feature.originalFeature
                                .getNumberProperty(
                                    PROP_INFERENCE_ID,
                                )?.toLong()
                        if (id != null) {
                            candidateById[id]?.let(onMarkerClick)
                            true
                        } else {
                            false
                        }
                    }
                mapView.mapboxMap.addInteraction(interaction)
            }
        onDispose {
            cancelables.forEach { it.cancel() }
        }
    }
}

internal fun buildVisibleCandidates(
    state: Stage4State,
    toggleState: LayerToggleState,
): List<MarkerCandidate> {
    val result = mutableListOf<MarkerCandidate>()
    if (toggleState.showYes) {
        state.yesMeteorites.forEach { candidate ->
            candidate.geoCentroid?.let {
                result += MarkerCandidate(candidate, VERDICT_YES, candidate.claimCode())
            }
        }
    }
    if (toggleState.showNo) {
        state.noMeteorites.forEach { candidate ->
            candidate.geoCentroid?.let {
                result += MarkerCandidate(candidate, VERDICT_NO, candidate.claimCode())
            }
        }
    }
    if (toggleState.showUnprocessed) {
        state.unprocessedCandidates.forEach { candidate ->
            candidate.geoCentroid?.let {
                result += MarkerCandidate(candidate, VERDICT_UNPROCESSED, candidate.claimCode())
            }
        }
    }
    return result
}

private fun Stage4Candidate.claimCode(): Int =
    when {
        claimedByMe -> CLAIM_ME
        claimedByOther -> CLAIM_OTHER
        else -> CLAIM_NONE
    }

private fun MarkerCandidate.toFeature(): Feature {
    val properties = JsonObject()
    properties.addProperty(PROP_INFERENCE_ID, candidate.inferenceResultId)
    properties.addProperty(PROP_VERDICT, verdict)
    properties.addProperty(PROP_CLAIMED, claim)
    val centroid = candidate.geoCentroid ?: return Feature.fromGeometry(Point.fromLngLat(0.0, 0.0))
    return Feature.fromGeometry(Point.fromLngLat(centroid.longitude, centroid.latitude), properties)
}

private fun MarkerCandidate.toClaimedFeature(): Feature {
    val properties = JsonObject()
    properties.addProperty(PROP_CLAIMED, claim)
    val centroid = candidate.geoCentroid ?: return Feature.fromGeometry(Point.fromLngLat(0.0, 0.0))
    return Feature.fromGeometry(Point.fromLngLat(centroid.longitude, centroid.latitude), properties)
}

private fun verdictFilterExpression(verdict: Int): Expression =
    Expression.match {
        get(PROP_VERDICT)
        literal(verdict.toDouble())
        literal(true)
        literal(false)
    }

private fun claimColorExpression(
    claimedByMe: String,
    claimedByOther: String,
): Expression =
    Expression.match {
        get(PROP_CLAIMED)
        literal(CLAIM_ME.toDouble())
        literal(claimedByMe)
        literal(CLAIM_OTHER.toDouble())
        literal(claimedByOther)
        literal(TRANSPARENT)
    }

private fun Color.toHex(): String = "#%06X".format(toArgb() and 0xFFFFFF)
