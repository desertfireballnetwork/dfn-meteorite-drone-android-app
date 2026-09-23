package au.edu.fireballs.stage4

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.edu.fireballs.stage4.data.local.dao.LocalDecisionDao
import au.edu.fireballs.stage4.data.local.dao.PendingPhotoUploadDao
import au.edu.fireballs.stage4.data.remote.AccountManager
import au.edu.fireballs.stage4.data.repository.DurableSyncStatus
import au.edu.fireballs.stage4.data.repository.SelectedSurveyRepository
import au.edu.fireballs.stage4.data.repository.SyncProgress
import au.edu.fireballs.stage4.data.repository.SyncStatusSource
import au.edu.fireballs.stage4.di.IoDispatcher
import au.edu.fireballs.stage4.ui.screen.stage4map.SyncWorkManager
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface AuthState {
    data object Loading : AuthState

    data class Resolved(
        val isSignedIn: Boolean,
    ) : AuthState
}

sealed interface GlobalSyncState {
    data object Hidden : GlobalSyncState

    data class Pending(
        val decisions: Int,
        val photos: Int,
    ) : GlobalSyncState

    data object WaitingForNetwork : GlobalSyncState

    data class Running(
        val progress: SyncProgress?,
    ) : GlobalSyncState

    data object Resuming : GlobalSyncState

    data object Complete : GlobalSyncState

    data object Failed : GlobalSyncState

    data object SessionExpired : GlobalSyncState
}

@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        private val accountManager: Lazy<AccountManager>,
        private val selectedSurveyRepository: SelectedSurveyRepository,
        private val localDecisionDao: LocalDecisionDao,
        private val pendingPhotoUploadDao: PendingPhotoUploadDao,
        private val syncWorkManager: SyncWorkManager,
        private val syncStatusSource: SyncStatusSource,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
        val authState: StateFlow<AuthState> = _authState.asStateFlow()

        val selectedSurveyId: StateFlow<Long?> =
            selectedSurveyRepository.selectedSurveyId
                .stateIn(
                    viewModelScope,
                    SharingStarted.Eagerly,
                    null,
                )

        val globalSyncState: StateFlow<GlobalSyncState> =
            syncStatusSource.status
                .map(DurableSyncStatus::toGlobalSyncState)
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5_000),
                    GlobalSyncState.Hidden,
                )

        val syncCompletionEvents: Flow<Unit> =
            syncStatusSource.completions.map { Unit }

        fun setSelectedSurvey(surveyId: Long) {
            viewModelScope.launch {
                selectedSurveyRepository.set(surveyId)
            }
        }

        fun clearSelectedSurvey() {
            viewModelScope.launch {
                selectedSurveyRepository.clear()
            }
        }

        fun resumeSyncIfNeeded() {
            viewModelScope.launch {
                val surveyId = selectedSurveyRepository.selectedSurveyId.first()
                if (surveyId != null) {
                    val pending =
                        localDecisionDao.getUnsyncedCount(surveyId).first() +
                            pendingPhotoUploadDao.getNotUploadedCount(surveyId).first()
                    if (pending > 0) {
                        syncWorkManager.enqueueSync()
                    }
                }
            }
        }

        init {
            checkAuthStatus()
        }

        private fun checkAuthStatus() {
            viewModelScope.launch {
                val isSignedIn =
                    withContext(ioDispatcher) {
                        accountManager.get().isSignedIn()
                    }
                _authState.value = AuthState.Resolved(isSignedIn)
            }
        }
    }

private fun DurableSyncStatus.toGlobalSyncState(): GlobalSyncState =
    when (this) {
        DurableSyncStatus.Idle -> GlobalSyncState.Hidden
        is DurableSyncStatus.Pending -> GlobalSyncState.Pending(decisions, photos)
        is DurableSyncStatus.WaitingForNetwork -> GlobalSyncState.WaitingForNetwork
        is DurableSyncStatus.Running -> GlobalSyncState.Running(progress)
        is DurableSyncStatus.Resuming -> GlobalSyncState.Resuming
        is DurableSyncStatus.Complete -> GlobalSyncState.Hidden
        is DurableSyncStatus.Failed -> GlobalSyncState.Failed
        is DurableSyncStatus.SessionExpired -> GlobalSyncState.SessionExpired
    }
