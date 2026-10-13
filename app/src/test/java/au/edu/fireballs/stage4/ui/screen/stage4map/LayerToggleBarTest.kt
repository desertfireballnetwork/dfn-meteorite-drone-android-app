package au.edu.fireballs.stage4.ui.screen.stage4map

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
class LayerToggleBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun collapsedByDefaultShowsOnlyLayersButton() {
        setContent()

        composeRule.onNodeWithTag(LAYER_MENU_BUTTON_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(LAYER_MENU_PANEL_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("Map Layers").assertDoesNotExist()
    }

    @Test
    fun tapLayersButtonExpandsPanelAndCloseCollapsesIt() {
        setContent()

        composeRule.onNodeWithTag(LAYER_MENU_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(LAYER_MENU_PANEL_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Unprocessed").assertIsDisplayed()
        composeRule.onNodeWithTag(LAYER_MENU_BUTTON_TAG).assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Close map layers").performClick()
        composeRule.onNodeWithTag(LAYER_MENU_PANEL_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(LAYER_MENU_BUTTON_TAG).assertIsDisplayed()
    }

    @Test
    fun toggleRowInExpandedPanelInvokesCallback() {
        val toggles = mutableListOf<Pair<LayerType, Boolean>>()
        setContent(onToggle = { type, checked -> toggles += type to checked })

        composeRule.onNodeWithTag(LAYER_MENU_BUTTON_TAG).performClick()
        // Toggle order matches the panel rows: Yes, No, Unprocessed, Surveyed Areas.
        composeRule.onAllNodes(isToggleable())[2].performClick()

        assertEquals(listOf(LayerType.UNPROCESSED to false), toggles)
    }

    private fun setContent(onToggle: (LayerType, Boolean) -> Unit = { _, _ -> }) {
        composeRule.setContent {
            Stage4Theme {
                LayerToggleBar(state = LayerToggleState(), onToggle = onToggle)
            }
        }
    }
}
