package au.edu.fireballs.stage4.ui.screen.candidate

import androidx.lifecycle.ViewModel
import au.edu.fireballs.stage4.data.repository.CandidateImageRepository
import au.edu.fireballs.stage4.domain.model.Stage4Candidate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<CandidateUiState?>(null)
        val uiState: StateFlow<CandidateUiState?> = _uiState.asStateFlow()
        private var retryCount = 0

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
    }
