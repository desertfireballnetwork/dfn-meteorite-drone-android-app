package au.edu.fireballs.stage4.ui.session

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SessionExpiredHandlerTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun modalAppearsOnEvent() =
        runTest {
            val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            var signedIn = false

            composeRule.setContent {
                SessionExpiredHandler(
                    events = events,
                    onSignIn = { signedIn = true },
                )
            }

            composeRule
                .onNodeWithText("Your session has expired. Please sign in again.")
                .assertDoesNotExist()

            events.tryEmit(Unit)
            composeRule.waitForIdle()

            composeRule
                .onNodeWithText("Your session has expired. Please sign in again.")
                .assertExists()
            composeRule.onNodeWithTag("session-expired-sign-in").assertExists()
        }

    @Test
    fun signInButtonInvokesCallback() =
        runTest {
            val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            var signedIn = false

            composeRule.setContent {
                SessionExpiredHandler(
                    events = events,
                    onSignIn = { signedIn = true },
                )
            }

            events.tryEmit(Unit)
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("session-expired-sign-in").performClick()
            composeRule.waitForIdle()

            assertTrue(signedIn)
            composeRule
                .onNodeWithText("Your session has expired. Please sign in again.")
                .assertDoesNotExist()
        }

    @Test
    fun noRepeatedModalWhileVisible() =
        runTest {
            val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

            composeRule.setContent {
                SessionExpiredHandler(
                    events = events,
                    onSignIn = {},
                )
            }

            events.tryEmit(Unit)
            events.tryEmit(Unit)
            events.tryEmit(Unit)
            composeRule.waitForIdle()

            composeRule
                .onAllNodesWithText("Your session has expired. Please sign in again.")
                .assertCountEquals(1)
            composeRule.onNodeWithTag("session-expired-sign-in").assertExists()
        }
}
