package au.edu.fireballs.stage4.ui.screen.surveylist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.edu.fireballs.stage4.data.repository.SurveyFetchResult
import au.edu.fireballs.stage4.data.repository.SurveyRepository
import au.edu.fireballs.stage4.domain.model.Survey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SurveyListUiState {
    data object Loading : SurveyListUiState

    data class Loaded(
        val surveys: List<Survey>,
        val isRefreshing: Boolean = false,
        val userMessage: String? = null,
    ) : SurveyListUiState

    data object Empty : SurveyListUiState

    data class Error(
        val message: String,
    ) : SurveyListUiState

    data object AuthExpired : SurveyListUiState
}

@HiltViewModel
class SurveyListViewModel
    @Inject
    constructor(
        private val surveyRepository: SurveyRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<SurveyListUiState>(SurveyListUiState.Loading)
        val uiState: StateFlow<SurveyListUiState> = _uiState.asStateFlow()

        private var fetchJob: Job? = null

        fun loadSurveys(isPullToRefresh: Boolean = false) {
            // cancelling the previous call if any
            fetchJob?.cancel()

            fetchJob =
                viewModelScope.launch {
                    val currentState = _uiState.value

                    if (currentState is SurveyListUiState.Loaded) {
                        _uiState.value = currentState.copy(isRefreshing = true, userMessage = null)
                    } else if (!isPullToRefresh) {
                        _uiState.value = SurveyListUiState.Loading
                    }

                    when (val result = surveyRepository.getSurveys()) {
                        is SurveyFetchResult.Success -> {
                            if (result.surveys.isEmpty()) {
                                _uiState.value = SurveyListUiState.Empty
                            } else {
                                _uiState.value =
                                    SurveyListUiState.Loaded(
                                        surveys = result.surveys,
                                        isRefreshing = false,
                                        userMessage = null,
                                    )
                            }
                        }

                        is SurveyFetchResult.Error -> {
                            if (currentState is SurveyListUiState.Loaded) {
                                _uiState.value =
                                    currentState.copy(
                                        isRefreshing = false,
                                        userMessage =
                                            result.message ?: "Failed to update survey list",
                                    )
                            } else {
                                _uiState.value =
                                    SurveyListUiState.Error(
                                        result.message ?: "Failed to load surveys",
                                    )
                            }
                        }

                        is SurveyFetchResult.NetworkError -> {
                            if (currentState is SurveyListUiState.Loaded) {
                                _uiState.value =
                                    currentState.copy(
                                        isRefreshing = false,
                                        userMessage = "Offline — displaying previous survey list",
                                    )
                            } else {
                                _uiState.value =
                                    SurveyListUiState.Error(
                                        "Network error. Please check your connection.",
                                    )
                            }
                        }

                        is SurveyFetchResult.AuthExpired -> {
                            _uiState.value = SurveyListUiState.AuthExpired
                        }
                    }
                }
        }

        fun userMessageShown() {
            val currentState = _uiState.value
            if (currentState is SurveyListUiState.Loaded) {
                _uiState.value = currentState.copy(userMessage = null)
            }
        }
    }
