package au.edu.fireballs.stage4.ui.screen.dataentry

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import au.edu.fireballs.stage4.ui.theme.Stage4Theme
import au.edu.fireballs.stage4.ui.util.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsStorageTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun populatedStorage_rendersHeadingAndSevenRowsInSemanticOrder() {
        setContent(storage = storage(model = unavailableModel()))

        composeRule.onNodeWithText("Storage").assertExists()
        val categories =
            listOf(
                "Device free space",
                "Known cached downloads",
                "GeoTIFF tiles",
                "Candidate crops",
                "Satellite maps",
                "Evidence photos",
                "Temporary cache",
            )
        categories.forEachIndexed { index, category ->
            composeRule
                .onNodeWithTag("storage-row-$index")
                .assertExists()
                .assert(hasClickAction().not())
                .assertHeightIsAtLeast(48.dp)
            val description = unavailableModel().rows[index].semanticsDescription
            composeRule
                .onNodeWithTag("storage-row-$index")
                .assertContentDescriptionEquals(description)
            assertTrue(description.startsWith(category))
            assertEquals(
                1,
                composeRule
                    .onAllNodesWithContentDescription(description)
                    .fetchSemanticsNodes()
                    .size,
            )
        }
    }

    @Test
    fun unavailableSatelliteAndEvidence_exposeRequiredTextAndSemantics() {
        setContent(storage = storage(model = unavailableModel()))

        composeRule
            .onNodeWithContentDescription(
                "Satellite maps, 3 regions, Size unavailable",
            ).assertExists()
        composeRule
            .onNodeWithContentDescription("Evidence photos, 2 MB, Preserved")
            .assertExists()
        composeRule.onNodeWithText("Size unavailable").assertDoesNotExist()
        composeRule.onNodeWithText("Delete").assertDoesNotExist()
        composeRule.onNodeWithTag("clear-geotiff-tiles").assertExists()
        composeRule.onNodeWithTag("clear-candidate-crops").assertExists()
        composeRule.onNodeWithTag("clear-satellite-maps").assertExists()
        composeRule.onNodeWithTag("clear-temporary-cache").assertExists()
    }

    @Test
    fun measuredSatellite_retainsRegionCountAndHasNoUnavailableStatus() {
        val model =
            unavailableModel().copy(
                rows =
                    unavailableModel().rows.map {
                        if (it.category == "Satellite maps") {
                            it.copy(value = "12 MB", status = "3 regions")
                        } else {
                            it
                        }
                    },
            )
        setContent(storage = storage(model = model))

        composeRule
            .onNodeWithContentDescription("Satellite maps, 12 MB, 3 regions")
            .assertExists()
        composeRule.onNodeWithText("Size unavailable").assertDoesNotExist()
    }

    @Test
    fun loading_isVisibleAndPoliteWithStaleRowsRetained() {
        setContent(storage = storage(model = unavailableModel(), loading = true))

        composeRule
            .onNodeWithTag("storage-loading")
            .assertTextContains("Calculating storage usage")
            .assert(hasPoliteLiveRegion())
        composeRule.onNodeWithTag("storage-row-0").assertExists()
        composeRule.onNodeWithTag("storage-row-6").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun failure_isPoliteRetainsRowsAndRetryHasAccessibleTarget() {
        var retries = 0
        setContent(
            storage =
                storage(
                    model = unavailableModel(),
                    error = "Could not calculate storage usage",
                ),
            onRetry = { retries++ },
        )

        composeRule
            .onNodeWithTag("storage-error")
            .assertTextContains("Could not calculate storage usage")
            .assert(hasPoliteLiveRegion())
        composeRule.onNodeWithTag("storage-row-0").assertExists()
        composeRule
            .onNodeWithTag("storage-retry")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun initialFailure_hasNoInventedRows() {
        setContent(
            storage =
                storage(
                    error = "Could not calculate storage usage",
                ),
        )

        composeRule.onNodeWithTag("storage-error").assertExists()
        composeRule.onNodeWithTag("storage-retry").performScrollTo().assertExists()
        composeRule.onNodeWithTag("storage-row-0").assertDoesNotExist()
    }

    @Test
    fun zeroValuesRemainVisible() {
        val rows =
            unavailableModel().rows.map {
                if (it.category == "Satellite maps") {
                    it.copy(value = "0 regions", status = "Size unavailable")
                } else {
                    it.copy(value = "0 B")
                }
            }
        setContent(storage = storage(model = StorageDisplayModel(rows)))

        composeRule.onNodeWithContentDescription("GeoTIFF tiles, 0 B").assertExists()
        composeRule
            .onNodeWithContentDescription(
                "Satellite maps, 0 regions, Size unavailable",
            ).assertExists()
    }

    @Test
    fun enlargedFont_scrollsToLastWrappedValueWithoutClipping() {
        composeRule.setContent {
            val density = LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2.0f),
            ) {
                Stage4Theme {
                    SettingsContent(
                        uiState = uiState(storage(model = unavailableModel())),
                        geotiffState = geotiffState(),
                        onRadiusInput = {},
                        onGeotiffRadiusInput = {},
                        onSave = {},
                        onRetryStorage = {},
                        onClearRequested = {},
                        onClearConfirmed = {},
                        onClearCancelled = {},
                        modifier = Modifier.height(360.dp),
                    )
                }
            }
        }

        composeRule
            .onNodeWithTag("storage-row-6")
            .performScrollTo()
            .assertIsDisplayed()
            .assertContentDescriptionEquals("Temporary cache, 0 B")
    }

    private fun setContent(
        storage: SettingsStorageUiState,
        onRetry: () -> Unit = {},
    ) {
        composeRule.setContent {
            Stage4Theme {
                SettingsContent(
                    uiState = uiState(storage),
                    geotiffState = geotiffState(),
                    onRadiusInput = {},
                    onGeotiffRadiusInput = {},
                    onSave = {},
                    onRetryStorage = onRetry,
                    onClearRequested = {},
                    onClearConfirmed = {},
                    onClearCancelled = {},
                )
            }
        }
    }

    private fun uiState(storage: SettingsStorageUiState) =
        SettingsUiState(
            currentRadius = 250f,
            inputText = "250",
            error = null,
            saved = false,
            storage = storage,
        )

    private fun geotiffState() =
        GeotiffRadiusUiState(
            inputText = "20",
            error = null,
            saved = false,
        )

    private fun storage(
        model: StorageDisplayModel? = null,
        loading: Boolean = false,
        error: String? = null,
    ) = SettingsStorageUiState(
        displayModel = model,
        isLoading = loading,
        error = error?.let(UiText::DynamicString),
    )

    private fun unavailableModel() =
        StorageDisplayModel(
            rows =
                listOf(
                    StorageDisplayRow("Device free space", "8 GB free of 64 GB"),
                    StorageDisplayRow("Known cached downloads", "10 MB"),
                    StorageDisplayRow("GeoTIFF tiles", "4 MB"),
                    StorageDisplayRow("Candidate crops", "6 MB"),
                    StorageDisplayRow("Satellite maps", "3 regions", "Size unavailable"),
                    StorageDisplayRow("Evidence photos", "2 MB", "Preserved"),
                    StorageDisplayRow("Temporary cache", "0 B"),
                ),
        )

    private fun hasPoliteLiveRegion() =
        SemanticsMatcher.expectValue(
            SemanticsProperties.LiveRegion,
            LiveRegionMode.Polite,
        )
}
