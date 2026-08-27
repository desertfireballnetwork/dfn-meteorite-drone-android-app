package au.edu.fireballs.stage4.ui.screen.stage4map

internal enum class LocationPermissionStatus {
    GRANTED,
    COARSE_ONLY,
    NEVER_REQUESTED,
    DENIED_RATIONALE_AVAILABLE,
    DENIED_PERMANENT,
    REVOKED,
}

internal data class LocationPermissionDecision(
    val status: LocationPermissionStatus,
    val shouldRequestNow: Boolean,
)

internal fun shouldAutoRequestLocationPermission(
    permissionGranted: Boolean,
    hadRequestedBefore: Boolean,
    shouldShowRationale: Boolean,
): Boolean = !permissionGranted && !hadRequestedBefore && !shouldShowRationale

internal fun decideLocationPermission(
    fineGranted: Boolean,
    coarseGranted: Boolean,
    hadRequestedBefore: Boolean,
    shouldShowRationale: Boolean,
    wasGrantedPreviously: Boolean,
): LocationPermissionDecision {
    val granted = fineGranted || coarseGranted
    val status =
        when {
            fineGranted -> LocationPermissionStatus.GRANTED
            coarseGranted -> LocationPermissionStatus.COARSE_ONLY
            !hadRequestedBefore && !shouldShowRationale -> LocationPermissionStatus.NEVER_REQUESTED
            shouldShowRationale -> LocationPermissionStatus.DENIED_RATIONALE_AVAILABLE
            wasGrantedPreviously -> LocationPermissionStatus.REVOKED
            else -> LocationPermissionStatus.DENIED_PERMANENT
        }
    return LocationPermissionDecision(
        status = status,
        shouldRequestNow =
            shouldAutoRequestLocationPermission(
                permissionGranted = granted,
                hadRequestedBefore = hadRequestedBefore,
                shouldShowRationale = shouldShowRationale,
            ),
    )
}
