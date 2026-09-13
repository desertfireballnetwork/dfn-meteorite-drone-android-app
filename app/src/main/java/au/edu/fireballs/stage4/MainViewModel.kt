package au.edu.fireballs.stage4

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.edu.fireballs.stage4.data.local.dao.LocalDecisionDao
import au.edu.fireballs.stage4.data.local.dao.PendingPhotoUploadDao
import au.edu.fireballs.stage4.data.remote.AccountManager
import au.edu.fireballs.stage4.data.repository.SelectedSurveyRepository
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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

@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        private val accountManager: Lazy<AccountManager>,
        private val selectedSurveyRepository: SelectedSurveyRepository,
        private val localDecisionDao: LocalDecisionDao,
        private val pendingPhotoUploadDao: PendingPhotoUploadDao,
        private val syncWorkManager: SyncWorkManager,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
        val authState: StateFlow<AuthState> = _authState.asStateFlow()

        val selectedSurveyId: Flow<Long?> = selectedSurveyRepository.selectedSurveyId

        val pendingDecisions: StateFlow<Int> =
            selectedSurveyRepository.selectedSurveyId
                .flatMapLatest { surveyId ->
                    if (surveyId == null) {
                        flowOf(0)
                    } else {
                        localDecisionDao.getUnsyncedCount(surveyId)
                    }
                }.stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    0,
                )

        val pendingPhotos: StateFlow<Int> =
            selectedSurveyRepository.selectedSurveyId
                .flatMapLatest { surveyId ->
                    if (surveyId == null) {
                        flowOf(0)
                    } else {
                        pendingPhotoUploadDao.getNotUploadedCount(surveyId)
                    }
                }.stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    0,
                )

        fun setSelectedSurvey(surveyId: Long) {
            viewModelScope.launch {
                selectedSurveyRepository.set(surveyId)
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
