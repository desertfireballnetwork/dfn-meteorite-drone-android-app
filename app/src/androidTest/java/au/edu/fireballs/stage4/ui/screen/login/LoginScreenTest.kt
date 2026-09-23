package au.edu.fireballs.stage4.ui.screen.login

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import au.edu.fireballs.stage4.ui.theme.Stage4Theme
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class LoginScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun passwordField_toggleVisibility_updatesVisualsAndAccessibility() {
        val viewModel = mockk<LoginViewModel>(relaxed = true)
        val uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
        val navigationEvent = MutableSharedFlow<LoginNavigationEvent>()

        every { viewModel.uiState } returns uiState
        every { viewModel.navigationEvent } returns navigationEvent

        composeTestRule.setContent {
            Stage4Theme {
                LoginScreen(
                    viewModel = viewModel,
                    onLoginSuccess = {},
                )
            }
        }

        val testPassword = "testPassword123"

        composeTestRule
            .onNodeWithTag("password_field")
            .performTextInput(testPassword)

        composeTestRule
            .onNodeWithTag("password_field")
            .assertTextContains("•••••••••••••••")

        composeTestRule
            .onNodeWithContentDescription("Show password")
            .assertIsDisplayed()
            .performClick()

        composeTestRule
            .onNodeWithTag("password_field")
            .assertTextContains(testPassword)

        composeTestRule
            .onNodeWithContentDescription("Hide password")
            .assertIsDisplayed()
            .performClick()

        composeTestRule
            .onNodeWithTag("password_field")
            .assertTextContains("•••••••••••••••")

        composeTestRule
            .onNodeWithContentDescription("Show password")
            .assertIsDisplayed()
    }
}
