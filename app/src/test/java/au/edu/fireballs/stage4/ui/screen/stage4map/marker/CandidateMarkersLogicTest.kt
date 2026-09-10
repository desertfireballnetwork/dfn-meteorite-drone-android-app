package au.edu.fireballs.stage4.ui.screen.stage4map.marker

import au.edu.fireballs.stage4.domain.model.BoundingBox
import au.edu.fireballs.stage4.domain.model.GeoCoordinate
import au.edu.fireballs.stage4.domain.model.ImageDims
import au.edu.fireballs.stage4.domain.model.Stage4Candidate
import au.edu.fireballs.stage4.domain.model.Stage4State
import au.edu.fireballs.stage4.domain.model.Stage4Survey
import au.edu.fireballs.stage4.ui.screen.stage4map.LayerToggleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateMarkersLogicTest {
    private fun createCandidate(
        id: Long,
        lat: Double? = -37.8,
        lon: Double = 145.0,
        claimedByMe: Boolean = false,
        claimedByOther: Boolean = false,
    ): Stage4Candidate =
        Stage4Candidate(
            inferenceResultId = id,
            imageId = 1000L + id,
            imageFilename = "img_$id.jpg",
            imageDims = ImageDims(w = 100, h = 100),
            geoCentroid = lat?.let { GeoCoordinate(latitude = it, longitude = lon) },
            geoArea = null,
            box = BoundingBox(x = 0, y = 0, w = 10, h = 10),
            confidence = 0.9,
            sizeM = null,
            claimedByMe = claimedByMe,
            claimedByOther = claimedByOther,
        )

    private fun createState(
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
    fun buildVisibleCandidates_includesAllLayersWhenVisible() {
        val yes = createCandidate(1L)
        val no = createCandidate(2L)
        val unprocessed = createCandidate(3L)
        val state =
            createState(unprocessed = listOf(unprocessed), yes = listOf(yes), no = listOf(no))

        val result =
            buildVisibleCandidates(
                state = state,
                toggleState =
                    LayerToggleState(
                        showYes = true,
                        showNo = true,
                        showUnprocessed = true,
                    ),
            )

        assertEquals(setOf(1L, 2L, 3L), result.map { it.candidate.inferenceResultId }.toSet())
    }

    @Test
    fun buildVisibleCandidates_excludesHiddenLayers() {
        val yes = createCandidate(1L)
        val no = createCandidate(2L)
        val unprocessed = createCandidate(3L)
        val state =
            createState(unprocessed = listOf(unprocessed), yes = listOf(yes), no = listOf(no))

        val result =
            buildVisibleCandidates(
                state = state,
                toggleState =
                    LayerToggleState(
                        showYes = false,
                        showNo = true,
                        showUnprocessed = false,
                    ),
            )

        assertEquals(listOf(2L), result.map { it.candidate.inferenceResultId })
    }

    @Test
    fun buildVisibleCandidates_excludesNullLocationCandidates() {
        val withLocation = createCandidate(1L)
        val withoutLocation = createCandidate(2L, lat = null)
        val state = createState(unprocessed = listOf(withLocation, withoutLocation))

        val result =
            buildVisibleCandidates(
                state = state,
                toggleState = LayerToggleState(showUnprocessed = true),
            )

        assertEquals(listOf(1L), result.map { it.candidate.inferenceResultId })
    }

    @Test
    fun buildVisibleCandidates_returnsEmptyWhenAllLayersHidden() {
        val yes = createCandidate(1L)
        val state = createState(yes = listOf(yes))

        val result =
            buildVisibleCandidates(
                state = state,
                toggleState =
                    LayerToggleState(
                        showYes = false,
                        showNo = false,
                        showUnprocessed = false,
                    ),
            )

        assertTrue(result.isEmpty())
    }

    @Test
    fun buildVisibleCandidates_setsVerdictAndClaimPerCandidate() {
        val yes = createCandidate(1L, claimedByMe = true)
        val no = createCandidate(2L, claimedByOther = true)
        val unprocessed = createCandidate(3L)
        val state =
            createState(unprocessed = listOf(unprocessed), yes = listOf(yes), no = listOf(no))

        val result =
            buildVisibleCandidates(
                state = state,
                toggleState =
                    LayerToggleState(
                        showYes = true,
                        showNo = true,
                        showUnprocessed = true,
                    ),
            )

        val byId = result.associateBy { it.candidate.inferenceResultId }
        assertEquals(1, byId.getValue(1L).verdict)
        assertEquals(1, byId.getValue(1L).claim)
        assertEquals(2, byId.getValue(2L).verdict)
        assertEquals(2, byId.getValue(2L).claim)
        assertEquals(0, byId.getValue(3L).verdict)
        assertEquals(0, byId.getValue(3L).claim)
    }
}
