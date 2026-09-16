package au.edu.fireballs.stage4.ui.screen.candidate

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import au.edu.fireballs.stage4.R
import au.edu.fireballs.stage4.domain.model.Stage4Candidate
import com.mapbox.geojson.Point
import com.mapbox.maps.AnnotatedFeature
import com.mapbox.maps.CameraBoundsOptions
import com.mapbox.maps.ViewAnnotationAnchor
import com.mapbox.maps.ViewAnnotationAnchorConfig
import com.mapbox.maps.ViewAnnotationOptions
import com.mapbox.maps.dsl.cameraOptions
import com.mapbox.maps.extension.compose.MapEffect
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.compose.annotation.ViewAnnotation
import com.mapbox.maps.extension.compose.style.LongValue
import com.mapbox.maps.extension.compose.style.MapStyle
import com.mapbox.maps.extension.compose.style.StringListValue
import com.mapbox.maps.extension.compose.style.layers.generated.RasterLayer
import com.mapbox.maps.extension.compose.style.projection.generated.Projection
import com.mapbox.maps.extension.compose.style.rememberStyleState
import com.mapbox.maps.extension.compose.style.sources.generated.SchemeValue
import com.mapbox.maps.extension.compose.style.sources.generated.rememberRasterSourceState

@Composable
fun CandidateMap(
    candidate: Stage4Candidate,
    tileUrlPattern: String,
    modifier: Modifier = Modifier,
) {
    if (LocalInspectionMode.current) {
        Box(
            modifier =
                modifier
                    .fillMaxSize()
                    .testTag(CandidateMapDefaults.ROOT_TAG),
        ) {
            if (candidate.geoCentroid != null) {
                Box(
                    modifier = Modifier.testTag(CandidateMapDefaults.MARKER_TAG),
                )
            }
        }
        return
    }

    val centroid = candidate.geoCentroid
    val mapViewportState =
        rememberMapViewportState {
            centroid?.let { coord ->
                setCameraOptions(
                    cameraOptions {
                        center(Point.fromLngLat(coord.longitude, coord.latitude))
                        zoom(CandidateMapDefaults.CANDIDATE_ZOOM)
                    },
                )
            }
        }
    val styleState =
        rememberStyleState {
            projection = Projection.GLOBE
        }

    LaunchedEffect(candidate.inferenceResultId, centroid) {
        centroid?.let { coord ->
            mapViewportState.setCameraOptions(
                cameraOptions {
                    center(Point.fromLngLat(coord.longitude, coord.latitude))
                    zoom(CandidateMapDefaults.CANDIDATE_ZOOM)
                },
            )
        }
    }

    MapboxMap(
        modifier =
            modifier
                .fillMaxSize()
                .testTag(CandidateMapDefaults.ROOT_TAG),
        mapViewportState = mapViewportState,
        style = {
            MapStyle(
                style = CandidateMapDefaults.BASE_STYLE_URI,
                styleState = styleState,
            )
        },
    ) {
        MapEffect(Unit) { mapView ->
            mapView.mapboxMap.setBounds(
                CameraBoundsOptions.Builder().maxZoom(CandidateMapDefaults.MAX_CAMERA_ZOOM).build(),
            )
        }

        if (tileUrlPattern.isNotEmpty()) {
            val sourceKey = CandidateMapDefaults.sourceKey(candidate.inferenceResultId)
            val layerId = CandidateMapDefaults.layerId(candidate.inferenceResultId)
            val sourceState =
                rememberRasterSourceState(key = sourceKey) {
                    tiles = StringListValue(listOf(tileUrlPattern))
                    scheme = SchemeValue.TMS
                    tileSize = LongValue(CandidateMapDefaults.TILE_SIZE)
                    minZoom = LongValue(CandidateMapDefaults.MIN_ZOOM)
                    maxZoom = LongValue(CandidateMapDefaults.MAX_ZOOM)
                }
            RasterLayer(sourceState, layerId)
        }

        if (centroid != null) {
            val options =
                ViewAnnotationOptions
                    .Builder()
                    .annotatedFeature(
                        AnnotatedFeature(
                            Point.fromLngLat(centroid.longitude, centroid.latitude),
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
                    modifier = Modifier.testTag(CandidateMapDefaults.MARKER_TAG),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.marker_unprocessed),
                        contentDescription = "Candidate #${candidate.inferenceResultId}",
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}
