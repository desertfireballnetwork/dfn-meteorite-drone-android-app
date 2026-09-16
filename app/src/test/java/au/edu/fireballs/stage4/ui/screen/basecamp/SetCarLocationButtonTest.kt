package au.edu.fireballs.stage4.ui.screen.basecamp

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    private fun setContent(
        showSuccess: Boolean = false,
        submitting: Boolean = false,
    ) {
        composeRule.setContent {
            SetCarLocationButton(
                showSuccess = showSuccess,
                submitting = submitting,
                onSetLocation = { _, _ -> },
                onMessage = {},
            )
        }
    }

    @Test
    fun idleShowsSetCarLocationIcon() {
        setContent()

        composeRule.onNodeWithContentDescription("Set car location").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Car location set").assertDoesNotExist()
    }

    @Test
    fun clickingIdleIconShowsConfirmationDialog() {
        setContent()

        composeRule.onNodeWithContentDescription("Set car location").performClick()

        composeRule.onNodeWithText("Set car location?").assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "Use this device's current location as the car location for this survey?",
            ).assertIsDisplayed()
        composeRule.onNodeWithText("Set location").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun cancelDismissesConfirmationDialog() {
        setContent()
        composeRule.onNodeWithContentDescription("Set car location").performClick()

        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.onNodeWithText("Set car location?").assertDoesNotExist()
    }

    @Test
    fun setLocationDismissesConfirmationDialog() {
        setContent()
        composeRule.onNodeWithContentDescription("Set car location").performClick()

        composeRule.onNodeWithText("Set location").performClick()

        composeRule.onNodeWithText("Set car location?").assertDoesNotExist()
    }

    @Test
    fun successShowsCarLocationSetIcon() {
        setContent(showSuccess = true)

        composeRule.onNodeWithContentDescription("Car location set").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Set car location").assertDoesNotExist()
    }

    @Test
    fun busyShowsNeitherIdleNorSuccessIcon() {
        setContent(submitting = true)

        composeRule
            .onNodeWithContentDescription("Setting car location")
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Set car location").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Car location set").assertDoesNotExist()
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
