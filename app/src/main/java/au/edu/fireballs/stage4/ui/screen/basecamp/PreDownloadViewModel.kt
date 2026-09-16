package au.edu.fireballs.stage4.ui.screen.basecamp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import au.edu.fireballs.stage4.data.repository.ClaimRepository
import au.edu.fireballs.stage4.data.repository.Stage4Repository
import au.edu.fireballs.stage4.data.tiles.BufferRadiusRepository
import au.edu.fireballs.stage4.data.tiles.GeotiffRadiusRepository
import au.edu.fireballs.stage4.sync.PreDownloadOrchestrator
import au.edu.fireballs.stage4.sync.PreDownloadWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PreDownloadUiState {
    data object Idle : PreDownloadUiState

    data class Ready(
        val claimedCandidateCount: Int,
        val estimatedSizeBytes: Long,
        val isStale: Boolean = false,
    ) : PreDownloadUiState

    data class Running(
        val done: Int,
        val total: Int,
        val phase: String,
    ) : PreDownloadUiState

    data class Done(
        val bundleId: Long,
        val tileCount: Int,
        val cropCount: Int,
        val satelliteRegionCount: Int,
        val candidateCount: Int,
        val reDownloadRecommended: Boolean,
    ) : PreDownloadUiState

    data class Error(
        val message: String,
    ) : PreDownloadUiState

    data object Cancelled : PreDownloadUiState
}

sealed interface PreDownloadEvent {
    data class ShowMessage(
        val message: String,
    ) : PreDownloadEvent
}

private const val GEOTIFF_BYTES_PER_CANDIDATE = 32_000_000L
private const val CROP_BYTES_PER_CANDIDATE = 1_000_000L
private const val SATELLITE_BYTES_PER_CANDIDATE = 300_000L

