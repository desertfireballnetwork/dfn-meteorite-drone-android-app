package au.edu.fireballs.stage4.ui.screen.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
internal fun FailedRowsWithDelete(
    summary: SyncSummary,
    deletesEnabled: Boolean,
    onDeleteDecision: (Long) -> Unit,
    onDeletePhoto: (Long) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<FailedRow?>(null) }

    FailedRowsSection(
        summary = summary,
        deletesEnabled = deletesEnabled,
        onDelete = { if (deletesEnabled) pendingDelete = it },
    )

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
private fun FailedRowsSection(
    summary: SyncSummary,
    deletesEnabled: Boolean,
    onDelete: (FailedRow) -> Unit,
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
    Spacer(Modifier.height(4.dp))
    Text(
        text = "${rows.size} failed items",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rows.forEach { row ->
            FailedRowCard(
                row = row,
                deletesEnabled = deletesEnabled,
                onDelete = { onDelete(row) },
            )
        }
    }
}

@Composable
private fun FailedRowCard(
    row: FailedRow,
    deletesEnabled: Boolean,
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
            TextButton(
                onClick = onDelete,
                enabled = deletesEnabled,
                modifier = Modifier.testTag("delete-${row.kind}-${row.id}"),
            ) {
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
                title =
                    "Photo ${it.inferenceResultId}: ${it.localFilePath.substringAfterLast('/')}",
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
