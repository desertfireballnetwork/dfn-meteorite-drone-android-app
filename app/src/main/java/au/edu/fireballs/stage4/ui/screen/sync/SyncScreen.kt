package au.edu.fireballs.stage4.ui.screen.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import au.edu.fireballs.stage4.ui.theme.Stage4Theme

private enum class RowKind { Decision, Photo }

private enum class FailureClass { Retryable, Terminal }

private data class ReasonLabel(
    val text: String,
    val failureClass: FailureClass,
)

private data class FailedRow(
    val id: Long,
    val title: String,
    val reason: String?,
    val kind: RowKind,
)

@Composable
fun SyncScreen(
    uiState: SyncUiState,
    onSyncNow: () -> Unit,
    onDeleteDecision: (Long) -> Unit,
    onDeletePhoto: (Long) -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        SyncUiState.SessionExpired -> SessionExpiredContent(onSignIn, modifier)
        SyncUiState.Idle -> IdleContent(onSyncNow, modifier)
        SyncUiState.Complete -> CompleteContent(onSyncNow, modifier)
        is SyncUiState.Pending -> PendingContent(uiState.summary, onSyncNow, modifier)
        is SyncUiState.Running -> RunningContent(uiState, onSyncNow, modifier)
        is SyncUiState.Failed ->
            FailedContent(uiState.summary, onSyncNow, onDeleteDecision, onDeletePhoto, modifier)
    }
}

@Composable
private fun IdleContent(
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
    ) {
        Header()
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Everything is up to date",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(16.dp))
        SyncNowButton(onSyncNow)
    }
}

@Composable
private fun PendingContent(
    summary: SyncSummary,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
    ) {
        Header()
        Spacer(Modifier.height(16.dp))
        PendingCounts(summary)
        Spacer(Modifier.height(16.dp))
        SyncNowButton(onSyncNow)
        FailedRowsSection(summary)
    }
}

@Composable
private fun RunningContent(
    state: SyncUiState.Running,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
    ) {
        Header()
        Spacer(Modifier.height(16.dp))
        PendingCounts(state.summary)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Sync in progress ${state.done}/${state.total}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { if (state.total > 0) state.done.toFloat() / state.total else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        SyncNowButton(onSyncNow)
        FailedRowsSection(state.summary)
    }
}

@Composable
private fun FailedContent(
    summary: SyncSummary,
    onSyncNow: () -> Unit,
    onDeleteDecision: (Long) -> Unit,
    onDeletePhoto: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<FailedRow?>(null) }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
    ) {
        Header()
        Spacer(Modifier.height(16.dp))
        PendingCounts(summary)
        Spacer(Modifier.height(16.dp))
        SyncNowButton(onSyncNow)
        FailedRowsSection(
            summary = summary,
            onDelete = { pendingDelete = it },
        )
    }

    pendingDelete?.let { row ->
        DeleteConfirmationDialog(
            row = row,
            onConfirm = {
                when (row.kind) {
                    RowKind.Decision -> onDeleteDecision(row.id)
                    RowKind.Photo -> onDeletePhoto(row.id)
                }
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun CompleteContent(
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
    ) {
        Header()
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Sync complete",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(16.dp))
        SyncNowButton(onSyncNow)
    }
}

@Composable
private fun SessionExpiredContent(
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Session expired — sign in to resume",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onSignIn) {
            Text("Sign in")
        }
    }
}

@Composable
private fun Header() {
    Text(
        text = "Sync",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun PendingCounts(summary: SyncSummary) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CountCard(
            label = "Decisions",
            count = summary.pendingDecisions,
            modifier = Modifier.weight(1f),
        )
        CountCard(
            label = "Photos",
            count = summary.pendingPhotos,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CountCard(
    label: String,
    count: Int,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SyncNowButton(onSyncNow: () -> Unit) {
    Button(
        onClick = onSyncNow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Sync now")
    }
}

@Composable
private fun FailedRowsSection(
    summary: SyncSummary,
    onDelete: (FailedRow) -> Unit = {},
) {
    val rows = failedRows(summary)
    if (rows.isEmpty()) {
        return
    }
    Spacer(Modifier.height(24.dp))
    Text(
        text = "Failed items",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rows.forEach { row ->
            FailedRowCard(row = row, onDelete = { onDelete(row) })
        }
    }
}

@Composable
private fun FailedRowCard(
    row: FailedRow,
    onDelete: () -> Unit,
) {
    val reason = reasonLabel(row.reason)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(4.dp))
                ReasonChip(reason)
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onDelete) {
                Text("Delete")
            }
        }
    }
}

@Composable
private fun ReasonChip(reason: ReasonLabel) {
    val background =
        when (reason.failureClass) {
            FailureClass.Retryable -> Stage4Theme.colors.markerCar
            FailureClass.Terminal -> Stage4Theme.colors.markerNo
        }
    Surface(
        color = background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = reason.text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun DeleteConfirmationDialog(
    row: FailedRow,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${row.title}?") },
        text = { Text("This removes the item from the local queue.") },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("delete-confirm"),
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("delete-cancel"),
            ) {
                Text("Cancel")
            }
        },
    )
}

private fun failedRows(summary: SyncSummary): List<FailedRow> {
    val decisions =
        summary.failedDecisions.map {
            FailedRow(
                id = it.inferenceResultId,
                title = "Decision ${it.inferenceResultId}",
                reason = it.syncFailedReason,
                kind = RowKind.Decision,
            )
        }
    val photos =
        summary.failedPhotos.map {
            FailedRow(
                id = it.rowId,
                title = "Photo ${it.localFilePath.substringAfterLast('/')}",
                reason = it.uploadFailedReason,
                kind = RowKind.Photo,
            )
        }
    return decisions + photos
}

private fun reasonLabel(reason: String?): ReasonLabel {
    if (reason == null) {
        return ReasonLabel("Unknown", FailureClass.Terminal)
    }
    return when {
        reason == "claim_required" -> ReasonLabel("Claim required", FailureClass.Retryable)
        reason == "file_missing" -> ReasonLabel("Photo file missing", FailureClass.Terminal)
        reason == "cross_campaign" -> ReasonLabel("Cross-campaign", FailureClass.Terminal)
        reason.startsWith("Server returned code:") ||
            reason.startsWith("Forbidden:") ||
            reason.startsWith("Conflict:") ->
            ReasonLabel("Server error", FailureClass.Terminal)
        else -> ReasonLabel("Server error", FailureClass.Terminal)
    }
}
