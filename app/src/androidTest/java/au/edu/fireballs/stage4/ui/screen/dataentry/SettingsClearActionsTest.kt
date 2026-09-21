package au.edu.fireballs.stage4.ui.screen.dataentry

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import au.edu.fireballs.stage4.data.repository.StorageClearCategory
import au.edu.fireballs.stage4.ui.theme.Stage4Theme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsClearActionsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun geotiffDialog_hasExactCopyAndCancelFocus() {
        assertDialog(
            category = StorageClearCategory.GeotiffTiles,
            title = "Clear geotiff tiles?",
            body =
                "This removes downloaded candidate map tiles. They can be downloaded " +
                    "again when online. Evidence photos, pending uploads, and decisions " +
                    "will not be removed.",
            confirmLabel = "Clear tiles",
            confirmTag = "clear-confirm-geotiff-tiles",
        )
    }

    @Test
    fun cropDialog_hasExactCopyAndCancelFocus() {
        assertDialog(
            category = StorageClearCategory.CandidateCrops,
            title = "Clear candidate crops?",
            body =
                "This removes downloaded candidate preview images. They can be " +
                    "downloaded again when online. Evidence photos, pending uploads, " +
                    "and decisions will not be removed.",
            confirmLabel = "Clear crops",
            confirmTag = "clear-confirm-candidate-crops",
        )
    }

    @Test
    fun satelliteDialog_hasExactCopyAndCancelFocus() {
        assertDialog(
            category = StorageClearCategory.SatelliteMaps,
            title = "Clear satellite maps?",
            body =
                "This removes downloaded satellite map regions. They can be downloaded " +
                    "again when online. Evidence photos, pending uploads, and decisions " +
                    "will not be removed.",
            confirmLabel = "Clear maps",
            confirmTag = "clear-confirm-satellite-maps",
        )
    }

    @Test
    fun allDownloadsDialog_hasExactCopyAndCancelFocus() {
        assertDialog(
            category = StorageClearCategory.AllCachedDownloads,
            title = "Clear all cached downloads?",
            body =
                "This removes all downloaded geotiff tiles, candidate crops, and " +
                    "satellite maps from this device. You will need an internet " +
                    "connection to download them again. Evidence photos, pending " +
                    "uploads, and decisions will be preserved.",
            confirmLabel = "Clear cached downloads",
            confirmTag = "clear-confirm-all-cached-downloads",
        )
    }

    @Test
    fun backDismissesWithoutConfirming() {
        var confirmations = 0
        var cancellations = 0
        setContent(
            pending = StorageClearCategory.GeotiffTiles,
            onConfirm = { confirmations++ },
            onCancel = { cancellations++ },
        )

        composeRule.activityRule.scenario.onActivity {
            it.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.onNodeWithTag("clear-confirmation-dialog").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(0, confirmations)
            assertEquals(1, cancellations)
        }
    }

    @Test
    fun outsideTapDismissesWithoutConfirming() {
        var confirmations = 0
        var cancellations = 0
        setContent(
            pending = StorageClearCategory.CandidateCrops,
            onConfirm = { confirmations++ },
            onCancel = { cancellations++ },
        )

        composeRule.onRoot().performTouchInput { click(Offset(1f, 1f)) }

        composeRule.onNodeWithTag("clear-confirmation-dialog").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(0, confirmations)
            assertEquals(1, cancellations)
        }
    }

    @Test
    fun downloadGate_disablesControlsAndShowsSupportingText() {
        setContent(clear = SettingsClearUiState(isGated = true, isDownloading = true))

        clearActionTags().forEach {
            composeRule.onNodeWithTag(it).performScrollTo().assertIsNotEnabled()
        }
        composeRule
            .onNodeWithTag("clear-download-gated")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onAllNodesWithText("Available after download completes")
            .assertCountEquals(5)
    }

    @Test
    fun clearingGate_disablesControls() {
        setContent(
            clear =
                SettingsClearUiState(
                    active = StorageClearCategory.GeotiffTiles,
                    isGated = true,
                ),
        )

        clearActionTags().forEach {
            composeRule.onNodeWithTag(it).performScrollTo().assertIsNotEnabled()
        }
    }

    @Test
    fun syncGate_disablesControls() {
        setContent(clear = SettingsClearUiState(isGated = true))

        clearActionTags().forEach {
            composeRule.onNodeWithTag(it).performScrollTo().assertIsNotEnabled()
        }
    }

    @Test
    fun temporaryCacheStartsWithoutConfirmation() {
        val requests = mutableListOf<StorageClearCategory>()
        setContent(onRequest = requests::add)

        composeRule
            .onNodeWithTag("clear-temporary-cache")
            .performScrollTo()
            .performClick()

        composeRule.onNodeWithTag("clear-confirmation-dialog").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(listOf(StorageClearCategory.TemporaryCache), requests)
        }
    }

    @Test
    fun evidenceRowHasNoClearAction() {
        setContent()

        composeRule
            .onNodeWithTag("storage-row-5")
            .assert(hasClickAction().not())
            .onChildren()
            .assertCountEquals(0)
        composeRule.onNodeWithText("Evidence photos").assertIsDisplayed()
    }

    private fun assertDialog(
        category: StorageClearCategory,
        title: String,
        body: String,
        confirmLabel: String,
        confirmTag: String,
    ) {
        setContent(pending = category)

        composeRule.onNodeWithText(title).assertIsDisplayed()
        composeRule.onNodeWithText(body).assertIsDisplayed()
        composeRule.onNodeWithText(confirmLabel).assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeRule.onNodeWithTag(confirmTag).assertIsDisplayed()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("clear-cancel").assertIsFocused()
    }

    private fun setContent(
        clear: SettingsClearUiState = SettingsClearUiState(),
        pending: StorageClearCategory? = null,
        onRequest: (StorageClearCategory) -> Unit = {},
        onConfirm: () -> Unit = {},
        onCancel: () -> Unit = {},
    ) {
        composeRule.setContent {
            var pendingCategory by mutableStateOf(pending)
            Stage4Theme {
                SettingsContent(
                    uiState = uiState(clear.copy(pendingConfirmation = pendingCategory)),
                    geotiffState = geotiffState(),
                    onRadiusInput = {},
                    onGeotiffRadiusInput = {},
                    onSave = {},
                    onRetryStorage = {},
                    onClearRequested = onRequest,
                    onClearConfirmed = onConfirm,
                    onClearCancelled = {
                        pendingCategory = null
                        onCancel()
                    },
                    onLogoutRequested = {},
                    onLogoutConfirmed = {},
                    onLogoutCancelled = {},
                )
            }
        }
    }

    private fun uiState(clear: SettingsClearUiState) =
        SettingsUiState(
            currentRadius = 250f,
            inputText = "250",
            error = null,
            saved = false,
            storage = SettingsStorageUiState(displayModel = storageModel()),
            clear = clear,
        )

    private fun geotiffState() =
        GeotiffRadiusUiState(
            inputText = "20",
            error = null,
            saved = false,
        )

    private fun storageModel() =
        StorageDisplayModel(
            rows =
                listOf(
                    StorageDisplayRow("Device free space", "8 GB free of 64 GB"),
                    StorageDisplayRow("Known cached downloads", "10 MB"),
                    StorageDisplayRow("GeoTIFF tiles", "4 MB"),
                    StorageDisplayRow("Candidate crops", "6 MB"),
                    StorageDisplayRow("Satellite maps", "12 MB", "3 regions"),
                    StorageDisplayRow("Evidence photos", "2 MB", "Preserved"),
                    StorageDisplayRow("Temporary cache", "1 MB"),
                ),
        )

    private fun clearActionTags() =
        listOf(
            "clear-geotiff-tiles",
            "clear-candidate-crops",
            "clear-satellite-maps",
            "clear-temporary-cache",
            "clear-all-cached-downloads",
        )
}
