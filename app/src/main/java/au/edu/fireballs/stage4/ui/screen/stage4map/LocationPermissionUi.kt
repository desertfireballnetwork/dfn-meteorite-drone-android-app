package au.edu.fireballs.stage4.ui.screen.stage4map

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

private val LOCATION_PERMISSIONS =
    arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

private class LocationPermissionRequestState(
    val hadRequestedBefore: MutableState<Boolean>,
    val wasGrantedPreviously: MutableState<Boolean>,
    val dismissedStatus: MutableState<LocationPermissionStatus?>,
    val launcher: ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>,
)

@Composable
private fun rememberLocationPermissionRequestState(): LocationPermissionRequestState {
    val hadRequestedBefore = rememberSaveable { mutableStateOf(false) }
    val wasGrantedPreviously = rememberSaveable { mutableStateOf(false) }
    val dismissedStatus = rememberSaveable { mutableStateOf<LocationPermissionStatus?>(null) }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            dismissedStatus.value = null
        }

    return remember(permissionLauncher) {
        LocationPermissionRequestState(
            hadRequestedBefore = hadRequestedBefore,
            wasGrantedPreviously = wasGrantedPreviously,
            dismissedStatus = dismissedStatus,
            launcher = permissionLauncher,
        )
    }
}

@Composable
internal fun rememberLocationPermission(): LocationPermissionUiState {
    val context = LocalContext.current
    val request = rememberLocationPermissionRequestState()

    val snapshot = locationPermissionSnapshot(context)

    val decision =
        decideLocationPermission(
            fineGranted = snapshot.fineGranted,
            coarseGranted = snapshot.coarseGranted,
            hadRequestedBefore = request.hadRequestedBefore.value,
            shouldShowRationale = snapshot.shouldShowRationale,
            wasGrantedPreviously = request.wasGrantedPreviously.value,
        )

    LocationPermissionEffects(
        fineGranted = snapshot.fineGranted,
        coarseGranted = snapshot.coarseGranted,
        status = decision.status,
        shouldRequestNow = decision.shouldRequestNow,
        onPermissionGranted = { request.wasGrantedPreviously.value = true },
        onStatusNotDenied = { request.dismissedStatus.value = null },
        onAutoRequest = {
            request.hadRequestedBefore.value = true
            request.launcher.launch(LOCATION_PERMISSIONS)
        },
    )

    return LocationPermissionUiState(
        status = decision.status,
        locationPermissionGranted = snapshot.fineGranted || snapshot.coarseGranted,
        hadRequestedBefore = request.hadRequestedBefore.value,
        wasGrantedPreviously = request.wasGrantedPreviously.value,
        showDeniedNotice =
            shouldShowLocationDeniedNotice(
                status = decision.status,
                dismissedStatus = request.dismissedStatus.value,
            ),
        requestPermissions = {
            request.hadRequestedBefore.value = true
            request.launcher.launch(LOCATION_PERMISSIONS)
        },
        dismissDeniedNotice = { request.dismissedStatus.value = decision.status },
    )
}

@Composable
private fun LocationPermissionEffects(
    fineGranted: Boolean,
    coarseGranted: Boolean,
    status: LocationPermissionStatus,
    shouldRequestNow: Boolean,
    onPermissionGranted: () -> Unit,
    onStatusNotDenied: () -> Unit,
    onAutoRequest: () -> Unit,
) {
    LaunchedEffect(fineGranted, coarseGranted) {
        if (fineGranted || coarseGranted) {
            onPermissionGranted()
        }
    }

    LaunchedEffect(status) {
        if (!isLocationDeniedStatus(status)) {
            onStatusNotDenied()
        }
    }

    LaunchedEffect(shouldRequestNow) {
        if (shouldRequestNow) {
            onAutoRequest()
        }
    }
}

internal class LocationPermissionUiState(
    val status: LocationPermissionStatus,
    val locationPermissionGranted: Boolean,
    val hadRequestedBefore: Boolean,
    val wasGrantedPreviously: Boolean,
    val showDeniedNotice: Boolean,
    val requestPermissions: () -> Unit,
    val dismissDeniedNotice: () -> Unit,
)

private data class LocationPermissionSnapshot(
    val fineGranted: Boolean,
    val coarseGranted: Boolean,
    val shouldShowRationale: Boolean,
)

fun isLocationPermissionGranted(context: Context): Boolean {
    val snapshot = locationPermissionSnapshot(context)
    return snapshot.fineGranted || snapshot.coarseGranted
}

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

private fun permissionRationaleVisible(
    context: Context,
    permission: String,
): Boolean {
    val activity = context as? Activity ?: return false
    return ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
}
