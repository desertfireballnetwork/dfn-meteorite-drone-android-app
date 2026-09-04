package au.edu.fireballs.stage4.ui.screen.stage4map

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import au.edu.fireballs.stage4.data.tiles.LocalFileRasterTileProvider
import au.edu.fireballs.stage4.data.tiles.TileStore
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files

class CustomRasterOverlaySmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun customRasterOverlay_nullTileStore_noCrash() {
        composeRule.setContent {
            MapHost(
                mapViewportState = rememberMapViewportState(),
                locationPermissionGranted = false,
                polygons = emptyList(),
                tilesetId = null,
                base = null,
                surveyId = 1L,
                candidateId = 2L,
                tileStore = null,
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("map-host-root").assertExists()
    }

    @Test
    fun customRasterOverlay_noTilesForSurvey_noCrash() {
        val store = TileStore(Files.createTempDirectory("tiles").toFile())
        composeRule.setContent {
            MapHost(
                mapViewportState = rememberMapViewportState(),
                locationPermissionGranted = false,
                polygons = emptyList(),
                tilesetId = null,
                base = null,
                surveyId = 1L,
                candidateId = 2L,
                tileStore = store,
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("map-host-root").assertExists()
    }

    @Test
    fun customRasterOverlay_withTilesForSurvey_noCrash() {
        val store = TileStore(Files.createTempDirectory("tiles").toFile())
        store.write(1L, 2L, 0, 0, 0, LocalFileRasterTileProvider.TRANSPARENT_PNG)
        composeRule.setContent {
            MapHost(
                mapViewportState = rememberMapViewportState(),
                locationPermissionGranted = false,
                polygons = emptyList(),
                tilesetId = null,
                base = null,
                surveyId = 1L,
                candidateId = 2L,
                tileStore = store,
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("map-host-root").assertExists()
    }

    @Test
    fun customRasterOverlay_corruptTile_noCrash() {
        val store = TileStore(Files.createTempDirectory("tiles").toFile())
        store.write(1L, 2L, 0, 0, 0, byteArrayOf(1, 2, 3, 4, 5))
        composeRule.setContent {
            MapHost(
                mapViewportState = rememberMapViewportState(),
                locationPermissionGranted = false,
                polygons = emptyList(),
                tilesetId = null,
                base = null,
                surveyId = 1L,
                candidateId = 2L,
                tileStore = store,
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("map-host-root").assertExists()
    }
}
