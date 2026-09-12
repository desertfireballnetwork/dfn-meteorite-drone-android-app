package au.edu.fireballs.stage4.ui.screen.candidate

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import au.edu.fireballs.stage4.domain.model.BoundingBox
import au.edu.fireballs.stage4.domain.model.GeoCoordinate
import au.edu.fireballs.stage4.domain.model.ImageDims
import au.edu.fireballs.stage4.domain.model.Stage4Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CandidateMapTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun createCandidate(
        id: Long = 42L,
        centroid: GeoCoordinate? = GeoCoordinate(-31.95, 115.86),
    ): Stage4Candidate =
        Stage4Candidate(
            inferenceResultId = id,
            imageId = 100L,
            imageFilename = "drone_test.jpg",
            imageDims = ImageDims(w = 4000, h = 3000),
            geoCentroid = centroid,
            geoArea = null,
            box = BoundingBox(x = 100, y = 100, w = 50, h = 50),
            confidence = 0.95,
            sizeM = null,
            claimedByMe = false,
            claimedByOther = false,
        )

    @Test
    fun tileSourceParameters_matchSpecification() {
        assertEquals("candidate_raster_tiles", CandidateMapDefaults.SOURCE_KEY)
        assertEquals("candidate-tiles", CandidateMapDefaults.LAYER_ID)
        assertEquals(128L, CandidateMapDefaults.TILE_SIZE)
        assertEquals(20L, CandidateMapDefaults.MIN_ZOOM)
        assertEquals(22L, CandidateMapDefaults.MAX_ZOOM)
        assertEquals(24.0, CandidateMapDefaults.CANDIDATE_ZOOM, 0.001)
        assertEquals(25.0, CandidateMapDefaults.MAX_CAMERA_ZOOM, 0.001)
        assertEquals("candidate-map-root", CandidateMapDefaults.ROOT_TAG)
        assertEquals("candidate-map-marker", CandidateMapDefaults.MARKER_TAG)
    }

    @Test
    fun sourceAndLayerKeys_areCandidateSpecific() {
        assertNotEquals(CandidateMapDefaults.sourceKey(42L), CandidateMapDefaults.sourceKey(43L))
        assertNotEquals(CandidateMapDefaults.layerId(42L), CandidateMapDefaults.layerId(43L))
    }

    @Test
    fun candidateMap_withCentroid_rendersRootAndMarker() {
        val candidate = createCandidate()
        val tileUrl = "https://example.com/tiles/{z}/{x}/{y}.png"

        composeRule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                CandidateMap(
                    candidate = candidate,
                    tileUrlPattern = tileUrl,
                )
            }
        }

        composeRule.onNodeWithTag("candidate-map-root").assertExists()
        composeRule.onNodeWithTag("candidate-map-marker").assertExists()
    }

    @Test
    fun candidateMap_withoutCentroid_rendersRootWithoutMarker() {
        val candidate = createCandidate(centroid = null)
        val tileUrl = "https://example.com/tiles/{z}/{x}/{y}.png"

        composeRule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                CandidateMap(
                    candidate = candidate,
                    tileUrlPattern = tileUrl,
                )
            }
        }

        composeRule.onNodeWithTag("candidate-map-root").assertExists()
        composeRule.onNodeWithTag("candidate-map-marker").assertDoesNotExist()
    }

    @Test
    fun candidateMap_withEmptyTileUrlPattern_rendersRoot() {
        val candidate = createCandidate()

        composeRule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                CandidateMap(
                    candidate = candidate,
                    tileUrlPattern = "",
                )
            }
        }

        composeRule.onNodeWithTag("candidate-map-root").assertExists()
    }
}
