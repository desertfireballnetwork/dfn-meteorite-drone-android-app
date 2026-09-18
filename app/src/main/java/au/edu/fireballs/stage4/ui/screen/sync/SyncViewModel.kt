package au.edu.fireballs.stage4.ui.screen.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.edu.fireballs.stage4.data.local.LocalDecisionEntity
import au.edu.fireballs.stage4.data.local.PendingPhotoUploadEntity
import au.edu.fireballs.stage4.data.local.dao.LocalDecisionDao
import au.edu.fireballs.stage4.data.local.dao.PendingPhotoUploadDao
import au.edu.fireballs.stage4.data.repository.DurableSyncStatus
import au.edu.fireballs.stage4.data.repository.SelectedSurveyRepository
import au.edu.fireballs.stage4.data.repository.SyncProgress
import au.edu.fireballs.stage4.data.repository.SyncStatusSource
import au.edu.fireballs.stage4.data.repository.isSyncGated
import au.edu.fireballs.stage4.ui.screen.stage4map.SyncWorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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

    data class Pending(
        val summary: SyncSummary,
    ) : SyncUiState

    data class WaitingForNetwork(
        val summary: SyncSummary,
    ) : SyncUiState

    data class Running(
        val summary: SyncSummary,
        val progress: SyncProgress?,
    ) : SyncUiState

    data class Resuming(
        val summary: SyncSummary,
    ) : SyncUiState

    data object Complete : SyncUiState

    data class Failed(
        val summary: SyncSummary,
    ) : SyncUiState

    data object SessionExpired : SyncUiState
}

@HiltViewModel
class SyncViewModel
    @Inject
    constructor(
        private val localDecisionDao: LocalDecisionDao,
        private val pendingPhotoUploadDao: PendingPhotoUploadDao,
        private val selectedSurveyRepository: SelectedSurveyRepository,
        private val syncWorkManager: SyncWorkManager,
        private val syncStatusSource: SyncStatusSource,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<SyncUiState>(SyncUiState.Idle)
        val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                combine(
                    summaryFlow(),
                    syncStatusSource.status,
                ) { summary, status ->
                    mapState(summary, status)
                }.distinctUntilChanged()
                    .collect { state ->
                        _uiState.value = state
                    }
            }
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        private fun summaryFlow(): Flow<SyncSummary?> =
            selectedSurveyRepository.selectedSurveyId
                .flatMapLatest { surveyId ->
                    if (surveyId == null) {
                        flowOf(null)
                    } else {
                        combine(
                            localDecisionDao.getUnsyncedCount(surveyId),
                            pendingPhotoUploadDao.getNotUploadedCount(surveyId),
                            localDecisionDao.getUnsyncedFailed(surveyId),
                            pendingPhotoUploadDao.getNotUploadedFailed(surveyId),
                        ) { decisions, photos, failedDecisions, failedPhotos ->
                            SyncSummary(
                                pendingDecisions = decisions,
                                pendingPhotos = photos,
                                failedDecisions = failedDecisions,
                                failedPhotos = failedPhotos,
                            )
                        }
                    }
                }

        private fun mapState(
            summary: SyncSummary?,
            status: DurableSyncStatus,
        ): SyncUiState {
            if (summary == null) {
                return SyncUiState.Idle
            }
            return when (status) {
                DurableSyncStatus.Idle -> SyncUiState.Idle
                is DurableSyncStatus.Pending -> SyncUiState.Pending(summary)
                is DurableSyncStatus.WaitingForNetwork -> SyncUiState.WaitingForNetwork(summary)
                is DurableSyncStatus.Running -> SyncUiState.Running(summary, status.progress)
                is DurableSyncStatus.Resuming -> SyncUiState.Resuming(summary)
                is DurableSyncStatus.Complete -> SyncUiState.Complete
                is DurableSyncStatus.Failed -> SyncUiState.Failed(summary)
                is DurableSyncStatus.SessionExpired -> SyncUiState.SessionExpired
            }
        }

        fun syncNow() {
            if (syncStatusSource.status.value.isSyncGated) {
                return
            }
            syncWorkManager.enqueueSync()
        }

        fun deleteDecision(inferenceResultId: Long) {
            if (syncStatusSource.status.value.isSyncGated) {
                return
            }
            viewModelScope.launch {
                localDecisionDao.deleteByInferenceResultId(inferenceResultId)
            }
        }

        fun deletePhoto(rowId: Long) {
            if (syncStatusSource.status.value.isSyncGated) {
                return
            }
            viewModelScope.launch {
                pendingPhotoUploadDao.deleteByRowId(rowId)
            }
        }
    }
