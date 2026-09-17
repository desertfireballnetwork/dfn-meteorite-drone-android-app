package au.edu.fireballs.stage4.ui.screen.dataentry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    geotiffViewModel: GeotiffRadiusViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val geotiffState by geotiffViewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
            )
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
            onRetryStorage = viewModel::retryStorage,
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
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .testTag("settings-scroll")
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
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
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                },
    )
}

@Composable
private fun StorageRow(
    row: StorageDisplayRow,
    index: Int,
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
}
