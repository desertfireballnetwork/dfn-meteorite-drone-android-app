package au.edu.fireballs.stage4

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.edu.fireballs.stage4.data.local.dao.LocalDecisionDao
import au.edu.fireballs.stage4.data.local.dao.PendingPhotoUploadDao
import au.edu.fireballs.stage4.data.remote.AccountManager
import au.edu.fireballs.stage4.data.repository.SelectedSurveyRepository
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    ) : ViewModel() {
        private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
        val authState: StateFlow<AuthState> = _authState.asStateFlow()

        val selectedSurveyId: Flow<Long?> = selectedSurveyRepository.selectedSurveyId

        val pendingDecisions: StateFlow<Int> =
            localDecisionDao.getUnsyncedCount().stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                0,
            )

        val pendingPhotos: StateFlow<Int> =
            pendingPhotoUploadDao.getNotUploadedCount().stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                0,
            )

        fun setSelectedSurvey(surveyId: Long) {
            viewModelScope.launch {
                selectedSurveyRepository.set(surveyId)
            }
        }

        init {
            checkAuthStatus()
        }

        private fun checkAuthStatus() {
            viewModelScope.launch {
                val isSignedIn =
                    withContext(Dispatchers.IO) {
                        accountManager.get().isSignedIn()
                    }
                _authState.value = AuthState.Resolved(isSignedIn)
            }
        }
    }
