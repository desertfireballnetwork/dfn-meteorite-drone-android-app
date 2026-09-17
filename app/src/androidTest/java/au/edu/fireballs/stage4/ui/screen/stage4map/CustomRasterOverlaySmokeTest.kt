package au.edu.fireballs.stage4.ui.screen.stage4map

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import au.edu.fireballs.stage4.domain.model.Stage4State
import au.edu.fireballs.stage4.domain.model.Stage4Survey
import com.mapbox.maps.MapView
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

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
                onUserLocationClick = {},
                overlayCandidates = emptyList(),
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("map-host-root").assertExists()
    }

    @Test
    fun customRasterOverlay_withUrl_installsSourceAndLayer() {
        composeRule.setContent {
            MapHost(
                mapViewportState = rememberMapViewportState(),
                locationPermissionGranted = false,
                state = emptyState(),
                layerToggleState = LayerToggleState(),
                onMarkerClick = {},
                onUserLocationClick = {},
                overlayCandidates =
                    listOf(
                        2L to
                            "https://find.gfo.rocks/image_geotiff_candidate_tile/1/2/{z}/{x}/{y}/",
                    ),
            )
        }
        composeRule.waitForIdle()

        val mapView = findMapView(composeRule.activity.window.decorView)
        assertNotNull("MapView should be present", mapView)

        val map = mapView?.mapboxMap
        assertNotNull("MapboxMap should be present", map)

        composeRule.waitUntil(timeoutMillis = 15_000) {
            var installed = false
            composeRule.runOnUiThread {
                val hasSource = map?.styleSources?.any { it.type == "raster" } == true
                val hasLayer = map?.styleLayerExists("custom_raster_layer_1_2") == true
                installed = hasSource && hasLayer
            }
            installed
        }
        composeRule.runOnUiThread {
            assertTrue(
                "custom raster source should be installed",
                map?.styleSources?.any { it.type == "raster" } == true,
            )
            assertTrue(
                "custom_raster_layer should be installed",
                map?.styleLayerExists("custom_raster_layer_1_2") == true,
            )
        }
    }

    private fun findMapView(view: View): MapView? =
        when (view) {
            is MapView -> view
            is ViewGroup ->
                view
                    .children()
                    .mapNotNull { findMapView(it) }
                    .firstOrNull()

            else -> null
        }

    private fun ViewGroup.children(): Sequence<View> =
        (0 until childCount).asSequence().map { getChildAt(it) }

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
