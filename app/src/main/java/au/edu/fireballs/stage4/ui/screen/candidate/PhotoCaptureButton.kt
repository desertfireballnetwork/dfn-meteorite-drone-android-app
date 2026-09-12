package au.edu.fireballs.stage4.ui.screen.candidate

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

@Composable
fun PhotoCaptureButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.testTag("photo-capture-button"),
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
        )
        Text("Add photo")
    }
}
