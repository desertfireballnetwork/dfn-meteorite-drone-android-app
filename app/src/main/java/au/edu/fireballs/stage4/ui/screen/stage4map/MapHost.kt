package au.edu.fireballs.stage4.ui.screen.stage4map

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import au.edu.fireballs.stage4.domain.model.Stage4Candidate
import au.edu.fireballs.stage4.domain.model.Stage4State
import au.edu.fireballs.stage4.ui.screen.stage4map.marker.CandidateMarkers
import com.mapbox.maps.CameraBoundsOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.extension.compose.MapEffect
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.MapViewportState
import com.mapbox.maps.extension.compose.style.MapStyle
import com.mapbox.maps.extension.compose.style.projection.generated.Projection
import com.mapbox.maps.extension.compose.style.rememberStyleState
import com.mapbox.maps.plugin.Plugin
import com.mapbox.maps.plugin.locationcomponent.LocationComponentPlugin

private const val BASE_STYLE_URI = "mapbox://styles/mapbox/standard-satellite"
private const val MAX_CAMERA_ZOOM = 25.0

@Composable
internal fun MapHost(
    mapViewportState: MapViewportState,
    locationPermissionGranted: Boolean,
    state: Stage4State,
    layerToggleState: LayerToggleState,
    onMarkerClick: (Stage4Candidate) -> Unit,
    candidateId: Long? = null,
    tileUrlPattern: String? = null,
) {
    val styleState =
        rememberStyleState {
            projection = Projection.GLOBE
        }

    MapboxMap(
        modifier =
            Modifier
                .fillMaxSize()
                .testTag("map-host-root"),
        mapViewportState = mapViewportState,
        style = {
            MapStyle(
                style = BASE_STYLE_URI,
                styleState = styleState,
            )
        },
    ) {
        MapHostEffects(locationPermissionGranted = locationPermissionGranted)

        SurveyedAreaOverlay(
            polygons = state.surveyedAreas,
            tilesetId = state.survey.tilesetId,
            visible = layerToggleState.showSurveyedAreas,
        )
        BaseMarker(base = state.base)

        CandidateMarkers(
            state = state,
            toggleState = layerToggleState,
            onMarkerClick = onMarkerClick,
        )
        CustomRasterOverlay(
            surveyId = state.survey.id,
            candidateId = candidateId,
            tileUrlPattern = tileUrlPattern,
        )
    }
}

@Composable
private fun MapHostEffects(locationPermissionGranted: Boolean) {
    MapEffect(Unit) { mapView ->
        mapView.mapboxMap.setBounds(
            CameraBoundsOptions.Builder().maxZoom(MAX_CAMERA_ZOOM).build(),
        )
    }

    MapEffect(locationPermissionGranted) { mapView ->
        setLocationPuckEnabled(mapView, enabled = locationPermissionGranted)
    }
}

private fun setLocationPuckEnabled(
    mapView: MapView,
    enabled: Boolean,
) {
    val locationPlugin =
        mapView.getPlugin(Plugin.MAPBOX_LOCATION_COMPONENT_PLUGIN_ID) as? LocationComponentPlugin
    locationPlugin?.updateSettings {
        this.enabled = enabled
        this.pulsingEnabled = enabled
    }
}
