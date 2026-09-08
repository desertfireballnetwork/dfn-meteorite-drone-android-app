package au.edu.fireballs.stage4.ui.screen.stage4map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LayerToggleBar(
    state: LayerToggleState,
    onToggle: (LayerType, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.width(200.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "Map Layers",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
            LayerToggleRow(
                label = "Confirmed (Yes)",
                checked = state.showYes,
                onCheckedChange = { onToggle(LayerType.YES, it) },
            )
            LayerToggleRow(
                label = "Rejected (No)",
                checked = state.showNo,
                onCheckedChange = { onToggle(LayerType.NO, it) },
            )
            LayerToggleRow(
                label = "Unprocessed",
                checked = state.showUnprocessed,
                onCheckedChange = { onToggle(LayerType.UNPROCESSED, it) },
            )
            LayerToggleRow(
                label = "Surveyed Areas",
                checked = state.showSurveyedAreas,
                onCheckedChange = { onToggle(LayerType.SURVEYED_AREAS, it) },
            )
        }
    }
}

@Composable
private fun LayerToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
