package au.edu.fireballs.stage4.ui.screen.candidate

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.edu.fireballs.stage4.data.local.PendingPhotoUploadEntity
import au.edu.fireballs.stage4.data.remote.dto.EvidencePhotoDto
import au.edu.fireballs.stage4.data.repository.CandidateImageRepository
import au.edu.fireballs.stage4.data.repository.DecisionRepository
import au.edu.fireballs.stage4.data.repository.EvidenceFetchResult
import au.edu.fireballs.stage4.data.repository.EvidencePhotoRepository
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

sealed interface EvidenceGalleryUiState {
    data object Loading : EvidenceGalleryUiState

    data class Content(
        val photos: List<EvidencePhotoDto>,
    ) : EvidenceGalleryUiState

    data object Empty : EvidenceGalleryUiState

    data class Error(
        val message: String? = null,
    ) : EvidenceGalleryUiState

    data object Offline : EvidenceGalleryUiState

    data object AuthExpired : EvidenceGalleryUiState
}

@HiltViewModel
class CandidateViewModel
    @Inject
    constructor(
        private val imageRepository: CandidateImageRepository,
        private val decisionRepository: DecisionRepository,
        private val evidencePhotoRepository: EvidencePhotoRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<CandidateUiState?>(null)
        val uiState: StateFlow<CandidateUiState?> = _uiState.asStateFlow()

        private val _verdict = MutableStateFlow<Boolean?>(null)
        val verdict: StateFlow<Boolean?> = _verdict.asStateFlow()

        private val _detectionTagId = MutableStateFlow<Long?>(null)
        val detectionTagId: StateFlow<Long?> = _detectionTagId.asStateFlow()

        private val _photoGalleryState =
            MutableStateFlow<List<PendingPhotoUploadEntity>>(emptyList())
        val photoGalleryState: StateFlow<List<PendingPhotoUploadEntity>> =
            _photoGalleryState.asStateFlow()

        private val _serverGalleryState =
            MutableStateFlow<EvidenceGalleryUiState>(EvidenceGalleryUiState.Loading)
        val serverGalleryState: StateFlow<EvidenceGalleryUiState> =
            _serverGalleryState.asStateFlow()

        private var retryCount = 0
        private var verdictJob: Job? = null
        private var galleryJob: Job? = null
        private var serverGalleryJob: Job? = null
        private var currentCandidateId: Long? = null

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

            galleryJob?.cancel()
            galleryJob =
                viewModelScope.launch {
                    evidencePhotoRepository
                        .getLocalPhotosForCandidate(candidate.inferenceResultId)
                        .collect { photos -> _photoGalleryState.value = photos }
                }

            val candidateId = candidate.inferenceResultId
            if (currentCandidateId != candidateId) {
                currentCandidateId = candidateId
                fetchServerGallery(candidateId, surveyId)
            }
        }

        fun retryServerGallery() {
            val state = _uiState.value ?: return
            fetchServerGallery(
                candidateId = state.candidate.inferenceResultId,
                surveyId = state.surveyId,
            )
        }

        private fun fetchServerGallery(
            candidateId: Long,
            surveyId: Long,
        ) {
            serverGalleryJob?.cancel()
            _serverGalleryState.value = EvidenceGalleryUiState.Loading
            serverGalleryJob =
                viewModelScope.launch {
                    val result =
                        evidencePhotoRepository.fetchServerEvidence(
                            surveyId = surveyId,
                            inferenceResultId = candidateId,
                        )
                    _serverGalleryState.value =
                        when (result) {
                            is EvidenceFetchResult.Success ->
                                if (result.photos.isEmpty()) {
                                    EvidenceGalleryUiState.Empty
                                } else {
                                    EvidenceGalleryUiState.Content(result.photos)
                                }
                            is EvidenceFetchResult.Error ->
                                EvidenceGalleryUiState.Error(result.message)
                            EvidenceFetchResult.AuthExpired ->
                                EvidenceGalleryUiState.AuthExpired
                            EvidenceFetchResult.NetworkError ->
                                EvidenceGalleryUiState.Offline
                            else -> EvidenceGalleryUiState.Error()
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

        fun onPhotoCaptured(uri: Uri) {
            savePhotoLocally(uri, deleteSource = true)
        }

        fun onPhotoPicked(uri: Uri) {
            savePhotoLocally(uri)
        }

        private fun savePhotoLocally(
            uri: Uri,
            deleteSource: Boolean = false,
        ) {
            val state = _uiState.value ?: return
            viewModelScope.launch {
                evidencePhotoRepository.saveLocally(
                    uri = uri,
                    surveyId = state.surveyId,
                    inferenceResultId = state.candidate.inferenceResultId,
                    deleteSource = deleteSource,
                )
            }
        }
    }
