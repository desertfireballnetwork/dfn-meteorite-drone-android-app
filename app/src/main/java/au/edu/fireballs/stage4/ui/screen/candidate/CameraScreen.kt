package au.edu.fireballs.stage4.ui.screen.candidate

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.camera.core.CameraInfoUnavailableException
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import au.edu.fireballs.stage4.data.repository.ActiveEvidenceCapture
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@Composable
fun CameraScreen(
    onCapture: (Uri) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val imageCapture = remember { ImageCapture.Builder().build() }
    val scope = rememberCoroutineScope()
    var cameraError by remember { mutableStateOf(false) }
    var captureCancelled by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val listener =
            Runnable {
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview =
                        Preview
                            .Builder()
                            .build()
                            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture,
                    )
                } catch (e: CameraInfoUnavailableException) {
                    Log.w("CameraScreen", "Failed to bind camera", e)
                    cameraError = true
                } catch (e: IllegalArgumentException) {
                    Log.w("CameraScreen", "Failed to bind camera", e)
                    cameraError = true
                }
            }
        cameraProviderFuture.addListener(listener, ContextCompat.getMainExecutor(context))
        onDispose {}
    }

    Dialog(
        onDismissRequest = onCancel,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize(),
            )
            if (cameraError) {
                Text(
                    text = "Camera unavailable",
                    color = Color.White,
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(24.dp)
                            .testTag("camera-error"),
                )
            }
            Column(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            captureCancelled = true
                            onCancel()
                        },
                        modifier = Modifier.weight(1f).testTag("camera-cancel"),
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                val uri = captureImage(context, imageCapture)
                                val result = resolveCaptureResult(uri, captureCancelled)
                                if (result != null) onCapture(result)
                            }
                        },
                        modifier = Modifier.weight(1f).testTag("camera-capture"),
                    ) {
                        Text("Capture")
                    }
                }
            }
        }
    }
}

private suspend fun captureImage(
    context: Context,
    imageCapture: ImageCapture,
): Uri? {
    val captureDir = File(context.cacheDir, "evidence").apply { mkdirs() }
    val tempFile = File.createTempFile("evidence_capture_", ".jpg", captureDir)
    ActiveEvidenceCapture.mark(tempFile.absolutePath)
    val outputOptions =
        ImageCapture
            .OutputFileOptions
            .Builder(tempFile)
            .build()
    var retained = false
    try {
        return suspendCoroutine { continuation ->
            imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        continuation.resume(Uri.fromFile(tempFile))
                    }

                    override fun onError(exception: ImageCaptureException) {
                        continuation.resumeWithException(exception)
                    }
                },
            )
        }.also { retained = true }
    } catch (e: ImageCaptureException) {
        Log.w("CameraScreen", "Image capture failed", e)
        return null
    } finally {
        if (!retained) {
            releaseCapturedFile(tempFile)
        }
    }
}

private fun releaseCapturedFile(file: File) {
    file.delete()
    ActiveEvidenceCapture.clear(file.absolutePath)
}

internal fun resolveCaptureResult(
    uri: Uri?,
    cancelled: Boolean,
): Uri? {
    if (uri == null || cancelled) {
        uri?.path?.let { path ->
            File(path).delete()
            ActiveEvidenceCapture.clear(path)
        }
        return null
    }
    return uri
}
