package au.edu.fireballs.stage4.ui.screen.login

import app.cash.turbine.test
import au.edu.fireballs.stage4.data.remote.AuthRepository
import au.edu.fireballs.stage4.data.remote.AuthResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val authRepository: AuthRepository = mock()
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: LoginViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = LoginViewModel(authRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `blank credentials updates UI state to error immediately`() =
        runTest {
            viewModel.signIn("", "")

            assertEquals(
                LoginUiState.Error("Username and password required"),
                viewModel.uiState.value,
            )
        }

    @Test
    fun `successful sign in updates state to loading then emits navigation event`() =
        runTest {
            whenever(authRepository.login("user", "pass")).thenReturn(AuthResult.Success)

            viewModel.navigationEvent.test {
                viewModel.uiState.test {
                    assertEquals(LoginUiState.Idle, awaitItem())

                    viewModel.signIn("user", "pass")
                    testDispatcher.scheduler.advanceUntilIdle()

                    assertEquals(LoginUiState.Loading, awaitItem())
                    assertEquals(LoginUiState.Idle, awaitItem())
                }

                assertEquals(LoginNavigationEvent.NavigateToSurveys, awaitItem())
            }
        }

    @Test
    fun `failed sign in updates state to Error`() =
        runTest {
            val errorMessage = "Invalid username or password"
            whenever(
                authRepository.login("user", "pass"),
            ).thenReturn(AuthResult.Failure(errorMessage))

            viewModel.uiState.test {
                assertEquals(LoginUiState.Idle, awaitItem())

                viewModel.signIn("user", "pass")
                testDispatcher.scheduler.advanceUntilIdle()

                assertEquals(LoginUiState.Loading, awaitItem())
                assertEquals(LoginUiState.Error(errorMessage), awaitItem())
            }
        }

    @Test
    fun `network error updates state to retry message`() =
        runTest {
            whenever(authRepository.login("user", "pass")).thenReturn(AuthResult.NetworkError)

            viewModel.uiState.test {
                assertEquals(LoginUiState.Idle, awaitItem())

                viewModel.signIn("user", "pass")
                testDispatcher.scheduler.advanceUntilIdle()

                assertEquals(LoginUiState.Loading, awaitItem())
                assertEquals(LoginUiState.Error("Network error, retry"), awaitItem())
            }
        }
}
