package au.edu.fireballs.stage4.ui.screen.stage4map.marker

import au.edu.fireballs.stage4.R
import au.edu.fireballs.stage4.domain.model.BoundingBox
import au.edu.fireballs.stage4.domain.model.ImageDims
import au.edu.fireballs.stage4.domain.model.Stage4Candidate
import au.edu.fireballs.stage4.ui.theme.DFNColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkerStyleTest {
    private val colors = DFNColors()
    private val fakeCandidate =
        Stage4Candidate(
            inferenceResultId = 1L,
            imageId = 1L,
            imageFilename = "test.jpg",
            imageDims = ImageDims(100, 100),
            geoCentroid = null,
            geoArea = null,
            box = BoundingBox(0, 0, 10, 10),
            confidence = 0.9,
            sizeM = null,
            claimedByMe = false,
        )

    @Test
    fun getCandidateMarkerStyle_yes_returnsCorrectStyle() {
        val style = getCandidateMarkerStyle(fakeCandidate, verdict = 1, colors = colors)
        assertEquals(R.drawable.marker_yes, style.iconRes)
        assertEquals(MarkerStyleDefaults.YES_SIZE, style.size)
        assertEquals(1.0f, style.opacity)
    }

    @Test
    fun getCandidateMarkerStyle_no_returnsCorrectStyle() {
        val style = getCandidateMarkerStyle(fakeCandidate, verdict = 2, colors = colors)
        assertEquals(R.drawable.marker_no, style.iconRes)
        assertEquals(MarkerStyleDefaults.NO_SIZE, style.size)
        assertEquals(MarkerStyleDefaults.NO_OPACITY, style.opacity)
    }

    @Test
    fun getCandidateMarkerStyle_unprocessed_returnsCorrectStyle() {
        val style = getCandidateMarkerStyle(fakeCandidate, verdict = 0, colors = colors)
        assertEquals(R.drawable.marker_unprocessed, style.iconRes)
        assertEquals(MarkerStyleDefaults.UNPROCESSED_SIZE, style.size)
        assertEquals(1.0f, style.opacity)
    }

    @Test
    fun getCandidateMarkerStyle_claimedByMe_returnsBorderColor() {
        val claimed = fakeCandidate.copy(claimedByMe = true)
        val style = getCandidateMarkerStyle(claimed, verdict = 0, colors = colors)
        assertTrue(colors.markerClaimedByMeOutline == style.claimBorderColor)
    }

    @Test
    fun getCandidateMarkerStyle_notClaimed_returnsNullBorderColor() {
        val style = getCandidateMarkerStyle(fakeCandidate, verdict = 0, colors = colors)
        assertNull(style.claimBorderColor)
    }
}
