package au.edu.fireballs.stage4.ui.screen.basecamp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import au.edu.fireballs.stage4.data.repository.ClaimRepository
import au.edu.fireballs.stage4.data.repository.OfflineWorkingSetRepository
import au.edu.fireballs.stage4.data.repository.PreDownloadPreflightResult
import au.edu.fireballs.stage4.data.repository.PreDownloadSpaceEstimate
import au.edu.fireballs.stage4.data.repository.PreDownloadStoragePreflight
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
        val geotiffPresentCount: Int,
        val geotiffMissingCount: Int,
        val cropPresentCount: Int,
        val cropMissingCount: Int,
        val satellitePresentCount: Int,
        val satelliteMissingCount: Int,
        val estimatedIncrementalBytes: Long,
        val availableBytes: Long,
        val reserveBytes: Long,
        val expectedRemainingBytes: Long,
        val canStart: Boolean,
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

@HiltViewModel
class PreDownloadViewModel
    @Inject
    constructor(
        private val claimRepository: ClaimRepository,
        private val workingSetRepository: OfflineWorkingSetRepository,
        private val stage4Repository: Stage4Repository,
        private val bufferRadiusRepository: BufferRadiusRepository,
        private val geotiffRadiusRepository: GeotiffRadiusRepository,
        private val preflight: PreDownloadStoragePreflight,
        private val preDownloadWorkManager: PreDownloadWorkManager,
    ) : ViewModel() {
        private val _workingSetState =
            MutableStateFlow<WorkingSetUiState>(WorkingSetUiState.None)
        val workingSetState: StateFlow<WorkingSetUiState> = _workingSetState.asStateFlow()

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
                when (val active = workingSetRepository.activeReplacement()) {
                    null -> _workingSetState.value = WorkingSetUiState.None
                    else ->
                        _workingSetState.value =
                            WorkingSetUiState.ResumeAvailable(
                                manifestId = active.manifestId,
                                missingCount = active.missingCount,
                            )
                }
                claimRepository.refreshClaimsToRoom(surveyId)
                val claimed = claimRepository.countActiveClaimedCandidates(surveyId)
                val isStale = isLocalDataStale(surveyId)
                val result =
                    preflight.evaluate(
                        surveyId,
                        bufferRadiusRepository.getBufferRadiusMeters().toDouble(),
                        geotiffRadiusRepository.getRadiusMeters().toDouble(),
                        isStale,
                    )
                _uiState.value =
                    when (result) {
                        is PreDownloadPreflightResult.Allowed ->
                            result.estimate.toReadyState(
                                claimedCandidateCount = claimed,
                                canStart = true,
                                isStale = isStale,
                            )

                        is PreDownloadPreflightResult.InsufficientDeviceSpace ->
                            result.estimate.toReadyState(
                                claimedCandidateCount = claimed,
                                canStart = false,
                                isStale = isStale,
                            )

                        PreDownloadPreflightResult.StorageOperationActive ->
                            PreDownloadUiState.Error(
                                "Another storage operation is already running",
                            )
                    }
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
                        PreDownloadWorkManager.inputData(
                            surveyId = currentSurveyId,
                            bufferMeters = bufferMeters,
                            geotiffRadiusMeters = geotiffRadiusMeters,
                            manifestId = null,
                        ),
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

        fun resumeReplacement(manifestId: String) {
            val currentSurveyId = surveyId
            if (currentSurveyId < 0L) return
            val request =
                OneTimeWorkRequestBuilder<PreDownloadWorker>()
                    .setInputData(
                        PreDownloadWorkManager.inputData(
                            surveyId = currentSurveyId,
                            bufferMeters = bufferRadiusRepository.getBufferRadiusMeters(),
                            geotiffRadiusMeters =
                                geotiffRadiusRepository.getRadiusMeters(),
                            manifestId = manifestId,
                        ),
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
                    val output = info.outputData
                    val code = output.getString(PreDownloadOrchestrator.KEY_ERROR_CODE)
                    val message =
                        when (code) {
                            PreDownloadOrchestrator.CODE_INSUFFICIENT_DEVICE_SPACE -> {
                                val required =
                                    formatBytes(
                                        output.getLong(
                                            PreDownloadOrchestrator.KEY_REQUIRED_BYTES,
                                            0,
                                        ),
                                    )
                                val available =
                                    formatBytes(
                                        output.getLong(
                                            PreDownloadOrchestrator.KEY_AVAILABLE_BYTES,
                                            0,
                                        ),
                                    )
                                val reserve =
                                    formatBytes(
                                        output.getLong(
                                            PreDownloadOrchestrator.KEY_RESERVE_BYTES,
                                            0,
                                        ),
                                    )
                                "Not enough device space to prepare this survey offline. " +
                                    "Needs about $required, $available available, " +
                                    "$reserve must remain free."
                            }

                            PreDownloadOrchestrator.CODE_STORAGE_FULL_WHILE_WRITING ->
                                "Device storage became full during download. " +
                                    "Free some space, then retry."

                            PreDownloadOrchestrator.CODE_STORAGE_OPERATION_ACTIVE ->
                                "Another storage operation is already running. " +
                                    "Let it finish, then retry."

                            else ->
                                output.getString(PreDownloadOrchestrator.KEY_ERROR)
                                    ?: "Download failed"
                        }
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

        private fun PreDownloadSpaceEstimate.toReadyState(
            claimedCandidateCount: Int,
            canStart: Boolean,
            isStale: Boolean,
        ): PreDownloadUiState.Ready =
            PreDownloadUiState.Ready(
                claimedCandidateCount = claimedCandidateCount,
                geotiffPresentCount = inventory.geotiffPresentCount,
                geotiffMissingCount = inventory.geotiffMissingCount,
                cropPresentCount = inventory.cropPresentCount,
                cropMissingCount = inventory.cropMissingCount,
                satellitePresentCount = inventory.satellitePresentCount,
                satelliteMissingCount = inventory.satelliteMissingCount,
                estimatedIncrementalBytes = incrementalRequiredBytes,
                availableBytes = availableBytes,
                reserveBytes = reserveBytes,
                expectedRemainingBytes = expectedRemainingBytes,
                canStart = canStart,
                isStale = isStale,
            )

        private fun formatBytes(bytes: Long): String {
            val units = listOf("B", "KB", "MB", "GB", "TB")
            var value = bytes.toDouble()
            var unit = 0
            while (value >= 1024 && unit < units.lastIndex) {
                value /= 1024
                unit++
            }
            return if (unit == 0) {
                "$bytes ${units[unit]}"
            } else {
                "%.1f %s".format(value, units[unit])
            }
        }

        private fun uniqueWorkName(surveyId: Long): String =
            PreDownloadWorkManager.UNIQUE_WORK_PREFIX + surveyId
    }
