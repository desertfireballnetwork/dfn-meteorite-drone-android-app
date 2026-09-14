package au.edu.fireballs.stage4.ui.screen.stage4map

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
class OfflineBundleGuardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun warningVisibleWhenNoBundleExists() {
        composeRule.setContent {
            Stage4Theme {
                OfflineBundleGuard(hasBundle = false, onOpenDownloads = {})
            }
        }
        composeRule
            .onNodeWithText("No offline data downloaded — connect to basecamp WiFi to download")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Open Downloads").assertIsDisplayed()
    }

    @Test
    fun warningHiddenWhenBundleExists() {
        composeRule.setContent {
            Stage4Theme {
                OfflineBundleGuard(hasBundle = true, onOpenDownloads = {})
            }
        }
        composeRule
            .onNodeWithText("No offline data downloaded — connect to basecamp WiFi to download")
            .assertDoesNotExist()
        composeRule.onNodeWithText("Open Downloads").assertDoesNotExist()
    }

    @Test
    fun tapOpenDownloadsInvokesCallback() {
        var opened = 0
        composeRule.setContent {
            Stage4Theme {
                OfflineBundleGuard(hasBundle = false, onOpenDownloads = { opened++ })
            }
        }
        composeRule.onNodeWithText("Open Downloads").performClick()
        assertEquals(1, opened)
    }
}
