package au.edu.fireballs.stage4.ui.screen.basecamp

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import au.edu.fireballs.stage4.ui.screen.stage4map.isLocationPermissionGranted
import au.edu.fireballs.stage4.ui.screen.stage4map.locationServicesEnabled
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

private const val LOCATION_FIX_TIMEOUT_MS = 15_000L
private const val SPINNER_SIZE_DP = 18

private val LOCATION_PERMISSIONS =
    arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

internal fun fineLocationGranted(granted: Map<String, Boolean>): Boolean =
    granted[Manifest.permission.ACCESS_FINE_LOCATION] == true

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetCarLocationButton(
    showSuccess: Boolean,
    submitting: Boolean,
    onSetLocation: (Double, Double) -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    var acquiring by remember { mutableStateOf(false) }
    var showConfirmation by rememberSaveable { mutableStateOf(false) }
    val tokenSource = remember { CancellationTokenSource() }

    DisposableEffect(Unit) {
        onDispose { tokenSource.cancel() }
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { granted ->
            if (!fineLocationGranted(granted)) {
                onMessage("Location permission required to set car location")
            } else {
                acquireLocation(
                    context = context,
                    tokenSource = tokenSource,
                    onSetLocation = onSetLocation,
                    onMessage = onMessage,
                    onAcquiring = { acquiring = it },
                )
            }
        }

    val requestCurrentLocation = {
        if (!isLocationPermissionGranted(context)) {
            permissionLauncher.launch(LOCATION_PERMISSIONS)
        } else {
            acquireLocation(
                context = context,
                tokenSource = tokenSource,
                onSetLocation = onSetLocation,
                onMessage = onMessage,
                onAcquiring = { acquiring = it },
            )
        }
    }
    val busy = acquiring || submitting

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = {
            PlainTooltip {
                Text(text = "Set car location")
            }
        },
        state = rememberTooltipState(),
    ) {
        IconButton(
            onClick = { showConfirmation = true },
            enabled = !busy,
        ) {
            when {
                busy -> {
                    CircularProgressIndicator(
                        modifier =
                            Modifier
                                .size(SPINNER_SIZE_DP.dp)
                                .semantics {
                                    contentDescription = "Setting car location"
                                },
                    )
                }

                showSuccess -> {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Car location set",
                    )
                }

                else -> {
                    Icon(
                        imageVector = Icons.Filled.DirectionsCar,
                        contentDescription = "Set car location",
                    )
                }
            }
        }
    }

    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = { Text(text = "Set car location?") },
            text = {
                Text(
                    text =
                        "Use this device's current location as the car location " +
                            "for this survey?",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmation = false
                        requestCurrentLocation()
                    },
                ) {
                    Text(text = "Set location")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmation = false }) {
                    Text(text = "Cancel")
                }
            },
        )
    }
}

private fun acquireLocation(
    context: Context,
    tokenSource: CancellationTokenSource,
    onSetLocation: (Double, Double) -> Unit,
    onMessage: (String) -> Unit,
    onAcquiring: (Boolean) -> Unit,
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
                tokenSource.token,
            ).addOnSuccessListener { location ->
                onAcquiring(false)
                if (location != null) {
                    onSetLocation(location.latitude, location.longitude)
                } else {
                    onMessage("Couldn't get GPS location")
                }
            }.addOnFailureListener {
                if (!tokenSource.token.isCancellationRequested) {
                    onAcquiring(false)
                    onMessage("Couldn't get GPS location")
                }
            }
    } catch (_: SecurityException) {
        onAcquiring(false)
        onMessage("Couldn't get GPS location")
    }
}
