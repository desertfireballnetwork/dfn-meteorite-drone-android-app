package au.edu.fireballs.stage4.ui.screen.basecamp

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SetCarLocationButtonTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsVisibleLabelWhenIdle() {
        composeRule.setContent {
            SetCarLocationButton(
                showSuccess = false,
                submitting = false,
                onSetLocation = { _, _ -> },
                onMessage = {},
            )
        }
        composeRule.onNodeWithText("Set my location as car location").assertIsDisplayed()
        composeRule.onNodeWithText("Car location set").assertDoesNotExist()
    }

    @Test
    fun showsCheckmarkOnlyAfterServerSuccess() {
        composeRule.setContent {
            SetCarLocationButton(
                showSuccess = true,
                submitting = false,
                onSetLocation = { _, _ -> },
                onMessage = {},
            )
        }
        composeRule.onNodeWithText("Car location set").assertIsDisplayed()
        composeRule.onNodeWithText("Set my location as car location").assertDoesNotExist()
    }

    @Test
    fun showsBusyStateWhileSubmitting() {
        composeRule.setContent {
            SetCarLocationButton(
                showSuccess = false,
                submitting = true,
                onSetLocation = { _, _ -> },
                onMessage = {},
            )
        }
        composeRule.onNodeWithText("Set my location as car location").assertDoesNotExist()
        composeRule.onNodeWithText("Car location set").assertDoesNotExist()
    }

    @Test
    fun fineLocationGrantedRequiresFinePermission() {
        assertFalse(
            fineLocationGranted(
                mapOf(
                    Manifest.permission.ACCESS_FINE_LOCATION to false,
                    Manifest.permission.ACCESS_COARSE_LOCATION to true,
                ),
            ),
        )
        assertTrue(
            fineLocationGranted(
                mapOf(Manifest.permission.ACCESS_FINE_LOCATION to true),
            ),
        )
        assertFalse(fineLocationGranted(emptyMap()))
    }
}
