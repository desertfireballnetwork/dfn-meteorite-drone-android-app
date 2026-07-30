package au.edu.fireballs.stage4.ui.screen.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.edu.fireballs.stage4.data.remote.AuthRepository
import au.edu.fireballs.stage4.data.remote.AuthResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface LoginUiState {
    data object Idle : LoginUiState

    data object Loading : LoginUiState

    data class Error(
        val message: String,
    ) : LoginUiState
}

sealed interface LoginNavigationEvent {
    data object NavigateToSurveys : LoginNavigationEvent
}

@HiltViewModel
class LoginViewModel
    @Inject
    constructor(
        private val authRepository: AuthRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
        val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

        private val _navigationEvent = MutableSharedFlow<LoginNavigationEvent>()
        val navigationEvent: SharedFlow<LoginNavigationEvent> = _navigationEvent.asSharedFlow()

        fun signIn(
            username: String,
            password: String,
        ) {
            if (username.isBlank() || password.isBlank()) {
                _uiState.update { LoginUiState.Error("Username and password required") }
                return
            }

            viewModelScope.launch {
                _uiState.update { LoginUiState.Loading }
                when (val result = authRepository.login(username, password)) {
                    is AuthResult.Success -> {
                        _uiState.update { LoginUiState.Idle }
                        _navigationEvent.emit(LoginNavigationEvent.NavigateToSurveys)
                    }
                    is AuthResult.Failure -> {
                        _uiState.update { LoginUiState.Error(result.message) }
                    }
                    is AuthResult.NetworkError -> {
                        _uiState.update { LoginUiState.Error("Network error, retry") }
                    }
                }
            }
        }
    }
