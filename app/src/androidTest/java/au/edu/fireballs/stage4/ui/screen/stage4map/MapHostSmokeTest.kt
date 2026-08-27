package au.edu.fireballs.stage4.ui.screen.stage4map

import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import org.junit.Rule
import org.junit.Test

class MapHostSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun mapHost_composesWithEffectsInsideContent_noApplierCrash() {
        composeRule.setContent {
            MapHost(
                mapViewportState = rememberMapViewportState(),
                locationPermissionGranted = false,
                polygons = emptyList(),
                tilesetId = null,
                base = null,
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
                locationPermissionGranted = true,
                polygons = listOf(emptyList()),
                tilesetId = null,
                base = null,
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
                polygons = emptyList(),
                tilesetId = null,
                base = null,
            )
            LaunchedEffect(Unit) {
                granted = false
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("map-host-root").assertExists()
    }
}
