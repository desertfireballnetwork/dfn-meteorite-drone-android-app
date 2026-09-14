package au.edu.fireballs.stage4.ui.sync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun PendingSyncSnackbar(
    pendingDecisions: Int,
    pendingPhotos: Int,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pendingDecisions + pendingPhotos <= 0) {
        return
    }
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onTap),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.medium,
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = "$pendingDecisions decisions / $pendingPhotos photos pending sync",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Tap to view",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
