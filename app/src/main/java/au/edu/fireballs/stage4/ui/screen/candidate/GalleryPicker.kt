package au.edu.fireballs.stage4.ui.screen.candidate

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

internal class GalleryPicker(
    val launch: () -> Unit,
)

@Composable
internal fun rememberGalleryPicker(onImagePicked: (Uri) -> Unit): GalleryPicker {
    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent(),
        ) { uri ->
            if (uri != null) onImagePicked(uri)
        }
    return GalleryPicker(
        launch = { launcher.launch("image/jpeg") },
    )
}
