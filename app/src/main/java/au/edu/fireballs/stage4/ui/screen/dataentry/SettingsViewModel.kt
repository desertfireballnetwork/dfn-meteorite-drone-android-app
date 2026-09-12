package au.edu.fireballs.stage4.ui.screen.dataentry

import androidx.lifecycle.ViewModel
import au.edu.fireballs.stage4.data.tiles.BufferRadiusRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class SettingsUiState(
    val currentRadius: Float,
    val inputText: String,
    val error: String?,
    val saved: Boolean,
)

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val bufferRadiusRepository: BufferRadiusRepository,
    ) : ViewModel() {
        private val _uiState =
            MutableStateFlow(
                SettingsUiState(
                    currentRadius = bufferRadiusRepository.getBufferRadiusMeters(),
                    inputText = formatRadius(bufferRadiusRepository.getBufferRadiusMeters()),
                    error = null,
                    saved = false,
                ),
            )
        val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

        fun load() {
            val current = bufferRadiusRepository.getBufferRadiusMeters()
            _uiState.update {
                SettingsUiState(
                    currentRadius = current,
                    inputText = formatRadius(current),
                    error = null,
                    saved = false,
                )
            }
        }

        fun onRadiusInput(text: String) {
            _uiState.update {
                it.copy(
                    inputText = text,
                    error = validate(text),
                    saved = false,
                )
            }
        }

        fun save() {
            val text = _uiState.value.inputText
            val error = validate(text)
            if (error != null) {
                _uiState.update { it.copy(error = error, saved = false) }
                return
            }
            val value = text.toFloatOrNull() ?: return
            bufferRadiusRepository.setBufferRadiusMeters(value)
            _uiState.update {
                it.copy(
                    currentRadius = value,
                    error = null,
                    saved = true,
                )
            }
        }

        private fun validate(text: String): String? {
            val value = text.toFloatOrNull()
            if (value == null) {
                return "Enter a valid number"
            }
            if (value < 1.0f || value > 2000.0f) {
                return "Radius must be between 1 and 2000 m"
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