@HiltViewModel
class PreDownloadViewModel
    @Inject
    constructor(
        private val claimRepository: ClaimRepository,
        private val stage4Repository: Stage4Repository,
        private val bufferRadiusRepository: BufferRadiusRepository,
        private val geotiffRadiusRepository: GeotiffRadiusRepository,
        private val preDownloadWorkManager: PreDownloadWorkManager,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<PreDownloadUiState>(PreDownloadUiState.Idle)
        val uiState: StateFlow<PreDownloadUiState> = _uiState.asStateFlow()

        private val _events = MutableSharedFlow<PreDownloadEvent>(extraBufferCapacity = 8)
        val events: SharedFlow<PreDownloadEvent> = _events.asSharedFlow()

        private var surveyId: Long = -1L
        private var observeJob: Job? = null

        fun openSurvey(surveyId: Long) {
            this.surveyId = surveyId
            observeJob?.cancel()
            observeJob = null
            viewModelScope.launch {
                claimRepository.refreshClaimsToRoom(surveyId)
                val claimed = claimRepository.countActiveClaimedCandidates(surveyId)
                _uiState.value =
                    PreDownloadUiState.Ready(
                        claimedCandidateCount = claimed,
                        estimatedSizeBytes = estimateSize(claimed),
                        isStale = isLocalDataStale(surveyId),
                    )
            }
        }

        fun startDownload() {
            val currentSurveyId = surveyId
            if (currentSurveyId < 0L) return
            val bufferMeters = bufferRadiusRepository.getBufferRadiusMeters()
            val geotiffRadiusMeters = geotiffRadiusRepository.getRadiusMeters()
            val request =
                OneTimeWorkRequestBuilder<PreDownloadWorker>()
                    .setInputData(
                        Data
                            .Builder()
                            .putLong(PreDownloadWorker.KEY_SURVEY_ID, currentSurveyId)
                            .putFloat(PreDownloadWorker.KEY_BUFFER_METERS, bufferMeters)
                            .putFloat(
                                PreDownloadWorker.KEY_GEOTIFF_RADIUS_METERS,
                                geotiffRadiusMeters,
                            ).build(),
                    ).build()
            preDownloadWorkManager.enqueueUniqueWork(
                uniqueWorkName = uniqueWorkName(currentSurveyId),
                existingWorkPolicy = ExistingWorkPolicy.REPLACE,
                request = request,
            )
            observeJob?.cancel()
            observeJob =
                viewModelScope.launch {
                    preDownloadWorkManager
                        .getWorkInfoByIdFlow(request.id)
                        .collect { info -> info?.let { handleWorkInfo(it) } }
                }
        }

        fun cancel() {
            val currentSurveyId = surveyId
            if (currentSurveyId < 0L) return
            observeJob?.cancel()
            observeJob = null
            preDownloadWorkManager.cancelUniqueWork(uniqueWorkName(currentSurveyId))
            _uiState.value = PreDownloadUiState.Cancelled
        }

        private suspend fun handleWorkInfo(info: WorkInfo) {
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING ->
                    _uiState.value =
                        PreDownloadUiState.Running(
                            done = info.progress.getInt(PreDownloadOrchestrator.KEY_DONE, 0),
                            total = info.progress.getInt(PreDownloadOrchestrator.KEY_TOTAL, 0),
                            phase =
                                info.progress.getString(PreDownloadOrchestrator.KEY_PHASE)
                                    ?: "",
                        )

                WorkInfo.State.SUCCEEDED -> {
                    val output = info.outputData
                    val reDownloadRecommended = isReDownloadRecommended(output)
                    if (reDownloadRecommended) {
                        _events.tryEmit(
                            PreDownloadEvent.ShowMessage(
                                "Re-download recommended — ML task changed",
                            ),
                        )
                    }
                    _uiState.value =
                        PreDownloadUiState.Done(
                            bundleId = output.getLong(PreDownloadOrchestrator.KEY_BUNDLE_ID, -1L),
                            tileCount = output.getInt(PreDownloadOrchestrator.KEY_TILE_COUNT, 0),
                            cropCount = output.getInt(PreDownloadOrchestrator.KEY_CROP_COUNT, 0),
                            satelliteRegionCount =
                                output.getInt(
                                    PreDownloadOrchestrator.KEY_SATELLITE_REGION_COUNT,
                                    0,
                                ),
                            candidateCount =
                                output.getInt(PreDownloadOrchestrator.KEY_CANDIDATE_COUNT, 0),
                            reDownloadRecommended = reDownloadRecommended,
                        )
                    observeJob?.cancel()
                    observeJob = null
                }

                WorkInfo.State.FAILED -> {
                    val message =
                        info.outputData.getString(PreDownloadOrchestrator.KEY_ERROR)
                            ?: "Download failed"
                    _uiState.value = PreDownloadUiState.Error(message)
                    observeJob?.cancel()
                    observeJob = null
                }

                WorkInfo.State.CANCELLED -> {
                    _uiState.value = PreDownloadUiState.Cancelled
                    observeJob?.cancel()
                    observeJob = null
                }

                else -> Unit
            }
        }

        private fun isReDownloadRecommended(output: Data): Boolean =
            output.getBoolean(PreDownloadOrchestrator.KEY_RE_DOWNLOAD_RECOMMENDED, false)

        private suspend fun isLocalDataStale(surveyId: Long): Boolean {
            val local = stage4Repository.getLocalLatestTaskCreated(surveyId) ?: return false
            val fresh = stage4Repository.fetchLatestTaskCreated(surveyId) ?: return false
            return fresh != local
        }

        private fun estimateSize(claimedCount: Int): Long {
            val candidates = claimedCount.toLong()
            val geotiff = candidates * GEOTIFF_BYTES_PER_CANDIDATE
            val crops = candidates * CROP_BYTES_PER_CANDIDATE
            val satellite = candidates * SATELLITE_BYTES_PER_CANDIDATE
            return geotiff + crops + satellite
        }

        private fun uniqueWorkName(surveyId: Long): String =
            PreDownloadWorkManager.UNIQUE_WORK_PREFIX + surveyId
    }
