package au.edu.fireballs.stage4.ui.screen.stage4map

import android.content.Context
import android.location.LocationManager

internal enum class LocationFixSource {
    CACHE,
    REQUEST_FRESH,
}

internal const val MAX_LOCATION_AGE_MS = 60_000L
internal const val MAX_FIX_ACCURACY_METERS = 100.0

internal fun chooseLocationSource(
    lastFixTimeMs: Long?,
    nowMs: Long,
): LocationFixSource =
    when {
        lastFixTimeMs == null -> LocationFixSource.REQUEST_FRESH
        nowMs - lastFixTimeMs <= MAX_LOCATION_AGE_MS -> LocationFixSource.CACHE
        else -> LocationFixSource.REQUEST_FRESH
    }

internal fun isCoordinateValid(
    latitude: Double,
    longitude: Double,
): Boolean =
    latitude.isFinite() &&
        longitude.isFinite() &&
        latitude in -90.0..90.0 &&
        longitude in -180.0..180.0

internal fun isLocationFixUsable(
    latitude: Double,
    longitude: Double,
    accuracyMeters: Float?,
): Boolean {
    val accuracyUsable =
        accuracyMeters != null &&
            accuracyMeters.isFinite() &&
            accuracyMeters >= 0f &&
            accuracyMeters <= MAX_FIX_ACCURACY_METERS
    return isCoordinateValid(latitude = latitude, longitude = longitude) && accuracyUsable
}

internal fun anyLocationProviderEnabled(
    gpsEnabled: Boolean,
    networkEnabled: Boolean,
): Boolean = gpsEnabled || networkEnabled

internal fun locationServicesEnabled(context: Context): Boolean {
    val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
    return anyLocationProviderEnabled(
        gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER),
        networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER),
    )
}
