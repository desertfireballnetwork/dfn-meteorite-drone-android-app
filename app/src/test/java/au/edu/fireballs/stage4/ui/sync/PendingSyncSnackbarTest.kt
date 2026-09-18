package au.edu.fireballs.stage4.ui.sync

import android.app.Application
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import au.edu.fireballs.stage4.GlobalSyncState
import au.edu.fireballs.stage4.data.repository.SyncPhase
import au.edu.fireballs.stage4.data.repository.SyncProgress
import au.edu.fireballs.stage4.ui.theme.Stage4Theme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PendingSyncSnackbarTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setState(
        state: GlobalSyncState,
        onAction: () -> Unit = {},
    ) {
        composeRule.setContent {
            Stage4Theme {
                PendingSyncSnackbar(state = state, onAction = onAction)
            }
        }
    }

    @Test
    fun hiddenWhenIdle() {
        setState(GlobalSyncState.Hidden)
        composeRule.onNodeWithText("pending sync", substring = true).assertDoesNotExist()
    }

    @Test
    fun showsPendingCountsWhenPending() {
        setState(GlobalSyncState.Pending(decisions = 3, photos = 2))
        composeRule.onNodeWithText("3 decisions / 2 photos pending sync").assertIsDisplayed()
        composeRule.onNodeWithText("Tap to view").assertIsDisplayed()
    }

    @Test
    fun pendingIsActionable() {
        var taps = 0
        setState(GlobalSyncState.Pending(decisions = 1, photos = 0)) { taps++ }
        composeRule.onNodeWithText("1 decisions / 0 photos pending sync").performClick()
        assertEquals(1, taps)
    }

    @Test
    fun showsWaitingCopyAndIsNonActionable() {
        setState(GlobalSyncState.WaitingForNetwork)
        composeRule.onNodeWithText("Sync will start when connected").assertIsDisplayed()
        composeRule.onNodeWithText("Sync will start when connected").assertHasNoClickAction()
        composeRule.onNodeWithText("Tap to view").assertDoesNotExist()
    }

    @Test
    fun showsPhotoProgressCopy() {
        val progress = SyncProgress(SyncPhase.Photo, done = 3, total = 10)
        setState(GlobalSyncState.Running(progress))
        composeRule.onNodeWithText("Syncing: Uploading photo 3 of 10").assertIsDisplayed()
    }

    @Test
    fun showsDecisionProgressCopy() {
        val progress = SyncProgress(SyncPhase.Decision, done = 4, total = 9)
        setState(GlobalSyncState.Running(progress))
        composeRule.onNodeWithText("Syncing: Synchronising decision 4 of 9").assertIsDisplayed()
    }

    @Test
    fun unknownPhaseRunningShowsSyncing() {
        val progress = SyncProgress(SyncPhase.Unknown, done = 1, total = 5)
        setState(GlobalSyncState.Running(progress))
        composeRule.onNodeWithText("Syncing").assertIsDisplayed()
    }

    @Test
    fun photoProgressWithoutTotalOmitsNumbers() {
        val progress = SyncProgress(SyncPhase.Photo, done = 1, total = null)
        setState(GlobalSyncState.Running(progress))
        composeRule.onNodeWithText("Syncing: Uploading photo").assertIsDisplayed()
    }

    @Test
    fun nullProgressRunningShowsSyncing() {
        setState(GlobalSyncState.Running(progress = null))
        composeRule.onNodeWithText("Syncing").assertIsDisplayed()
    }

    @Test
    fun resumingShowsSyncingAndIsNonActionable() {
        setState(GlobalSyncState.Resuming)
        composeRule.onNodeWithText("Syncing").assertIsDisplayed()
        composeRule.onNodeWithText("Syncing").assertHasNoClickAction()
    }

    @Test
    fun failedShowsAttentionCopyAndIsActionable() {
        var taps = 0
        setState(GlobalSyncState.Failed) { taps++ }
        composeRule.onNodeWithText("Sync needs attention").assertIsDisplayed()
        composeRule.onNodeWithText("Sync needs attention").performClick()
        assertEquals(1, taps)
    }

    @Test
    fun completeShowsSuccessCopy() {
        setState(GlobalSyncState.Complete)
        composeRule.onNodeWithText("Sync complete").assertIsDisplayed()
    }

    @Test
    fun sessionExpiredRendersNoGlobalSurface() {
        setState(GlobalSyncState.SessionExpired)
        composeRule.onNodeWithText("Sync complete").assertDoesNotExist()
        composeRule.onNodeWithText("Sync needs attention").assertDoesNotExist()
    }
}
