package au.edu.fireballs.stage4.ui.screen.stage4map

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import au.edu.fireballs.stage4.ui.theme.Stage4Theme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MapOverlaySyncTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(
        status: SyncStatus,
        onSync: () -> Unit = {},
    ) {
        composeRule.setContent {
            Stage4Theme {
                SyncButton(status = status, onSync = onSync)
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun runningState_showsCompactSpinnerDisabled() {
        setContent(SyncStatus.Running)
        composeRule.onNodeWithTag(MAP_SYNC_PROGRESS_TAG).assertExists()
        composeRule.onNodeWithTag(MAP_SYNC_BUTTON_TAG).assertIsNotEnabled()
    }

    @Test
    fun resumingState_showsCompactSpinnerDisabled() {
        setContent(SyncStatus.Resuming)
        composeRule.onNodeWithTag(MAP_SYNC_PROGRESS_TAG).assertExists()
        composeRule.onNodeWithTag(MAP_SYNC_BUTTON_TAG).assertIsNotEnabled()
    }

    @Test
    fun waitingForNetworkState_showsConnectivityNotSpinner() {
        setContent(SyncStatus.WaitingForNetwork)
        composeRule.onNodeWithTag(MAP_SYNC_PROGRESS_TAG).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Waiting for network").assertExists()
        composeRule.onNodeWithTag(MAP_SYNC_BUTTON_TAG).assertIsNotEnabled()
    }

    @Test
    fun idleState_showsActionableSync() {
        var synced = 0
        setContent(SyncStatus.Idle, onSync = { synced++ })
        composeRule.onNodeWithTag(MAP_SYNC_BUTTON_TAG).assertIsEnabled().performClick()
        assertEquals(1, synced)
    }

    @Test
    fun pendingState_showsActionableSync() {
        var synced = 0
        setContent(SyncStatus.Pending, onSync = { synced++ })
        composeRule.onNodeWithTag(MAP_SYNC_BUTTON_TAG).assertIsEnabled().performClick()
        assertEquals(1, synced)
    }

    @Test
    fun failedState_showsFailureFeedback() {
        setContent(SyncStatus.Failed)
        composeRule.onNodeWithContentDescription("Sync failed").assertExists()
    }

    @Test
    fun sessionExpiredState_showsSessionExpiredFeedback() {
        setContent(SyncStatus.AuthExpired)
        composeRule.onNodeWithContentDescription("Session expired").assertExists()
    }

    @Test
    fun completeState_showsCompleteFeedback() {
        setContent(SyncStatus.Complete)
        composeRule.onNodeWithContentDescription("Sync complete").assertExists()
    }
}
