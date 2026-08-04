package au.edu.fireballs.stage4

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.edu.fireballs.stage4.data.remote.AccountManager
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        // Injecting of Lazy<AccountManager> to defer instantiation
        private val accountManager: Lazy<AccountManager>,
    ) : ViewModel() {
        private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
        val authState: StateFlow<AuthState> = _authState.asStateFlow()

        init {
            checkAuthStatus()
        }

        private fun checkAuthStatus() {
            viewModelScope.launch {
                // Perform AccountManager init and disk I/O / decryption on Dispatchers.IO
                val isSignedIn =
                    withContext(Dispatchers.IO) {
                        accountManager.get().isSignedIn()
                    }
                _authState.value = AuthState.Resolved(isSignedIn)
            }
        }
    }
