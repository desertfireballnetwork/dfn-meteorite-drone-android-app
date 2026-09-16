package au.edu.fireballs.stage4.ui.screen.dataentry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
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
    val error = uiState.error

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = uiState.inputText,
                onValueChange = viewModel::onRadiusInput,
                label = { Text("Satellite imagery radius (m)") },
                supportingText = {
                    Text("Mapbox context around candidate clusters, 1 - 2000 m")
                },
                isError = error != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            if (error != null) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            OutlinedTextField(
                value = geotiffState.inputText,
                onValueChange = geotiffViewModel::onRadiusInput,
                label = { Text("Georeferenced imagery radius (m)") },
                supportingText = {
                    Text("High-resolution candidate imagery, 1 - 100 m")
                },
                isError = geotiffState.error != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            geotiffState.error?.let { geotiffError ->
                Text(
                    text = geotiffError,
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
                onClick = {
                    viewModel.save()
                    geotiffViewModel.save()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }
        }
    }
}
