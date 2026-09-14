package au.edu.fireballs.stage4.ui.screen.stage4map

import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import au.edu.fireballs.stage4.domain.model.GeoCoordinate
import au.edu.fireballs.stage4.domain.model.Stage4State
import au.edu.fireballs.stage4.domain.model.Stage4Survey
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import org.junit.Rule
import org.junit.Test

class MapHostSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun dummyState(): Stage4State =
        Stage4State(
            survey = Stage4Survey(id = 1L, eventId = "EVENT_01", tilesetId = null),
            base = GeoCoordinate(-37.8, 145.0),
            surveyedAreas = emptyList(),
            unprocessedCandidates = emptyList(),
            yesMeteorites = emptyList(),
            noMeteorites = emptyList(),
            detectionTags = emptyList(),
            userLocations = emptyList(),
            showGeolocationAccuracyCircle = false,
            latestTaskCreated = "",
        )

    @Test
    fun mapHost_composesWithEffectsInsideContent_noApplierCrash() {
        composeRule.setContent {
            MapHost(
                mapViewportState = rememberMapViewportState(),
                locationPermissionGranted = false,
                state = dummyState(),
                layerToggleState = LayerToggleState(),
                onMarkerClick = {},
                onUserLocationClick = {},
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("map-host-root").assertExists()
    }

    @Test
    fun mapHost_composesWithPermissionEffect_noApplierCrash() {
        composeRule.setContent {
            MapHost(
                mapViewportState = rememberMapViewportState(),
                locationPermissionGranted = false,
                state = dummyState(),
                layerToggleState = LayerToggleState(),
                onMarkerClick = {},
                onUserLocationClick = {},
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("map-host-root").assertExists()
    }

    @Test
    fun mapHost_togglesPermissionEffectFromGrantedToRevoked_noApplierCrash() {
        composeRule.setContent {
            var granted by remember { mutableStateOf(true) }
            MapHost(
                mapViewportState = rememberMapViewportState(),
                locationPermissionGranted = granted,
                state = dummyState(),
                layerToggleState = LayerToggleState(),
                onMarkerClick = {},
                onUserLocationClick = {},
            )
            LaunchedEffect(Unit) {
                granted = false
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("map-host-root").assertExists()
    }
}
