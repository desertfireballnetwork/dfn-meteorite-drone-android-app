package au.edu.fireballs.stage4.ui.screen.stage4map

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun OfflineBundleGuard(
    hasBundle: Boolean,
    onOpenDownloads: () -> Unit,
) {
    if (hasBundle) return
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "No offline data downloaded — connect to basecamp WiFi to download",
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(modifier = Modifier.width(4.dp))
            TextButton(onClick = onOpenDownloads) {
                Text(text = "Open Downloads")
            }
        }
    }
}
