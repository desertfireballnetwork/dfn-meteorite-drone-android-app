package au.edu.fireballs.stage4.ui.screen.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import au.edu.fireballs.stage4.data.local.LocalDecisionEntity
import au.edu.fireballs.stage4.data.local.PendingPhotoUploadEntity
import au.edu.fireballs.stage4.data.local.dao.LocalDecisionDao
import au.edu.fireballs.stage4.data.local.dao.PendingPhotoUploadDao
import au.edu.fireballs.stage4.data.repository.SelectedSurveyRepository
import au.edu.fireballs.stage4.sync.SyncOrchestrator
import au.edu.fireballs.stage4.sync.SyncWorker
import au.edu.fireballs.stage4.ui.screen.stage4map.SyncWorkManager
import au.edu.fireballs.stage4.ui.screen.stage4map.WorkManagerSyncWorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SyncSummary(
    val pendingDecisions: Int,
    val pendingPhotos: Int,
    val failedDecisions: List<LocalDecisionEntity>,
    val failedPhotos: List<PendingPhotoUploadEntity>,
)

sealed interface SyncUiState {
    data object Idle : SyncUiState

    data object Resuming : SyncUiState

    data class Pending(
        val summary: SyncSummary,
    ) : SyncUiState

    data class Running(
        val summary: SyncSummary,
        val done: Int,
        val total: Int,
        val phase: String?,
    ) : SyncUiState

    data class Failed(
        val summary: SyncSummary,
    ) : SyncUiState

    data object SessionExpired : SyncUiState

    data object Complete : SyncUiState
}

@HiltViewModel
class SyncViewModel
    @Inject
    constructor(
        private val localDecisionDao: LocalDecisionDao,
        private val pendingPhotoUploadDao: PendingPhotoUploadDao,
        private val selectedSurveyRepository: SelectedSurveyRepository,
        private val syncWorkManager: SyncWorkManager,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<SyncUiState>(SyncUiState.Idle)
        val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                @OptIn(ExperimentalCoroutinesApi::class)
                selectedSurveyRepository.selectedSurveyId
                    .flatMapLatest { surveyId ->
                        if (surveyId == null) {
                            flowOf(SyncUiState.Idle)
                        } else {
                            combine(
                                localDecisionDao.getUnsyncedCount(surveyId),
                                pendingPhotoUploadDao.getNotUploadedCount(surveyId),
                                localDecisionDao.getUnsyncedFailed(surveyId),
                                pendingPhotoUploadDao.getNotUploadedFailed(surveyId),
                                syncWorkManager.getWorkInfosForUniqueWorkFlow(
                                    WorkManagerSyncWorkManager.UNIQUE_WORK_NAME,
                                ),
                            ) { decisions, photos, failedDecisions, failedPhotos, workInfos ->
                                val summary =
                                    SyncSummary(
                                        pendingDecisions = decisions,
                                        pendingPhotos = photos,
                                        failedDecisions = failedDecisions,
                                        failedPhotos = failedPhotos,
                                    )
                                resolveState(summary, workInfos)
                            }
                        }
                    }.distinctUntilChanged()
                    .collect { state ->
                        _uiState.value = state
                    }
            }
        }

        private fun resolveState(
            summary: SyncSummary,
            workInfos: List<WorkInfo>,
        ): SyncUiState {
            val current = workInfos.lastOrNull()
            return when (current?.state) {
                WorkInfo.State.RUNNING -> {
                    val progress = current.progress
                    SyncUiState.Running(
                        summary = summary,
                        done = progress.getInt(SyncOrchestrator.KEY_DONE, 0),
                        total = progress.getInt(SyncOrchestrator.KEY_TOTAL, 0),
                        phase = progress.getString(SyncOrchestrator.KEY_PHASE),
                    )
                }

                WorkInfo.State.ENQUEUED -> SyncUiState.Resuming

                WorkInfo.State.SUCCEEDED -> {
                    if (current.outputData.getBoolean(SyncWorker.KEY_AUTH_EXPIRED, false)) {
                        SyncUiState.SessionExpired
                    } else if (hasPending(summary)) {
                        SyncUiState.Pending(summary)
                    } else {
                        SyncUiState.Complete
                    }
                }

                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> SyncUiState.Failed(summary)

                else -> if (hasPending(summary)) SyncUiState.Pending(summary) else SyncUiState.Idle
            }
        }

        private fun hasPending(summary: SyncSummary): Boolean =
            summary.pendingDecisions > 0 || summary.pendingPhotos > 0

        fun syncNow() {
            syncWorkManager.enqueueSync()
        }

        fun deleteDecision(inferenceResultId: Long) {
            viewModelScope.launch {
                localDecisionDao.deleteByInferenceResultId(inferenceResultId)
            }
        }

        fun deletePhoto(rowId: Long) {
            viewModelScope.launch {
                pendingPhotoUploadDao.deleteByRowId(rowId)
            }
        }
    }
