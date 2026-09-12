package au.edu.fireballs.stage4.ui.screen.candidate

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

internal class CameraPermissionRequester(
    val granted: Boolean,
    val showDenied: Boolean,
    val request: () -> Unit,
    val dismissDenied: () -> Unit,
)

@Composable
internal fun rememberCameraPermission(): CameraPermissionRequester {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(isCameraGranted(context)) }
    var showDenied by remember { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { result ->
            granted = result
            showDenied = !result
        }
    return CameraPermissionRequester(
        granted = granted,
        showDenied = showDenied,
        request = { launcher.launch(Manifest.permission.CAMERA) },
        dismissDenied = { showDenied = false },
    )
}

private fun isCameraGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
