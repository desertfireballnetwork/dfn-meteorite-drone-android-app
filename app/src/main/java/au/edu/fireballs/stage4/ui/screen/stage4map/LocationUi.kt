package au.edu.fireballs.stage4.ui.screen.stage4map

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.mapbox.geojson.Point
import com.mapbox.maps.dsl.cameraOptions
import com.mapbox.maps.extension.compose.animation.viewport.MapViewportState
import com.mapbox.maps.plugin.animation.MapAnimationOptions

internal const val OVERLAY_PADDING_DP = 12

private const val LOCATION_ANIMATION_DURATION_MS = 500L
private const val LOCATION_FIX_TIMEOUT_MS = 15_000L
private const val LOCATION_FAILURE_MESSAGE = "Couldn't get your location"

private val LOCATION_PERMISSIONS =
    arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

@Composable
internal fun rememberLocationPermission(): LocationPermissionUiState {
    val context = LocalContext.current

    var hadRequestedBefore by rememberSaveable { mutableStateOf(false) }
    var wasGrantedPreviously by rememberSaveable { mutableStateOf(false) }
    var deniedNoticeVisible by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissions ->
            val granted = permissions.values.any { it }
            deniedNoticeVisible = !granted
        }

    val snapshot = locationPermissionSnapshot(context)

    val decision =
        decideLocationPermission(
            fineGranted = snapshot.fineGranted,
            coarseGranted = snapshot.coarseGranted,
            hadRequestedBefore = hadRequestedBefore,
            shouldShowRationale = snapshot.shouldShowRationale,
            wasGrantedPreviously = wasGrantedPreviously,
        )

    LaunchedEffect(snapshot.fineGranted, snapshot.coarseGranted) {
        if (snapshot.fineGranted || snapshot.coarseGranted) {
            wasGrantedPreviously = true
        }
    }

    LaunchedEffect(decision.shouldRequestNow) {
        if (decision.shouldRequestNow) {
            hadRequestedBefore = true
            permissionLauncher.launch(LOCATION_PERMISSIONS)
        }
    }

    return LocationPermissionUiState(
        status = decision.status,
        locationPermissionGranted = snapshot.fineGranted || snapshot.coarseGranted,
        showDeniedNotice = shouldShowDeniedNotice(deniedNoticeVisible, decision.status),
        requestPermissions = {
            hadRequestedBefore = true
            permissionLauncher.launch(LOCATION_PERMISSIONS)
        },
        dismissDeniedNotice = { deniedNoticeVisible = false },
    )
}

internal class LocationPermissionUiState(
    val status: LocationPermissionStatus,
    val locationPermissionGranted: Boolean,
    val showDeniedNotice: Boolean,
    val requestPermissions: () -> Unit,
    val dismissDeniedNotice: () -> Unit,
)

private data class LocationPermissionSnapshot(
    val fineGranted: Boolean,
    val coarseGranted: Boolean,
    val shouldShowRationale: Boolean,
)

private fun locationPermissionSnapshot(context: Context): LocationPermissionSnapshot {
    val fineGranted =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    val coarseGranted =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    val shouldShowRationale =
        permissionRationaleVisible(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            permissionRationaleVisible(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    return LocationPermissionSnapshot(
        fineGranted = fineGranted,
        coarseGranted = coarseGranted,
        shouldShowRationale = shouldShowRationale,
    )
}

private fun shouldShowDeniedNotice(
    deniedNoticeVisible: Boolean,
    status: LocationPermissionStatus,
): Boolean =
    deniedNoticeVisible &&
        status != LocationPermissionStatus.GRANTED &&
        status != LocationPermissionStatus.COARSE_ONLY

@Composable
internal fun MapTopOverlay(
    loaded: Stage4MapUiState.Loaded,
    locationPermission: LocationPermissionUiState,
    locating: Boolean,
    locationMessage: String?,
    onDismissMessage: () -> Unit,
) {
    val context = LocalContext.current

    Column(
        modifier =
            Modifier
                .statusBarsPadding()
                .padding(OVERLAY_PADDING_DP.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SurveyInfoChip(loaded = loaded)
        if (locating) {
            Spacer(modifier = Modifier.height(8.dp))
            LocatingChip()
        }
        locationMessage?.let { message ->
            Spacer(modifier = Modifier.height(8.dp))
            LocationMessageChip(message = message, onDismiss = onDismissMessage)
        }
        if (locationPermission.showDeniedNotice) {
            Spacer(modifier = Modifier.height(8.dp))
            LocationDeniedNotice(
                status = locationPermission.status,
                onRequestAgain = locationPermission.requestPermissions,
                onOpenSettings = { openAppSettings(context) },
                onDismiss = locationPermission.dismissDeniedNotice,
            )
        }
    }
}

@Composable
private fun SurveyInfoChip(loaded: Stage4MapUiState.Loaded) {
    val state = loaded.state
    val totalCandidates =
        state.unprocessedCandidates.size + state.yesMeteorites.size + state.noMeteorites.size

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = state.survey.eventId,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "$totalCandidates candidates loaded",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun LocatingChip() {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Locating...",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun LocationMessageChip(
    message: String,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        tonalElevation = 2.dp,
        onClick = onDismiss,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun LocationDeniedNotice(
    status: LocationPermissionStatus,
    onRequestAgain: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = deniedNoticeText(status),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            when (status) {
                LocationPermissionStatus.DENIED_RATIONALE_AVAILABLE,
                LocationPermissionStatus.REVOKED,
                -> {
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(onClick = onRequestAgain) {
                        Text(text = "Allow")
                    }
                }

                LocationPermissionStatus.DENIED_PERMANENT -> {
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(onClick = onOpenSettings) {
                        Text(text = "Open settings")
                    }
                }

                LocationPermissionStatus.GRANTED,
                LocationPermissionStatus.COARSE_ONLY,
                LocationPermissionStatus.NEVER_REQUESTED,
                -> Unit
            }
            IconButton(onClick = onDismiss) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = "Dismiss")
            }
        }
    }
}

private fun deniedNoticeText(status: LocationPermissionStatus): String =
    when (status) {
        LocationPermissionStatus.DENIED_PERMANENT -> "Location permission disabled"
        LocationPermissionStatus.REVOKED -> "Location permission was revoked"
        else -> "Location permission declined"
    }

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
) {
    if (!isLocationPermissionGranted(context)) {
        onLocatingChanged(false)
        onMessage("Location permission required to find your position")
        return
    }

    val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    onLocatingChanged(true)
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
            cached?.let { flyToLocation(mapViewportState, it) }
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
                if (location != null) {
                    flyToLocation(mapViewportState, location)
                } else {
                    onMessage(LOCATION_FAILURE_MESSAGE)
                }
            }.addOnFailureListener {
                reportLocationFailure(onLocatingChanged, onMessage)
            }
    } catch (_: SecurityException) {
        reportLocationFailure(onLocatingChanged, onMessage)
    }
}

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

private fun isLocationPermissionGranted(context: Context): Boolean {
    val fineGranted =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    val coarseGranted =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    return fineGranted || coarseGranted
}

private fun permissionRationaleVisible(
    context: Context,
    permission: String,
): Boolean {
    val activity = context as? Activity ?: return false
    return ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
}

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ),
    )
}
