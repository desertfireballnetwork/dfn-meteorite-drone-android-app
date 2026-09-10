package au.edu.fireballs.stage4.ui.screen.candidate

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import au.edu.fireballs.stage4.data.repository.CandidateImageRepository
import au.edu.fireballs.stage4.domain.model.BoundingBox
import au.edu.fireballs.stage4.domain.model.GeoCoordinate
import au.edu.fireballs.stage4.domain.model.ImageDims
import au.edu.fireballs.stage4.domain.model.Stage4Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CandidateModalTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun createCandidate(
        id: Long = 42L,
        centroid: GeoCoordinate? = GeoCoordinate(-31.95, 115.86),
        confidence: Double = 0.95,
    ): Stage4Candidate =
        Stage4Candidate(
            inferenceResultId = id,
            imageId = 7L,
            imageFilename = "drone_test.jpg",
            imageDims = ImageDims(w = 4000, h = 3000),
            geoCentroid = centroid,
            geoArea = null,
            box = BoundingBox(x = 100, y = 100, w = 50, h = 50),
            confidence = confidence,
            sizeM = null,
            claimedByMe = false,
            claimedByOther = false,
        )

    @Test
    fun formatCoordinates_formatsCorrectly() {
        val coord = GeoCoordinate(-31.95, 115.86)
        assertEquals("31.950000°S, 115.860000°E", formatCoordinates(coord))

        val northernWestern = GeoCoordinate(12.345678, -45.678901)
        assertEquals("12.345678°N, 45.678901°W", formatCoordinates(northernWestern))

        assertEquals("N/A", formatCoordinates(null))
    }

    @Test
    fun candidateModal_headerDisplaysCandidateIdCoordinatesAndConfidence() {
        val candidate = createCandidate(id = 42L, confidence = 0.95)

        composeRule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                CandidateModal(
                    candidate = candidate,
                    uiState = CandidateUiState(candidate = candidate, surveyId = 1L),
                    onClose = {},
                    onSelectMode = {},
                )
            }
        }

        composeRule.onNodeWithText("Candidate #42").assertExists()
        composeRule.onNodeWithText("31.950000°S, 115.860000°E").assertExists()
        composeRule.onNodeWithText("Confidence: 0.95").assertExists()
    }

    @Test
    fun candidateModal_headerDisplaysNAWhenCoordinatesAreNull() {
        val candidate = createCandidate(id = 99L, centroid = null)

        composeRule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                CandidateModal(
                    candidate = candidate,
                    uiState = CandidateUiState(candidate = candidate, surveyId = 1L),
                    onClose = {},
                    onSelectMode = {},
                )
            }
        }

        composeRule.onNodeWithText("Candidate #99").assertExists()
        composeRule.onNodeWithText("N/A").assertExists()
    }

    @Test
    fun candidateModal_clickingCloseButtonCallsOnClose() {
        val candidate = createCandidate()
        var closed = false

        composeRule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                CandidateModal(
                    candidate = candidate,
                    uiState = CandidateUiState(candidate = candidate, surveyId = 1L),
                    onClose = { closed = true },
                    onSelectMode = {},
                )
            }
        }

        composeRule.onNodeWithTag("candidate-modal-close-button").performClick()
        assertTrue(closed)
    }

    @Test
    fun candidateModal_viewModeToggleSwitchesModes() {
        val candidate = createCandidate()

        composeRule.setContent {
            var currentMode by remember { mutableStateOf(CandidateViewMode.MAP) }
            CompositionLocalProvider(LocalInspectionMode provides true) {
                CandidateModal(
                    candidate = candidate,
                    uiState =
                        CandidateUiState(
                            candidate = candidate,
                            surveyId = 1L,
                            viewMode = currentMode,
                        ),
                    onClose = {},
                    onSelectMode = { currentMode = it },
                )
            }
        }

        composeRule.onNodeWithTag("candidate-view-toggle").assertExists()
        composeRule.onNodeWithText("Image").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Map").performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun candidateModal_withViewModel_initializesAndTogglesViewMode() {
        val candidate = createCandidate(id = 42L)
        val imageRepository: CandidateImageRepository = mock()
        whenever(imageRepository.getCroppedImageUrl(42L))
            .thenReturn("https://example.com/crop/42")
        whenever(imageRepository.getCandidateTileUrlPattern(10L, 42L))
            .thenReturn("https://example.com/tiles/10/42/{z}/{x}/{y}/")

        val viewModel = CandidateViewModel(imageRepository)

        composeRule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                CandidateModal(
                    candidate = candidate,
                    surveyId = 10L,
                    onClose = {},
                    viewModel = viewModel,
                )
            }
        }

        composeRule.waitForIdle()
        assertEquals(CandidateViewMode.MAP, viewModel.uiState.value?.viewMode)

        composeRule.onNodeWithText("Image").performClick()
        composeRule.waitForIdle()
        assertEquals(CandidateViewMode.IMAGE, viewModel.uiState.value?.viewMode)

        composeRule.onNodeWithText("Map").performClick()
        composeRule.waitForIdle()
        assertEquals(CandidateViewMode.MAP, viewModel.uiState.value?.viewMode)
    }

    @Test
    fun candidateModal_bothMapAndImageExistInComposition() {
        val candidate = createCandidate()

        composeRule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                CandidateModal(
                    candidate = candidate,
                    uiState = CandidateUiState(candidate = candidate, surveyId = 1L),
                    onClose = {},
                    onSelectMode = {},
                )
            }
        }

        composeRule.onNodeWithTag("candidate-map-root").assertExists()
        composeRule.onNodeWithTag("candidate-image-view").assertExists()
    }
}
