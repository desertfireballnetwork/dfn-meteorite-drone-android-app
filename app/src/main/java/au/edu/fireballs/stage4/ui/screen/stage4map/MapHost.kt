package au.edu.fireballs.stage4.ui.screen.stage4map

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import au.edu.fireballs.stage4.domain.model.GeoCoordinate
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
    polygons: List<List<List<Double>>>,
    tilesetId: String?,
    base: GeoCoordinate?,
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
        MapEffect(Unit) { mapView ->
            mapView.mapboxMap.setBounds(
                CameraBoundsOptions.Builder().maxZoom(MAX_CAMERA_ZOOM).build(),
            )
        }

        if (locationPermissionGranted) {
            MapEffect(locationPermissionGranted) { mapView ->
                enableLocationPuck(mapView)
            }
        }

        SurveyedAreaOverlay(
            polygons = polygons,
            tilesetId = tilesetId,
        )
        BaseMarker(base = base)
    }
}

private fun enableLocationPuck(mapView: MapView) {
    val locationPlugin =
        mapView.getPlugin(Plugin.MAPBOX_LOCATION_COMPONENT_PLUGIN_ID) as? LocationComponentPlugin
    locationPlugin?.updateSettings {
        enabled = true
        pulsingEnabled = true
    }
}
