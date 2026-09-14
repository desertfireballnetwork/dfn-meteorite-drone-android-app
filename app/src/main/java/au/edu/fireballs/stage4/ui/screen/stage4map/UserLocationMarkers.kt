package au.edu.fireballs.stage4.ui.screen.stage4map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import au.edu.fireballs.stage4.R
import au.edu.fireballs.stage4.domain.model.UserLocation
import com.google.gson.JsonObject
import com.mapbox.geojson.Feature
import com.mapbox.geojson.Point
import com.mapbox.maps.ClickInteraction
import com.mapbox.maps.MapboxDelicateApi
import com.mapbox.maps.extension.compose.DisposableMapEffect
import com.mapbox.maps.extension.compose.MapboxMapComposable
import com.mapbox.maps.extension.compose.style.BooleanValue
import com.mapbox.maps.extension.compose.style.DoubleValue
import com.mapbox.maps.extension.compose.style.layers.ImageValue
import com.mapbox.maps.extension.compose.style.layers.generated.IconAnchorValue
import com.mapbox.maps.extension.compose.style.layers.generated.SymbolLayer
import com.mapbox.maps.extension.compose.style.rememberStyleImage
import com.mapbox.maps.extension.compose.style.sources.GeoJSONData
import com.mapbox.maps.extension.compose.style.sources.generated.rememberGeoJsonSourceState

private const val USER_SOURCE_ID = "user-location-markers"
private const val USER_LAYER_ID = "user-location-markers-layer"
private const val USER_ICON = "marker-user"

private const val PROP_USER_ID = "userId"
private const val PROP_USERNAME = "username"
private const val PROP_FULL_NAME = "fullName"
private const val PROP_PROCESSED_AT = "processedAt"

private const val ICON_SIZE = 0.4

@Composable
@MapboxMapComposable
@OptIn(MapboxDelicateApi::class)
fun UserLocationMarkers(
    userLocations: List<UserLocation>,
    onUserLocationClick: (UserLocation) -> Unit,
) {
    if (userLocations.isEmpty()) return

    val userImage = rememberStyleImage(USER_ICON, R.drawable.marker_user, 1f, false)

    val sourceState =
        rememberGeoJsonSourceState(key = USER_SOURCE_ID) {
            data = GeoJSONData(buildUserLocationFeatures(userLocations))
        }

    LaunchedEffect(userLocations) {
        sourceState.data = GeoJSONData(buildUserLocationFeatures(userLocations))
    }

    SymbolLayer(sourceState, USER_LAYER_ID) {
        iconImage = ImageValue(userImage)
        iconSize = DoubleValue(ICON_SIZE)
        iconAllowOverlap = BooleanValue(true)
        iconIgnorePlacement = BooleanValue(true)
        iconAnchor = IconAnchorValue.BOTTOM
    }

    UserLocationClickHandler(
        userLocations = userLocations,
        onUserLocationClick = onUserLocationClick,
    )
}

@Composable
@MapboxMapComposable
private fun UserLocationClickHandler(
    userLocations: List<UserLocation>,
    onUserLocationClick: (UserLocation) -> Unit,
) {
    val userById =
        remember(userLocations) { userLocations.associateBy { it.userId } }

    DisposableMapEffect(userById, onUserLocationClick) { mapView ->
        val interaction =
            ClickInteraction.layer(USER_LAYER_ID) { feature, _ ->
                val id =
                    feature.originalFeature
                        .getNumberProperty(PROP_USER_ID)
                        ?.toLong()
                if (id != null) {
                    userById[id]?.let(onUserLocationClick)
                    true
                } else {
                    false
                }
            }
        val cancelable = mapView.mapboxMap.addInteraction(interaction)
        onDispose { cancelable.cancel() }
    }
}

internal fun buildUserLocationFeatures(userLocations: List<UserLocation>): List<Feature> =
    userLocations.map { location ->
        val properties = JsonObject()
        properties.addProperty(PROP_USER_ID, location.userId)
        properties.addProperty(PROP_USERNAME, location.username)
        properties.addProperty(PROP_FULL_NAME, location.fullName)
        properties.addProperty(PROP_PROCESSED_AT, location.processedAt)
        Feature.fromGeometry(
            Point.fromLngLat(location.coordinate.longitude, location.coordinate.latitude),
            properties,
        )
    }
