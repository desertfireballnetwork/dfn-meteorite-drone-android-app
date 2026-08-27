package au.edu.fireballs.stage4.ui.screen.stage4map

import android.content.Context
import android.location.Location
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.mapbox.geojson.Point
import com.mapbox.maps.dsl.cameraOptions
import com.mapbox.maps.extension.compose.animation.viewport.MapViewportState
import com.mapbox.maps.plugin.animation.MapAnimationOptions

private const val LOCATION_ANIMATION_DURATION_MS = 500L
private const val LOCATION_FIX_TIMEOUT_MS = 15_000L
private const val LOCATION_FAILURE_MESSAGE = "Couldn't get your location"
private const val POOR_ACCURACY_MESSAGE = "Location accuracy too low to recenter"
internal const val LOCATION_SERVICES_DISABLED_MESSAGE = "Location services are turned off"

@Composable
internal fun RecenterButton(
    modifier: Modifier,
    onClick: () -> Unit,
) {
    FloatingActionButton(onClick = onClick, modifier = modifier) {
        Icon(imageVector = Icons.Filled.LocationOn, contentDescription = "My location")
    }
}

internal fun recenterToUser(
    context: Context,
    mapViewportState: MapViewportState,
    onLocatingChanged: (Boolean) -> Unit,
    onMessage: (String?) -> Unit,
    onLocationServicesDisabled: (Boolean) -> Unit,
) {
    if (!isLocationPermissionGranted(context)) {
        onLocatingChanged(false)
        onMessage("Location permission required to find your position")
        return
    }
    if (!locationServicesEnabled(context)) {
        onLocatingChanged(false)
        onLocationServicesDisabled(true)
        onMessage(LOCATION_SERVICES_DISABLED_MESSAGE)
        return
    }

    val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    onLocatingChanged(true)
    onLocationServicesDisabled(false)
    onMessage(null)
    try {
        fusedLocationClient.lastLocation
            .addOnSuccessListener { cached ->
                handleCachedFix(
                    cached = cached,
                    fusedLocationClient = fusedLocationClient,
                    mapViewportState = mapViewportState,
                    onLocatingChanged = onLocatingChanged,
                    onMessage = onMessage,
                )
            }.addOnFailureListener {
                reportLocationFailure(onLocatingChanged, onMessage)
            }
    } catch (_: SecurityException) {
        reportLocationFailure(onLocatingChanged, onMessage)
    }
}

private fun handleCachedFix(
    cached: Location?,
    fusedLocationClient: FusedLocationProviderClient,
    mapViewportState: MapViewportState,
    onLocatingChanged: (Boolean) -> Unit,
    onMessage: (String?) -> Unit,
) {
    when (
        chooseLocationSource(
            lastFixTimeMs = cached?.time,
            nowMs = System.currentTimeMillis(),
        )
    ) {
        LocationFixSource.CACHE -> {
            onLocatingChanged(false)
            val usable =
                cached != null &&
                    isLocationFixUsable(
                        latitude = cached.latitude,
                        longitude = cached.longitude,
                        accuracyMeters = locationAccuracy(cached),
                    )
            if (usable) {
                flyToLocation(mapViewportState, cached)
            } else {
                onMessage(POOR_ACCURACY_MESSAGE)
            }
        }

        LocationFixSource.REQUEST_FRESH -> {
            requestCurrentLocationFix(
                fusedLocationClient = fusedLocationClient,
                mapViewportState = mapViewportState,
                onLocatingChanged = onLocatingChanged,
                onMessage = onMessage,
            )
        }
    }
}

private fun requestCurrentLocationFix(
    fusedLocationClient: FusedLocationProviderClient,
    mapViewportState: MapViewportState,
    onLocatingChanged: (Boolean) -> Unit,
    onMessage: (String?) -> Unit,
) {
    val cancellationTokenSource = CancellationTokenSource()
    try {
        fusedLocationClient
            .getCurrentLocation(
                CurrentLocationRequest
                    .Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setDurationMillis(LOCATION_FIX_TIMEOUT_MS)
                    .build(),
                cancellationTokenSource.token,
            ).addOnSuccessListener { location ->
                onLocatingChanged(false)
                when {
                    location == null -> onMessage(LOCATION_FAILURE_MESSAGE)

                    isLocationFixUsable(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracyMeters = locationAccuracy(location),
                    ) -> flyToLocation(mapViewportState, location)

                    else -> onMessage(POOR_ACCURACY_MESSAGE)
                }
            }.addOnFailureListener {
                reportLocationFailure(onLocatingChanged, onMessage)
            }
    } catch (_: SecurityException) {
        reportLocationFailure(onLocatingChanged, onMessage)
    }
}

private fun locationAccuracy(location: Location): Float? =
    if (location.hasAccuracy()) location.accuracy else null

private fun flyToLocation(
    mapViewportState: MapViewportState,
    location: Location,
) {
    mapViewportState.flyTo(
        cameraOptions {
            center(Point.fromLngLat(location.longitude, location.latitude))
        },
        MapAnimationOptions.mapAnimationOptions {
            duration(LOCATION_ANIMATION_DURATION_MS)
        },
    )
}

private fun reportLocationFailure(
    onLocatingChanged: (Boolean) -> Unit,
    onMessage: (String?) -> Unit,
) {
    onLocatingChanged(false)
    onMessage(LOCATION_FAILURE_MESSAGE)
}
