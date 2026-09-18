package au.edu.fireballs.stage4.ui.screen.stage4map

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import au.edu.fireballs.stage4.domain.model.MapCameraTarget
import au.edu.fireballs.stage4.domain.model.Stage4Candidate
import au.edu.fireballs.stage4.domain.model.Stage4State
import au.edu.fireballs.stage4.domain.model.UserLocation
import au.edu.fireballs.stage4.ui.screen.stage4map.marker.CandidateMarkers
import com.mapbox.maps.CameraBoundsOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.extension.compose.DisposableMapEffect
import com.mapbox.maps.extension.compose.MapEffect
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.MapViewportState
import com.mapbox.maps.extension.compose.style.MapStyle
import com.mapbox.maps.extension.compose.style.projection.generated.Projection
import com.mapbox.maps.extension.compose.style.rememberStyleState
import com.mapbox.maps.plugin.Plugin
import com.mapbox.maps.plugin.PuckBearing
import com.mapbox.maps.plugin.locationcomponent.LocationComponentPlugin
import com.mapbox.maps.plugin.locationcomponent.createDefault2DPuck
import com.mapbox.maps.plugin.locationcomponent.generated.LocationComponentSettings

private const val BASE_STYLE_URI = "mapbox://styles/mapbox/satellite-v9"
private const val MAX_CAMERA_ZOOM = 24.0

@Composable
internal fun MapHost(
    mapViewportState: MapViewportState,
    locationPermissionGranted: Boolean,
    state: Stage4State,
    layerToggleState: LayerToggleState,
    onMarkerClick: (Stage4Candidate) -> Unit,
    onUserLocationClick: (UserLocation) -> Unit,
    overlayCandidates: List<Pair<Long, String>> = emptyList(),
    onCameraChange: (MapCameraTarget) -> Unit = {},
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
        MapHostEffects(
            locationPermissionGranted = locationPermissionGranted,
            showAccuracyRing = state.showGeolocationAccuracyCircle,
        )
        DisposableMapEffect(Unit) { mapView ->
            val subscription =
                mapView.mapboxMap.subscribeCameraChanged {
                    val camera = mapView.mapboxMap.cameraState
                    onCameraChange(
                        MapCameraTarget(
                            latitude = camera.center.latitude(),
                            longitude = camera.center.longitude(),
                            zoom = camera.zoom,
                        ),
                    )
                }
            onDispose { subscription.cancel() }
        }

        SurveyedAreaOverlay(
            polygons = state.surveyedAreas,
            tilesetId = state.survey.tilesetId,
            visible = layerToggleState.showSurveyedAreas,
        )
        BaseMarker(base = state.base)

        overlayCandidates.forEach { (candidateId, pattern) ->
            key(candidateId) {
                CustomRasterOverlay(
                    surveyId = state.survey.id,
                    candidateId = candidateId,
                    tileUrlPattern = pattern,
                )
            }
        }
        CandidateMarkers(
            state = state,
            toggleState = layerToggleState,
            onMarkerClick = onMarkerClick,
        )
        UserLocationMarkers(
            userLocations = state.userLocations,
            onUserLocationClick = onUserLocationClick,
        )
    }
}

@Composable
private fun MapHostEffects(
    locationPermissionGranted: Boolean,
    showAccuracyRing: Boolean,
) {
    MapEffect(Unit) { mapView ->
        mapView.mapboxMap.setBounds(
            CameraBoundsOptions.Builder().maxZoom(MAX_CAMERA_ZOOM).build(),
        )
    }

    MapEffect(locationPermissionGranted, showAccuracyRing) { mapView ->
        setLocationPuckEnabled(
            mapView,
            enabled = locationPermissionGranted,
            showAccuracyRing = showAccuracyRing,
        )
    }
}

private fun setLocationPuckEnabled(
    mapView: MapView,
    enabled: Boolean,
    showAccuracyRing: Boolean,
) {
    val locationPlugin =
        mapView.getPlugin(Plugin.MAPBOX_LOCATION_COMPONENT_PLUGIN_ID) as? LocationComponentPlugin
    locationPlugin?.updateSettings {
        applyUserLocationDisplay(enabled = enabled, showAccuracyRing = showAccuracyRing)
    }
}

internal fun LocationComponentSettings.Builder.applyUserLocationDisplay(
    enabled: Boolean,
    showAccuracyRing: Boolean,
) {
    this.enabled = enabled
    pulsingEnabled = enabled
    this.showAccuracyRing = showAccuracyRing
    puckBearingEnabled = enabled
    puckBearing = PuckBearing.HEADING
    locationPuck = createDefault2DPuck(withBearing = enabled)
}
