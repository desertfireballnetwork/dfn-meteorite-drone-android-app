package au.edu.fireballs.stage4.ui.screen.dataentry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import au.edu.fireballs.stage4.data.repository.StorageClearCategory
import au.edu.fireballs.stage4.data.repository.StorageClearResult
import au.edu.fireballs.stage4.data.tiles.GeotiffRadiusRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class GeotiffRadiusUiState(
    val inputText: String,
    val error: String?,
    val saved: Boolean,
)

@HiltViewModel
class GeotiffRadiusViewModel
    @Inject
    constructor(
        private val repository: GeotiffRadiusRepository,
    ) : ViewModel() {
        private val _uiState =
            MutableStateFlow(
                GeotiffRadiusUiState(
                    inputText = formatRadius(repository.getRadiusMeters()),
                    error = null,
                    saved = false,
                ),
            )
        val uiState: StateFlow<GeotiffRadiusUiState> = _uiState.asStateFlow()

        fun onRadiusInput(text: String) {
            _uiState.update {
                it.copy(inputText = text, error = validate(text), saved = false)
            }
        }

        fun save() {
            val value = _uiState.value.inputText.toFloatOrNull()
            val error = validate(_uiState.value.inputText)
            if (value == null || error != null) {
                _uiState.update { it.copy(error = error, saved = false) }
                return
            }
            repository.setRadiusMeters(value)
            _uiState.update { it.copy(error = null, saved = true) }
        }

        private fun validate(text: String): String? {
            val value = text.toFloatOrNull() ?: return "Enter a valid number"
            if (
                value < GeotiffRadiusRepository.MIN_RADIUS_METERS ||
                value > GeotiffRadiusRepository.MAX_RADIUS_METERS
            ) {
                return "Radius must be between 1 and 100 m"
            }
            return null
        }

        private fun formatRadius(value: Float): String =
            if (value == value.toInt().toFloat()) {
                value.toInt().toString()
            } else {
                value.toString()
            }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    geotiffViewModel: GeotiffRadiusViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val geotiffState by geotiffViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.logoutEvent.collect {
            onLogout()
        }
    }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshStorage()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") })
        },
    ) { innerPadding ->
        SettingsContent(
            uiState = uiState,
            geotiffState = geotiffState,
            onRadiusInput = viewModel::onRadiusInput,
            onGeotiffRadiusInput = geotiffViewModel::onRadiusInput,
            onSave = {
                viewModel.save()
                geotiffViewModel.save()
            },
            onRetryStorage = viewModel::refreshStorage,
            onClearRequested = viewModel::onClearRequested,
            onClearConfirmed = viewModel::onClearConfirmed,
            onClearCancelled = viewModel::onClearCancelled,
            onLogoutRequested = viewModel::onLogoutRequested,
            onLogoutConfirmed = viewModel::onLogoutConfirmed,
            onLogoutCancelled = viewModel::onLogoutCancelled,
            modifier =
                modifier
                    .fillMaxSize()
                    .padding(innerPadding),
        )
    }
}

