package au.edu.fireballs.stage4.ui.screen.candidate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.edu.fireballs.stage4.data.repository.CandidateImageRepository
import au.edu.fireballs.stage4.data.repository.DecisionRepository
import au.edu.fireballs.stage4.domain.model.Stage4Candidate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class CandidateViewMode {
    MAP,
    IMAGE,
}

data class CandidateUiState(
    val candidate: Stage4Candidate,
    val surveyId: Long,
    val viewMode: CandidateViewMode = CandidateViewMode.MAP,
    val croppedImageModel: Any? = null,
    val tileUrlPattern: String = "",
)

@HiltViewModel
class CandidateViewModel
    @Inject
    constructor(
        private val imageRepository: CandidateImageRepository,
        private val decisionRepository: DecisionRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<CandidateUiState?>(null)
        val uiState: StateFlow<CandidateUiState?> = _uiState.asStateFlow()

        private val _verdict = MutableStateFlow<Boolean?>(null)
        val verdict: StateFlow<Boolean?> = _verdict.asStateFlow()

        private val _detectionTagId = MutableStateFlow<Long?>(null)
        val detectionTagId: StateFlow<Long?> = _detectionTagId.asStateFlow()

        private var retryCount = 0
        private var verdictJob: Job? = null

        fun initialize(
            candidate: Stage4Candidate,
            surveyId: Long,
        ) {
            val currentMode =
                _uiState.value?.let { current ->
                    if (current.candidate.inferenceResultId == candidate.inferenceResultId) {
                        current.viewMode
                    } else {
                        CandidateViewMode.MAP
                    }
                } ?: CandidateViewMode.MAP

            _uiState.value =
                CandidateUiState(
                    candidate = candidate,
                    surveyId = surveyId,
                    viewMode = currentMode,
                    croppedImageModel =
                        imageRepository.buildCroppedImageRequest(
                            inferenceResultId = candidate.inferenceResultId,
                            surveyId = surveyId,
                        ),
                    tileUrlPattern =
                        imageRepository.getCandidateTileUrlPattern(
                            surveyId = surveyId,
                            inferenceResultId = candidate.inferenceResultId,
                        ),
                )

            verdictJob?.cancel()
            verdictJob =
                viewModelScope.launch {
                    decisionRepository
                        .getVerdict(candidate.inferenceResultId)
                        .collect { decision ->
                            if (decision == null) {
                                _verdict.value = null
                                _detectionTagId.value = null
                            } else {
                                _verdict.value = decision.verdict
                                _detectionTagId.value = decision.detectionTagId
                            }
                        }
                }
        }

        fun setViewMode(mode: CandidateViewMode) {
            _uiState.update { current ->
                current?.copy(viewMode = mode)
            }
        }

        fun retryImage() {
            val current = _uiState.value ?: return
            retryCount++
            _uiState.update { state ->
                state?.copy(
                    croppedImageModel =
                        imageRepository.buildCroppedImageRequest(
                            inferenceResultId = state.candidate.inferenceResultId,
                            surveyId = state.surveyId,
                            retryKey = retryCount,
                        ),
                )
            }
        }

        fun submitVerdict(
            isMeteorite: Boolean,
            tagId: Long?,
        ) {
            val state = _uiState.value ?: return
            val effectiveTagId = if (isMeteorite) null else tagId
            _verdict.value = isMeteorite
            _detectionTagId.value = effectiveTagId
            viewModelScope.launch {
                decisionRepository
                    .upsertVerdict(
                        inferenceResultId = state.candidate.inferenceResultId,
                        surveyId = state.surveyId,
                        isMeteorite = isMeteorite,
                        detectionTagId = effectiveTagId,
                    ).first()
            }
        }

        fun clearVerdict() {
            val state = _uiState.value ?: return
            _verdict.value = null
            _detectionTagId.value = null
            viewModelScope.launch {
                decisionRepository
                    .clearVerdict(state.candidate.inferenceResultId)
                    .first()
            }
        }
    }
