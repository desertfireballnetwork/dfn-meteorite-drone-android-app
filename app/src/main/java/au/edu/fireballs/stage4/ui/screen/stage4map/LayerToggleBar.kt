package au.edu.fireballs.stage4.ui.screen.stage4map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal const val LAYER_MENU_BUTTON_TAG = "layer-menu-button"
internal const val LAYER_MENU_PANEL_TAG = "layer-menu-panel"

@Composable
fun LayerToggleBar(
    state: LayerToggleState,
    onToggle: (LayerType, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    if (expanded) {
        LayerTogglePanel(
            state = state,
            onToggle = onToggle,
            onCollapse = { expanded = false },
            modifier = modifier,
        )
    } else {
        SmallFloatingActionButton(
            onClick = { expanded = true },
            modifier = modifier.testTag(LAYER_MENU_BUTTON_TAG),
        ) {
            Icon(imageVector = Icons.Filled.Layers, contentDescription = "Map layers")
        }
    }
}

@Composable
private fun LayerTogglePanel(
    state: LayerToggleState,
    onToggle: (LayerType, Boolean) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier =
            modifier
                .width(200.dp)
                .testTag(LAYER_MENU_PANEL_TAG),
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Map Layers",
                    style = MaterialTheme.typography.titleSmall,
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                )
                IconButton(onClick = onCollapse) {
                    Icon(imageVector = Icons.Filled.Close, contentDescription = "Close map layers")
                }
            }
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
