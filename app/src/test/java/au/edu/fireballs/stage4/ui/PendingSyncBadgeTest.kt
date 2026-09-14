package au.edu.fireballs.stage4.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import au.edu.fireballs.stage4.ui.theme.Stage4Theme
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
    fun hiddenWhenCountIsZero() {
        composeRule.setContent {
            Stage4Theme {
                PendingSyncBadge(pendingCount = 0)
            }
        }
        composeRule.onNodeWithText("pending").assertDoesNotExist()
    }

    @Test
    fun showsCountWhenPendingItemsExist() {
        composeRule.setContent {
            Stage4Theme {
                PendingSyncBadge(pendingCount = 3)
            }
        }
        composeRule.onNodeWithText("pending").assertIsDisplayed()
        composeRule.onNodeWithText("3").assertIsDisplayed()
    }

    @Test
    fun shows99PlusForLargeCounts() {
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
    fun exposesMergedContentDescription() {
        composeRule.setContent {
            Stage4Theme {
                PendingSyncBadge(pendingCount = 3)
            }
        }
        composeRule.onNodeWithContentDescription("3 pending").assertExists()
    }
}
