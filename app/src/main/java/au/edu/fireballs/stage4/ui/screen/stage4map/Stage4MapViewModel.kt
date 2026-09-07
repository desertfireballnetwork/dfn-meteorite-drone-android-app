package au.edu.fireballs.stage4.ui.screen.stage4map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.edu.fireballs.stage4.data.local.LocalDecisionEntity
import au.edu.fireballs.stage4.data.local.dao.LocalDecisionDao
import au.edu.fireballs.stage4.data.repository.Stage4FetchResult
import au.edu.fireballs.stage4.data.repository.Stage4Repository
import au.edu.fireballs.stage4.domain.model.MapCameraTarget
import au.edu.fireballs.stage4.domain.model.Stage4Candidate
import au.edu.fireballs.stage4.domain.model.Stage4State
import au.edu.fireballs.stage4.domain.model.resolveInitialCamera
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LayerToggleState(
    val showYes: Boolean = true,
    val showNo: Boolean = true,
    val showUnprocessed: Boolean = true,
    val showSurveyedAreas: Boolean = true,
)

enum class LayerType {
    YES,
    NO,
    UNPROCESSED,
    SURVEYED_AREAS,
}

sealed interface Stage4MapUiState {
    data object Loading : Stage4MapUiState

    data class Loaded(
        val state: Stage4State,
        val cameraTarget: MapCameraTarget?,
        val layerToggleState: LayerToggleState = LayerToggleState(),
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
        private val localDecisionDao: LocalDecisionDao,
    ) : ViewModel() {
        private val layerToggleStateFlow = MutableStateFlow(LayerToggleState())
        private val sourceStateFlow = MutableStateFlow<Stage4State?>(null)
        private val isRefreshingFlow = MutableStateFlow(false)
        private val userMessageFlow = MutableStateFlow<String?>(null)
        private val errorFlow = MutableStateFlow<String?>(null)
        private val authExpiredFlow = MutableStateFlow(false)

        private val surveyIdFlow = MutableStateFlow<Long?>(null)

        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState: StateFlow<Stage4MapUiState> =
            surveyIdFlow
                .flatMapLatest { id ->
                    if (id == null) {
                        combine(errorFlow, authExpiredFlow) { error, authExpired ->
                            when {
                                authExpired -> Stage4MapUiState.AuthExpired
                                error != null -> Stage4MapUiState.Error(error)
                                else -> Stage4MapUiState.Loading
                            }
                        }
                    } else {
                        val loadedFlow =
                            combine(
                                sourceStateFlow,
                                localDecisionDao.observeDecisionsForSurvey(id),
                                layerToggleStateFlow,
                                isRefreshingFlow,
                                userMessageFlow,
                            ) { source, decisions, toggles, refreshing, msg ->
                                source?.let {
                                    Stage4MapUiState.Loaded(
                                        state = mergeStateWithDecisions(it, decisions),
                                        cameraTarget = resolveInitialCamera(it),
                                        layerToggleState = toggles,
                                        isRefreshing = refreshing,
                                        userMessage = msg,
                                    )
                                }
                            }

                        combine(
                            loadedFlow,
                            errorFlow,
                            authExpiredFlow,
                        ) { loaded, error, authExpired ->
                            when {
                                authExpired -> Stage4MapUiState.AuthExpired
                                error != null -> Stage4MapUiState.Error(error)
                                loaded != null -> loaded
                                else -> Stage4MapUiState.Loading
                            }
                        }
                    }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000),
                    initialValue = Stage4MapUiState.Loading,
                )

        private var fetchJob: Job? = null

        fun toggleLayer(
            type: LayerType,
            visible: Boolean,
        ) {
            layerToggleStateFlow.value =
                when (type) {
                    LayerType.YES -> layerToggleStateFlow.value.copy(showYes = visible)
                    LayerType.NO -> layerToggleStateFlow.value.copy(showNo = visible)
                    LayerType.UNPROCESSED ->
                        layerToggleStateFlow.value.copy(
                            showUnprocessed = visible,
                        )

                    LayerType.SURVEYED_AREAS ->
                        layerToggleStateFlow.value.copy(
                            showSurveyedAreas = visible,
                        )
                }
        }

        fun openSurvey(surveyId: Long) {
            fetchJob?.cancel()

            fetchJob =
                viewModelScope.launch {
                    val currentState = sourceStateFlow.value
                    val refreshInPlace = currentState != null && currentState.survey.id == surveyId

                    if (refreshInPlace) {
                        isRefreshingFlow.value = true
                        userMessageFlow.value = null
                    } else {
                        // Clear source state and errors so the combine pipeline resets cleanly
                        sourceStateFlow.value = null
                        errorFlow.value = null
                        authExpiredFlow.value = false
                        surveyIdFlow.value = surveyId
                    }

                    when (val result = stage4Repository.getCandidatesState(surveyId)) {
                        is Stage4FetchResult.Success -> {
                            sourceStateFlow.value = result.state
                            errorFlow.value = null
                            isRefreshingFlow.value = false
                        }

                        is Stage4FetchResult.Error -> {
                            if (refreshInPlace) {
                                isRefreshingFlow.value = false
                                userMessageFlow.value =
                                    result.message ?: "Failed to refresh survey map"
                            } else {
                                errorFlow.value =
                                    result.message ?: "Failed to load survey candidates"
                            }
                        }

                        is Stage4FetchResult.NetworkError -> {
                            if (refreshInPlace) {
                                isRefreshingFlow.value = false
                                userMessageFlow.value = "Offline — displaying previous map data"
                            } else {
                                errorFlow.value = "Network error. Please check your connection."
                            }
                        }

                        is Stage4FetchResult.AuthExpired -> {
                            authExpiredFlow.value = true
                        }
                    }
                }
        }

        private fun mergeStateWithDecisions(
            state: Stage4State,
            decisions: List<LocalDecisionEntity>,
        ): Stage4State {
            val decisionMap = decisions.associateBy { it.inferenceResultId }
            val yesIds = state.yesMeteorites.mapTo(mutableSetOf()) { it.inferenceResultId }
            val noIds = state.noMeteorites.mapTo(mutableSetOf()) { it.inferenceResultId }
            val allCandidates =
                (state.unprocessedCandidates + state.yesMeteorites + state.noMeteorites)
                    .distinctBy { it.inferenceResultId }

            val newUnprocessed = mutableListOf<Stage4Candidate>()
            val newYes = mutableListOf<Stage4Candidate>()
            val newNo = mutableListOf<Stage4Candidate>()

            allCandidates.forEach { candidate ->
                val decision = decisionMap[candidate.inferenceResultId]
                when {
                    decision?.verdict == true -> newYes.add(candidate)
                    decision != null -> newNo.add(candidate)
                    candidate.inferenceResultId in yesIds -> newYes.add(candidate)
                    candidate.inferenceResultId in noIds -> newNo.add(candidate)
                    else -> newUnprocessed.add(candidate)
                }
            }
            }

            return state.copy(
                unprocessedCandidates = newUnprocessed,
                yesMeteorites = newYes,
                noMeteorites = newNo,
            )
        }

        fun retry() {
            val surveyId = surveyIdFlow.value ?: return
            openSurvey(surveyId)
        }
    }
