package au.edu.fireballs.stage4.ui.screen.basecamp

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import au.edu.fireballs.stage4.ui.screen.stage4map.isLocationPermissionGranted
import au.edu.fireballs.stage4.ui.screen.stage4map.locationServicesEnabled
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.delay

private const val LOCATION_FIX_TIMEOUT_MS = 15_000L
private const val SUCCESS_DURATION_MS = 2_000L
private const val SPINNER_SIZE_DP = 20

private val LOCATION_PERMISSIONS =
    arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

@Composable
fun SetCarLocationButton(
    onSetLocation: (Double, Double) -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    var acquiring by remember { mutableStateOf(false) }
    var showSuccess by remember { mutableStateOf(false) }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { granted ->
            if (granted.values.none { it }) {
                onMessage("Location permission required to set car location")
            } else {
                acquireLocation(
                    context = context,
                    onSetLocation = onSetLocation,
                    onMessage = onMessage,
                    onAcquiring = { acquiring = it },
                    onSuccess = { showSuccess = true },
                )
            }
        }

    LaunchedEffect(showSuccess) {
        if (showSuccess) {
            delay(SUCCESS_DURATION_MS)
            showSuccess = false
        }
    }

    IconButton(
        onClick = {
            if (!isLocationPermissionGranted(context)) {
                permissionLauncher.launch(LOCATION_PERMISSIONS)
            } else {
                acquireLocation(
                    context = context,
                    onSetLocation = onSetLocation,
                    onMessage = onMessage,
                    onAcquiring = { acquiring = it },
                    onSuccess = { showSuccess = true },
                )
            }
        },
        enabled = !acquiring,
    ) {
        when {
            acquiring ->
                CircularProgressIndicator(modifier = Modifier.size(SPINNER_SIZE_DP.dp))

            showSuccess ->
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Car location set",
                )

            else ->
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = "Set my location as car location",
                )
        }
    }
}

private fun acquireLocation(
    context: Context,
    onSetLocation: (Double, Double) -> Unit,
    onMessage: (String) -> Unit,
    onAcquiring: (Boolean) -> Unit,
    onSuccess: () -> Unit,
) {
    if (!locationServicesEnabled(context)) {
        onMessage("Location services are turned off")
        return
    }
    val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    onAcquiring(true)
    try {
        fusedLocationClient
            .getCurrentLocation(
                CurrentLocationRequest
                    .Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setDurationMillis(LOCATION_FIX_TIMEOUT_MS)
                    .build(),
                CancellationTokenSource().token,
            ).addOnSuccessListener { location ->
                onAcquiring(false)
                if (location != null) {
                    onSetLocation(location.latitude, location.longitude)
                    onSuccess()
                } else {
                    onMessage("Couldn't get GPS location")
                }
            }.addOnFailureListener {
                onAcquiring(false)
                onMessage("Couldn't get GPS location")
            }
    } catch (_: SecurityException) {
        onAcquiring(false)
        onMessage("Couldn't get GPS location")
    }
}
