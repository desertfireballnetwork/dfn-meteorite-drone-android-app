package au.edu.fireballs.stage4.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import au.edu.fireballs.stage4.GlobalSyncState

private const val LABEL_PENDING = "pending"
private const val LABEL_WAITING = "waiting"
private const val LABEL_SYNCING = "syncing"
private const val LABEL_ATTENTION = "attention"

@Composable
fun PendingSyncBadge(
    pendingCount: Int,
    modifier: Modifier = Modifier,
) {
    if (pendingCount <= 0) {
        return
    }
    StatusBadge(
        label = LABEL_PENDING,
        description = "$pendingCount pending",
        badgeText = if (pendingCount > 99) "99+" else pendingCount.toString(),
        modifier = modifier,
    )
}

@Composable
fun PendingSyncBadge(
    state: GlobalSyncState,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        GlobalSyncState.Hidden,
        GlobalSyncState.Complete,
        GlobalSyncState.SessionExpired,
        -> return

        is GlobalSyncState.Pending -> {
            val total = state.decisions + state.photos
            if (total <= 0) {
                return
            }
            StatusBadge(
                label = LABEL_PENDING,
                description = "$total pending",
                badgeText = if (total > 99) "99+" else total.toString(),
                modifier = modifier,
            )
        }

        GlobalSyncState.WaitingForNetwork ->
            StatusBadge(
                label = LABEL_WAITING,
                description = "Sync will start when connected",
                modifier = modifier,
            )

        is GlobalSyncState.Running,
        GlobalSyncState.Resuming,
        ->
            StatusBadge(
                label = LABEL_SYNCING,
                description = "Syncing",
                modifier = modifier,
            )

        GlobalSyncState.Failed ->
            StatusBadge(
                label = LABEL_ATTENTION,
                description = "Sync needs attention",
                modifier = modifier,
                onClick = onAction,
            )
    }
}

@Composable
private fun StatusBadge(
    label: String,
    description: String,
    modifier: Modifier = Modifier,
    badgeText: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    BadgedBox(
        modifier =
            modifier
                .then(clickModifier)
                .semantics { contentDescription = description },
        badge = {
            if (badgeText != null) {
                Badge {
                    Text(text = badgeText)
                }
            }
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
