package au.edu.fireballs.stage4.ui.screen.dataentry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.edu.fireballs.stage4.data.repository.StorageCoordinator
import au.edu.fireballs.stage4.data.repository.StorageMutationState
import au.edu.fireballs.stage4.data.repository.StorageUsageSnapshot
import au.edu.fireballs.stage4.data.tiles.BufferRadiusRepository
import au.edu.fireballs.stage4.ui.util.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import javax.inject.Inject

data class SettingsStorageUiState(
    val displayModel: StorageDisplayModel? = null,
    val isLoading: Boolean = true,
    val error: UiText? = null,
)

data class StorageDisplayModel(
    val rows: List<StorageDisplayRow>,
)

data class StorageDisplayRow(
    val category: String,
    val value: String,
    val status: String? = null,
) {
    val semanticsDescription: String
        get() = listOfNotNull(category, value, status).joinToString()
}

data class SettingsUiState(
    val currentRadius: Float,
    val inputText: String,
    val error: String?,
    val saved: Boolean,
    val storage: SettingsStorageUiState = SettingsStorageUiState(),
)

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val bufferRadiusRepository: BufferRadiusRepository,
        private val storageCoordinator: StorageCoordinator,
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

        private var refreshInFlight = false
        private var refreshPending = false

        init {
            observeStorageMutations()
        }

        fun load() {
            val current = bufferRadiusRepository.getBufferRadiusMeters()
            _uiState.update {
                it.copy(
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

        private fun observeStorageMutations() {
            viewModelScope.launch {
                var previous: StorageMutationState? = null
                storageCoordinator.state.collect { current ->
                    val completed =
                        previous != null &&
                            previous !is StorageMutationState.Idle &&
                            current is StorageMutationState.Idle
                    previous = current
                    if (completed) {
                        refreshStorage()
                    }
                }
            }
        }

        fun refreshStorage() {
            if (refreshInFlight) {
                refreshPending = true
                return
            }
            refreshInFlight = true
            _uiState.update {
                it.copy(
                    storage =
                        it.storage.copy(
                            isLoading = true,
                            error = null,
                        ),
                )
            }
            viewModelScope.launch {
                try {
                    val displayModel = storageCoordinator.snapshot().toDisplayModel()
                    _uiState.update {
                        it.copy(
                            storage =
                                SettingsStorageUiState(
                                    displayModel = displayModel,
                                    isLoading = false,
                                    error = null,
                                ),
                        )
                    }
                } catch (_: Exception) {
                    _uiState.update {
                        it.copy(
                            storage =
                                it.storage.copy(
                                    isLoading = false,
                                    error =
                                        UiText.DynamicString(
                                            "Could not calculate storage usage",
                                        ),
                                ),
                        )
                    }
                } finally {
                    refreshInFlight = false
                    if (refreshPending) {
                        refreshPending = false
                        refreshStorage()
                    }
                }
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

internal fun StorageUsageSnapshot.toDisplayModel(): StorageDisplayModel {
    val regions = "$mapboxRegionCount ${if (mapboxRegionCount == 1) "region" else "regions"}"
    val satelliteValue = mapboxBytes?.let(::formatSettingsBytes) ?: regions
    val satelliteStatus = if (mapboxBytes == null) "Size unavailable" else regions
    return StorageDisplayModel(
        rows =
            listOf(
                StorageDisplayRow(
                    category = "Device free space",
                    value =
                        "${formatSettingsBytes(availableVolumeBytes)} free of " +
                            formatSettingsBytes(totalVolumeBytes),
                ),
                StorageDisplayRow(
                    category = "Known cached downloads",
                    value = formatSettingsBytes(knownCachedDownloadBytes),
                ),
                StorageDisplayRow(
                    category = "GeoTIFF tiles",
                    value = formatSettingsBytes(geotiffBytes),
                ),
                StorageDisplayRow(
                    category = "Candidate crops",
                    value = formatSettingsBytes(candidateCropBytes),
                ),
                StorageDisplayRow(
                    category = "Satellite maps",
                    value = satelliteValue,
                    status = satelliteStatus,
                ),
                StorageDisplayRow(
                    category = "Evidence photos",
                    value = formatSettingsBytes(evidenceBytes),
                    status = "Preserved",
                ),
                StorageDisplayRow(
                    category = "Temporary cache",
                    value = formatSettingsBytes(ownedTempCacheBytes),
                ),
            ),
    )
}

internal fun formatSettingsBytes(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0L)
    if (safeBytes < 1024L) {
        return "$safeBytes B"
    }
    val units = listOf("KB", "MB", "GB", "TB")
    var value = safeBytes.toDouble()
    var unitIndex = -1
    do {
        value /= 1024.0
        unitIndex++
    } while (value >= 1024.0 && unitIndex < units.lastIndex)
    return "${DecimalFormat("0.#").format(value)} ${units[unitIndex]}"
}
