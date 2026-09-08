package au.edu.fireballs.stage4.ui.screen.stage4map.marker

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import au.edu.fireballs.stage4.domain.model.BoundingBox
import au.edu.fireballs.stage4.domain.model.GeoCoordinate
import au.edu.fireballs.stage4.domain.model.ImageDims
import au.edu.fireballs.stage4.domain.model.Stage4Candidate
import au.edu.fireballs.stage4.domain.model.Stage4State
import au.edu.fireballs.stage4.domain.model.Stage4Survey
import au.edu.fireballs.stage4.ui.screen.stage4map.LayerToggleState
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import org.junit.Rule
import org.junit.Test

class CandidateMarkersTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun createCandidate(id: Long, lat: Double, lon: Double): Stage4Candidate =
        Stage4Candidate(
            inferenceResultId = id,
            imageId = 1000L + id,
            imageFilename = "img_$id.jpg",
            imageDims = ImageDims(w = 100, h = 100),
            geoCentroid = GeoCoordinate(latitude = lat, longitude = lon),
            geoArea = null,
            box = BoundingBox(x = 0, y = 0, w = 10, h = 10),
            confidence = 0.9,
            sizeM = null,
            claimedByMe = false,
            claimedByOther = false,
        )

    private fun createDummyState(
        unprocessed: List<Stage4Candidate> = emptyList(),
        yes: List<Stage4Candidate> = emptyList(),
        no: List<Stage4Candidate> = emptyList(),
    ): Stage4State =
        Stage4State(
            survey = Stage4Survey(id = 1L, eventId = "EVENT_01", tilesetId = null),
            base = GeoCoordinate(-37.8, 145.0),
            surveyedAreas = emptyList(),
            unprocessedCandidates = unprocessed,
            yesMeteorites = yes,
            noMeteorites = no,
            detectionTags = emptyList(),
            userLocations = emptyList(),
            showGeolocationAccuracyCircle = false,
            latestTaskCreated = "",
        )

    @Test
    fun markers_areDisplayedWhenLayerIsVisible() {
        val candYes = createCandidate(1L, -37.8, 145.0)
        val candNo = createCandidate(2L, -37.81, 145.01)
        val candUnprocessed = createCandidate(3L, -37.82, 145.02)

        val state =
            createDummyState(
                unprocessed = listOf(candUnprocessed),
                yes = listOf(candYes),
                no = listOf(candNo),
            )

        composeRule.setContent {
            MapboxMap(
                mapViewportState = rememberMapViewportState(),
            ) {
                CandidateMarkers(
                    state = state,
                    toggleState =
                        LayerToggleState(
                            showYes = true,
                            showNo = true,
                            showUnprocessed = true,
                        ),
                    onMarkerClick = {},
                )
            }
        }

        composeRule.onNodeWithTag("candidate-marker-1").assertIsDisplayed()
        composeRule.onNodeWithTag("candidate-marker-2").assertIsDisplayed()
        composeRule.onNodeWithTag("candidate-marker-3").assertIsDisplayed()
    }

    @Test
    fun markers_areHiddenWhenLayerIsToggledOff() {
        val candYes = createCandidate(1L, -37.8, 145.0)
        val candNo = createCandidate(2L, -37.81, 145.01)
        val candUnprocessed = createCandidate(3L, -37.82, 145.02)

        val state =
            createDummyState(
                unprocessed = listOf(candUnprocessed),
                yes = listOf(candYes),
                no = listOf(candNo),
            )

        composeRule.setContent {
            MapboxMap(
                mapViewportState = rememberMapViewportState(),
            ) {
                CandidateMarkers(
                    state = state,
                    toggleState =
                        LayerToggleState(
                            showYes = false,
                            showNo = true,
                            showUnprocessed = false,
                        ),
                    onMarkerClick = {},
                )
            }
        }

        composeRule.onNodeWithTag("candidate-marker-1").assertDoesNotExist()
        composeRule.onNodeWithTag("candidate-marker-2").assertIsDisplayed()
        composeRule.onNodeWithTag("candidate-marker-3").assertDoesNotExist()
    }

    @Test
    fun markers_withNullLocationAreNotDisplayed() {
        val candNullLoc =
            createCandidate(4L, 0.0, 0.0).copy(
                geoCentroid = null,
            )

        val state = createDummyState(unprocessed = listOf(candNullLoc))

        composeRule.setContent {
            MapboxMap(
                mapViewportState = rememberMapViewportState(),
            ) {
                CandidateMarkers(
                    state = state,
                    toggleState = LayerToggleState(showUnprocessed = true),
                    onMarkerClick = {},
                )
            }
        }

        composeRule.onNodeWithTag("candidate-marker-4").assertDoesNotExist()
    }
}
