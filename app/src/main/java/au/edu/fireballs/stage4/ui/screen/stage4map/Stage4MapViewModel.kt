package au.edu.fireballs.stage4.ui.screen.stage4map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.edu.fireballs.stage4.data.repository.Stage4FetchResult
import au.edu.fireballs.stage4.data.repository.Stage4Repository
import au.edu.fireballs.stage4.domain.model.MapCameraTarget
import au.edu.fireballs.stage4.domain.model.Stage4State
import au.edu.fireballs.stage4.domain.model.resolveInitialCamera
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface Stage4MapUiState {
    data object Loading : Stage4MapUiState

    data class Loaded(
        val state: Stage4State,
        val cameraTarget: MapCameraTarget?,
        val isRefreshing: Boolean = false,
        val userMessage: String? = null,
    ) : Stage4MapUiState

    data class Error(
        val message: String,
    ) : Stage4MapUiState

    data object AuthExpired : Stage4MapUiState
}

@HiltViewModel
class Stage4MapViewModel
    @Inject
    constructor(
        private val stage4Repository: Stage4Repository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<Stage4MapUiState>(Stage4MapUiState.Loading)
        val uiState: StateFlow<Stage4MapUiState> = _uiState.asStateFlow()

        private var fetchJob: Job? = null
        private var lastSurveyId: Long? = null

        fun openSurvey(surveyId: Long) {
            lastSurveyId = surveyId
            fetchJob?.cancel()

            fetchJob =
                viewModelScope.launch {
                    val currentState = _uiState.value
                    val refreshInPlace =
                        currentState is Stage4MapUiState.Loaded &&
                            currentState.state.survey.id == surveyId

                    if (refreshInPlace) {
                        _uiState.value = currentState.copy(isRefreshing = true, userMessage = null)
                    } else {
                        _uiState.value = Stage4MapUiState.Loading
                    }

                    when (val result = stage4Repository.getCandidatesState(surveyId)) {
                        is Stage4FetchResult.Success -> {
                            _uiState.value =
                                Stage4MapUiState.Loaded(
                                    state = result.state,
                                    cameraTarget = resolveInitialCamera(result.state),
                                )
                        }

                        is Stage4FetchResult.Error -> {
                            if (refreshInPlace) {
                                _uiState.value =
                                    currentState.copy(
                                        isRefreshing = false,
                                        userMessage =
                                            result.message ?: "Failed to refresh survey map",
                                    )
                            } else {
                                _uiState.value =
                                    Stage4MapUiState.Error(
                                        result.message ?: "Failed to load survey candidates",
                                    )
                            }
                        }

                        is Stage4FetchResult.NetworkError -> {
                            if (refreshInPlace) {
                                _uiState.value =
                                    currentState.copy(
                                        isRefreshing = false,
                                        userMessage = "Offline — displaying previous map data",
                                    )
                            } else {
                                _uiState.value =
                                    Stage4MapUiState.Error(
                                        "Network error. Please check your connection.",
                                    )
                            }
                        }

                        is Stage4FetchResult.AuthExpired -> {
                            _uiState.value = Stage4MapUiState.AuthExpired
                        }
                    }
                }
        }

        fun retry() {
            val surveyId = lastSurveyId ?: return
            openSurvey(surveyId)
        }
    }
