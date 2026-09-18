package au.edu.fireballs.stage4.ui.screen.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private const val ACTION_SYNC_NOW = "Sync now"
private const val ACTION_SYNCING = "Syncing…"
private const val COPY_WAITING = "Waiting for network"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    uiState: SyncUiState,
    onSyncNow: () -> Unit,
    onDeleteDecision: (Long) -> Unit,
    onDeletePhoto: (Long) -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = "Sync") },
            )
        },
    ) { paddingValues ->
        val contentModifier = Modifier.padding(paddingValues)
        when (uiState) {
            SyncUiState.SessionExpired -> SessionExpiredContent(onSignIn, contentModifier)
            SyncUiState.Idle -> IdleContent(onSyncNow, contentModifier)
            SyncUiState.Complete -> CompleteContent(onSyncNow, contentModifier)
            is SyncUiState.Pending ->
                PendingContent(
                    summary = uiState.summary,
                    onSyncNow = onSyncNow,
                    onDeleteDecision = onDeleteDecision,
                    onDeletePhoto = onDeletePhoto,
                    modifier = contentModifier,
                )
            is SyncUiState.Failed ->
                FailedContent(
                    summary = uiState.summary,
                    onSyncNow = onSyncNow,
                    onDeleteDecision = onDeleteDecision,
                    onDeletePhoto = onDeletePhoto,
                    modifier = contentModifier,
                )
            is SyncUiState.Running ->
                ActiveContent(
                    summary = uiState.summary,
                    statusText = progressCopy(uiState.progress),
                    progressFraction = progressFraction(uiState.progress),
                    onSyncNow = onSyncNow,
                    onDeleteDecision = onDeleteDecision,
                    onDeletePhoto = onDeletePhoto,
                    modifier = contentModifier,
                )
            is SyncUiState.Resuming ->
                ActiveContent(
                    summary = uiState.summary,
                    statusText = COPY_SYNCHRONISING,
                    progressFraction = null,
                    onSyncNow = onSyncNow,
                    onDeleteDecision = onDeleteDecision,
                    onDeletePhoto = onDeletePhoto,
                    modifier = contentModifier,
                )
            is SyncUiState.WaitingForNetwork ->
                ActiveContent(
                    summary = uiState.summary,
                    statusText = COPY_WAITING,
                    progressFraction = null,
                    onSyncNow = onSyncNow,
                    onDeleteDecision = onDeleteDecision,
                    onDeletePhoto = onDeletePhoto,
                    modifier = contentModifier,
                )
        }
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
    onDeleteDecision: (Long) -> Unit,
    onDeletePhoto: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        PendingCounts(summary)
        Spacer(Modifier.height(16.dp))
        SyncNowButton(onSyncNow)
        FailedRowsWithDelete(summary, true, onDeleteDecision, onDeletePhoto)
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
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        PendingCounts(summary)
        Spacer(Modifier.height(16.dp))
        SyncNowButton(onSyncNow)
        FailedRowsWithDelete(summary, true, onDeleteDecision, onDeletePhoto)
    }
}

@Composable
private fun ActiveContent(
    summary: SyncSummary,
    statusText: String,
    progressFraction: Float?,
    onSyncNow: () -> Unit,
    onDeleteDecision: (Long) -> Unit,
    onDeletePhoto: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        PendingCounts(summary)
        Spacer(Modifier.height(16.dp))
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            modifier =
                Modifier
                    .testTag("sync-status")
                    .semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(8.dp))
        SyncProgressIndicator(progressFraction)
        Spacer(Modifier.height(16.dp))
        SyncNowButton(onSyncNow, label = ACTION_SYNCING, enabled = false)
        FailedRowsWithDelete(summary, false, onDeleteDecision, onDeletePhoto)
    }
}

@Composable
private fun SyncProgressIndicator(progressFraction: Float?) {
    val indicatorModifier =
        Modifier
            .fillMaxWidth()
            .testTag("sync-progress")
    if (progressFraction == null) {
        LinearProgressIndicator(modifier = indicatorModifier)
    } else {
        LinearProgressIndicator(
            progress = { progressFraction },
            modifier = indicatorModifier,
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
private fun SyncNowButton(
    onSyncNow: () -> Unit,
    label: String = ACTION_SYNC_NOW,
    enabled: Boolean = true,
) {
    Button(
        onClick = onSyncNow,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label)
    }
}
