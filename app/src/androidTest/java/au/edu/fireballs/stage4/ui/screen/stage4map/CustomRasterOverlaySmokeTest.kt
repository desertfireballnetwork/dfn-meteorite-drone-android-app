package au.edu.fireballs.stage4.ui.screen.stage4map

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import au.edu.fireballs.stage4.data.tiles.LocalFileRasterTileProvider
import au.edu.fireballs.stage4.data.tiles.TileStore
import au.edu.fireballs.stage4.domain.model.Stage4State
import au.edu.fireballs.stage4.domain.model.Stage4Survey
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files

class CustomRasterOverlaySmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun customRasterOverlay_noTiles_noCrash() {
        composeRule.setContent {
            MapHost(
                mapViewportState = rememberMapViewportState(),
                locationPermissionGranted = false,
                state = emptyState(),
                layerToggleState = LayerToggleState(),
                onMarkerClick = {},
                candidateId = 2L,
                tileStore = null,
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("map-host-root").assertExists()
    }

    @Test
    fun customRasterOverlay_withTiles_noCrash() {
        val store = TileStore(Files.createTempDirectory("tiles").toFile())
        store.write(1L, 2L, 0, 0, 0, LocalFileRasterTileProvider.TRANSPARENT_PNG)
        composeRule.setContent {
            MapHost(
                mapViewportState = rememberMapViewportState(),
                locationPermissionGranted = false,
                state = emptyState(),
                layerToggleState = LayerToggleState(),
                onMarkerClick = {},
                candidateId = 2L,
                tileStore = store,
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("map-host-root").assertExists()
    }

    private fun emptyState(): Stage4State =
        Stage4State(
            survey = Stage4Survey(id = 1L, eventId = "DN240703-02", tilesetId = null),
            base = null,
            surveyedAreas = emptyList(),
            unprocessedCandidates = emptyList(),
            yesMeteorites = emptyList(),
            noMeteorites = emptyList(),
            detectionTags = emptyList(),
            userLocations = emptyList(),
            showGeolocationAccuracyCircle = false,
            latestTaskCreated = "2026-08-26T00:00:00Z",
        )
}