@Composable
internal fun SettingsContent(
    uiState: SettingsUiState,
    geotiffState: GeotiffRadiusUiState,
    onRadiusInput: (String) -> Unit,
    onGeotiffRadiusInput: (String) -> Unit,
    onSave: () -> Unit,
    onRetryStorage: () -> Unit,
    onClearRequested: (StorageClearCategory) -> Unit,
    onClearConfirmed: () -> Unit,
    onClearCancelled: () -> Unit,
    onLogoutRequested: () -> Unit,
    onLogoutConfirmed: () -> Unit,
    onLogoutCancelled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    uiState.clear.pendingConfirmation?.let { category ->
        ClearConfirmationDialog(
            category = category,
            onConfirm = onClearConfirmed,
            onDismiss = onClearCancelled,
        )
    }

    if (uiState.showLogoutConfirmation) {
        LogoutConfirmationDialog(
            onConfirm = onLogoutConfirmed,
            onDismiss = onLogoutCancelled,
        )
    }

    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .testTag("settings-scroll")
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RadiusSettings(
            uiState = uiState,
            geotiffState = geotiffState,
            onRadiusInput = onRadiusInput,
            onGeotiffRadiusInput = onGeotiffRadiusInput,
            onSave = onSave,
        )

        Text(
            text = "Storage",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.testTag("storage-heading"),
        )

        if (uiState.storage.isLoading) {
            StorageAnnouncement(
                text = "Calculating storage usage",
                tag = "storage-loading",
                isError = false,
            )
        }

        uiState.storage.displayModel?.rows?.forEachIndexed { index, row ->
            StorageRow(
                row = row,
                index = index,
                category = row.clearCategory(),
                clearEnabled = uiState.clear.clearEnabled,
                isDownloading = uiState.clear.isDownloading,
                onClearRequested = onClearRequested,
            )
        }

        OutlinedButton(
            onClick = {
                onClearRequested(StorageClearCategory.AllCachedDownloads)
            },
            enabled = uiState.clear.clearEnabled,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("clear-all-cached-downloads"),
        ) {
            Text(
                text = "Clear all cached downloads",
                color =
                    if (uiState.clear.clearEnabled) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
            )
        }

        if (uiState.clear.isDownloading) {
            Text(
                text = "Available after download completes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("clear-download-gated"),
            )
        }

        uiState.clear.active?.let { category ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("clear-progress")
                        .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                CircularProgressIndicator()
                Text("Clearing ${category.displayName()}…")
            }
        }

        uiState.clear.lastResult?.let { result ->
            StorageAnnouncement(
                text = result.announcement(),
                tag = "clear-result",
                isError = result is StorageClearResult.Failed,
            )
        }

        uiState.storage.error?.let { error ->
            StorageAnnouncement(
                text = error.asString(),
                tag = "storage-error",
                isError = true,
            )
            Button(
                onClick = onRetryStorage,
                modifier =
                    Modifier
                        .heightIn(min = 48.dp)
                        .testTag("storage-retry"),
            ) {
                Text("Retry")
            }
        }

        OutlinedButton(
            onClick = onLogoutRequested,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("logout-button"),
            enabled = !uiState.isLoggingOut,
        ) {
            if (uiState.isLoggingOut) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                Text(
                    text = "Logout",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun LogoutConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Logout?") },
        text = {
            Text(
                "This will clear all local data including offline maps and session cookies. " +
                    "You will need an internet connection to log in again.",
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("logout-confirm"),
            ) {
                Text("Logout", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("logout-cancel"),
            ) {
                Text("Cancel")
            }
        },
        modifier = Modifier.testTag("logout-confirmation-dialog"),
    )
}

@Composable
private fun RadiusSettings(
    uiState: SettingsUiState,
    geotiffState: GeotiffRadiusUiState,
    onRadiusInput: (String) -> Unit,
    onGeotiffRadiusInput: (String) -> Unit,
    onSave: () -> Unit,
) {
    OutlinedTextField(
        value = uiState.inputText,
        onValueChange = onRadiusInput,
        label = { Text("Satellite imagery radius (m)") },
        supportingText = {
            Text("Mapbox context around candidate clusters, 1 - 2000 m")
        },
        isError = uiState.error != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    uiState.error?.let { error ->
        Text(
            text = error,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    OutlinedTextField(
        value = geotiffState.inputText,
        onValueChange = onGeotiffRadiusInput,
        label = { Text("Georeferenced imagery radius (m)") },
        supportingText = {
            Text("High-resolution candidate imagery, 1 - 100 m")
        },
        isError = geotiffState.error != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    geotiffState.error?.let { error ->
        Text(
            text = error,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (uiState.saved && geotiffState.saved) {
        Text(
            text = "Saved",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Button(
        onClick = onSave,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Save")
    }
}

@Composable
private fun ClearConfirmationDialog(
    category: StorageClearCategory,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val copy = category.dialogCopy() ?: return
    val cancelFocusRequester = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(copy.title) },
        text = { Text(copy.body) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("clear-confirm-${category.tagName()}"),
            ) {
                Text(
                    text = copy.confirmLabel,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier =
                    Modifier
                        .focusRequester(cancelFocusRequester)
                        .testTag("clear-cancel"),
            ) {
                Text("Cancel")
            }
        },
        modifier = Modifier.testTag("clear-confirmation-dialog"),
    )

    LaunchedEffect(category) {
        cancelFocusRequester.requestFocus()
    }
}

@Composable
private fun StorageAnnouncement(
    text: String,
    tag: String,
    isError: Boolean,
) {
    Text(
        text = text,
        color =
            if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        style = MaterialTheme.typography.bodyMedium,
        modifier =
            Modifier
                .testTag(tag)
                .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun StorageRow(
    row: StorageDisplayRow,
    index: Int,
    category: StorageClearCategory?,
    clearEnabled: Boolean,
    isDownloading: Boolean,
    onClearRequested: (StorageClearCategory) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("storage-row-$index")
                    .clearAndSetSemantics {
                        contentDescription = row.semanticsDescription
                    }.padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = row.category,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = row.value,
                style = MaterialTheme.typography.bodyLarge,
            )
            row.status?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        category?.let {
            TextButton(
                onClick = { onClearRequested(it) },
                enabled = clearEnabled,
                modifier =
                    Modifier
                        .heightIn(min = 48.dp)
                        .testTag("clear-${it.tagName()}"),
            ) {
                Text(
                    text =
                        if (it == StorageClearCategory.TemporaryCache) {
                            "Clear temporary cache"
                        } else {
                            "Clear"
                        },
                    color =
                        if (clearEnabled) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        },
                )
            }
            if (isDownloading) {
                Text(
                    text = "Available after download completes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class ClearDialogCopy(
    val title: String,
    val body: String,
    val confirmLabel: String,
)

private fun StorageDisplayRow.clearCategory(): StorageClearCategory? =
    when (category) {
        "GeoTIFF tiles" -> StorageClearCategory.GeotiffTiles
        "Candidate crops" -> StorageClearCategory.CandidateCrops
        "Satellite maps" -> StorageClearCategory.SatelliteMaps
        "Temporary cache" -> StorageClearCategory.TemporaryCache
        else -> null
    }

private fun StorageClearCategory.dialogCopy(): ClearDialogCopy? =
    when (this) {
        StorageClearCategory.GeotiffTiles ->
            ClearDialogCopy(
                title = "Clear geotiff tiles?",
                body =
                    "This removes downloaded candidate map tiles. They can be downloaded " +
                        "again when online. Evidence photos, pending uploads, and decisions " +
                        "will not be removed.",
                confirmLabel = "Clear tiles",
            )
        StorageClearCategory.CandidateCrops ->
            ClearDialogCopy(
                title = "Clear candidate crops?",
                body =
                    "This removes downloaded candidate preview images. They can be " +
                        "downloaded again when online. Evidence photos, pending uploads, " +
                        "and decisions will not be removed.",
                confirmLabel = "Clear crops",
            )
        StorageClearCategory.SatelliteMaps ->
            ClearDialogCopy(
                title = "Clear satellite maps?",
                body =
                    "This removes downloaded satellite map regions. They can be downloaded " +
                        "again when online. Evidence photos, pending uploads, and decisions " +
                        "will not be removed.",
                confirmLabel = "Clear maps",
            )
        StorageClearCategory.AllCachedDownloads ->
            ClearDialogCopy(
                title = "Clear all cached downloads?",
                body =
                    "This removes all downloaded geotiff tiles, candidate crops, and " +
                        "satellite maps from this device. You will need an internet " +
                        "connection to download them again. Evidence photos, pending " +
                        "uploads, and decisions will be preserved.",
                confirmLabel = "Clear cached downloads",
            )
        StorageClearCategory.TemporaryCache -> null
    }

private fun StorageClearCategory.displayName(): String =
    when (this) {
        StorageClearCategory.GeotiffTiles -> "geotiff tiles"
        StorageClearCategory.CandidateCrops -> "candidate crops"
        StorageClearCategory.SatelliteMaps -> "satellite maps"
        StorageClearCategory.TemporaryCache -> "temporary cache"
        StorageClearCategory.AllCachedDownloads -> "cached downloads"
    }

private fun StorageClearCategory.tagName(): String =
    when (this) {
        StorageClearCategory.GeotiffTiles -> "geotiff-tiles"
        StorageClearCategory.CandidateCrops -> "candidate-crops"
        StorageClearCategory.SatelliteMaps -> "satellite-maps"
        StorageClearCategory.TemporaryCache -> "temporary-cache"
        StorageClearCategory.AllCachedDownloads -> "all-cached-downloads"
    }

private fun StorageClearResult.announcement(): String =
    when (this) {
        is StorageClearResult.Cleared -> "Cleared ${category.displayName()}."
        is StorageClearResult.PartiallyCleared ->
            "Cleared ${category.displayName()}, but $pendingSatellitePurges satellite " +
                "map regions could not be removed and will be retried."
        is StorageClearResult.Failed ->
            "Could not clear ${category.displayName()}." +
                message?.let { " $it" }.orEmpty()
    }
