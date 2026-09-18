package au.edu.fireballs.stage4.ui

import android.app.Application
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import au.edu.fireballs.stage4.GlobalSyncState
import au.edu.fireballs.stage4.ui.theme.Stage4Theme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PendingSyncBadgeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun countHiddenWhenZero() {
        composeRule.setContent {
            Stage4Theme {
                PendingSyncBadge(pendingCount = 0)
            }
        }
        composeRule.onNodeWithText("pending").assertDoesNotExist()
    }

    @Test
    fun countShowsCountWhenPendingItemsExist() {
        composeRule.setContent {
            Stage4Theme {
                PendingSyncBadge(pendingCount = 3)
            }
        }
        composeRule.onNodeWithText("pending").assertIsDisplayed()
        composeRule.onNodeWithText("3").assertIsDisplayed()
    }

    @Test
    fun countShows99PlusForLargeCounts() {
        composeRule.setContent {
            Stage4Theme {
                PendingSyncBadge(pendingCount = 150)
            }
        }
        composeRule.onNodeWithText("pending").assertIsDisplayed()
        composeRule.onNodeWithText("99+").assertIsDisplayed()
        composeRule.onNodeWithText("150").assertDoesNotExist()
    }

    @Test
    fun countExposesMergedContentDescription() {
        composeRule.setContent {
            Stage4Theme {
                PendingSyncBadge(pendingCount = 3)
            }
        }
        composeRule.onNodeWithContentDescription("3 pending").assertExists()
    }

    private fun setState(
        state: GlobalSyncState,
        onAction: () -> Unit = {},
    ) {
        composeRule.setContent {
            Stage4Theme {
                PendingSyncBadge(state = state, onAction = onAction)
            }
        }
    }

    @Test
    fun stateHiddenWhenIdle() {
        setState(GlobalSyncState.Hidden)
        composeRule.onNodeWithText("pending").assertDoesNotExist()
        composeRule.onNodeWithText("waiting").assertDoesNotExist()
        composeRule.onNodeWithText("syncing").assertDoesNotExist()
    }

    @Test
    fun stateShowsPendingTotal() {
        setState(GlobalSyncState.Pending(decisions = 3, photos = 2))
        composeRule.onNodeWithText("pending").assertIsDisplayed()
        composeRule.onNodeWithText("5").assertIsDisplayed()
    }

    @Test
    fun stateWaitingIsNonActionable() {
        setState(GlobalSyncState.WaitingForNetwork)
        composeRule.onNodeWithText("waiting").assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription("Sync will start when connected")
            .assertHasNoClickAction()
    }

    @Test
    fun stateRunningIsNonActionable() {
        setState(GlobalSyncState.Running(progress = null))
        composeRule.onNodeWithText("syncing").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Syncing").assertHasNoClickAction()
    }

    @Test
    fun stateResumingIsNonActionable() {
        setState(GlobalSyncState.Resuming)
        composeRule.onNodeWithText("syncing").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Syncing").assertHasNoClickAction()
    }

    @Test
    fun stateFailedIsActionable() {
        var taps = 0
        setState(GlobalSyncState.Failed) { taps++ }
        composeRule.onNodeWithText("attention").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Sync needs attention").performClick()
        assertEquals(1, taps)
    }

    @Test
    fun stateCompleteIsHidden() {
        setState(GlobalSyncState.Complete)
        composeRule.onNodeWithText("pending").assertDoesNotExist()
        composeRule.onNodeWithText("attention").assertDoesNotExist()
    }

    @Test
    fun stateSessionExpiredIsHidden() {
        setState(GlobalSyncState.SessionExpired)
        composeRule.onNodeWithText("pending").assertDoesNotExist()
        composeRule.onNodeWithText("attention").assertDoesNotExist()
    }
}
