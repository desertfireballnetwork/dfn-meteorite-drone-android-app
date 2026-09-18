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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import au.edu.fireballs.stage4.GlobalSyncState
import au.edu.fireballs.stage4.data.repository.SyncPhase
import au.edu.fireballs.stage4.data.repository.SyncProgress

internal const val GLOBAL_ACTION_VIEW = "Tap to view"
internal const val GLOBAL_WAITING = "Sync will start when connected"
internal const val GLOBAL_FAILED = "Sync needs attention"
internal const val GLOBAL_SYNCING = "Syncing"
internal const val GLOBAL_COMPLETE = "Sync complete"
internal const val GLOBAL_PHOTO_PREFIX = "Syncing: Uploading photo"
internal const val GLOBAL_DECISION_PREFIX = "Syncing: Synchronising decision"

@Composable
fun PendingSyncSnackbar(
    state: GlobalSyncState,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = state.globalMessage() ?: return
    val actionable = state is GlobalSyncState.Pending || state is GlobalSyncState.Failed
    val clickModifier = if (actionable) Modifier.clickable(onClick = onAction) else Modifier
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .then(clickModifier),
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
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier =
                    Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
            )
            if (actionable) {
                Text(
                    text = GLOBAL_ACTION_VIEW,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun GlobalSyncState.globalMessage(): String? =
    when (this) {
        GlobalSyncState.Hidden -> null
        is GlobalSyncState.Pending -> "$decisions decisions / $photos photos pending sync"
        GlobalSyncState.WaitingForNetwork -> GLOBAL_WAITING
        is GlobalSyncState.Running -> runningMessage(progress)
        GlobalSyncState.Resuming -> GLOBAL_SYNCING
        GlobalSyncState.Complete -> GLOBAL_COMPLETE
        GlobalSyncState.Failed -> GLOBAL_FAILED
        GlobalSyncState.SessionExpired -> null
    }

private fun runningMessage(progress: SyncProgress?): String {
    if (progress == null || progress.phase == SyncPhase.Unknown) {
        return GLOBAL_SYNCING
    }
    val prefix =
        when (progress.phase) {
            SyncPhase.Photo -> GLOBAL_PHOTO_PREFIX
            SyncPhase.Decision -> GLOBAL_DECISION_PREFIX
            SyncPhase.Unknown -> GLOBAL_SYNCING
        }
    val total = progress.total
    return if (total != null && total > 0) "$prefix ${progress.done} of $total" else prefix
}
