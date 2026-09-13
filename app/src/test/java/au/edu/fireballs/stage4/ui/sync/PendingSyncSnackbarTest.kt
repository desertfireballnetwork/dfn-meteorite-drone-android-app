package au.edu.fireballs.stage4.ui.sync

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
class PendingSyncSnackbarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun hiddenWhenNoPendingItems() {
        composeRule.setContent {
            Stage4Theme {
                PendingSyncSnackbar(
                    pendingDecisions = 0,
                    pendingPhotos = 0,
                    onTap = {},
                )
            }
        }
        composeRule.onNodeWithText("0 decisions / 0 photos pending sync").assertDoesNotExist()
    }

    @Test
    fun showsCountsWhenPendingItemsExist() {
        composeRule.setContent {
            Stage4Theme {
                PendingSyncSnackbar(
                    pendingDecisions = 3,
                    pendingPhotos = 2,
                    onTap = {},
                )
            }
        }
        composeRule.onNodeWithText("3 decisions / 2 photos pending sync").assertIsDisplayed()
        composeRule.onNodeWithText("Tap to view").assertIsDisplayed()
    }

    @Test
    fun tapTriggersCallback() {
        var tapped = 0
        composeRule.setContent {
            Stage4Theme {
                PendingSyncSnackbar(
                    pendingDecisions = 1,
                    pendingPhotos = 0,
                    onTap = { tapped++ },
                )
            }
        }
        composeRule.onNodeWithText("1 decisions / 0 photos pending sync").performClick()
        assertEquals(1, tapped)
    }
}
