package au.edu.fireballs.stage4.ui.screen.candidate

import app.cash.turbine.test
import au.edu.fireballs.stage4.data.repository.CandidateImageRepository
import au.edu.fireballs.stage4.domain.model.BoundingBox
import au.edu.fireballs.stage4.domain.model.ImageDims
import au.edu.fireballs.stage4.domain.model.Stage4Candidate
import coil.request.ImageRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class CandidateViewModelTest {
    private val imageRepository: CandidateImageRepository = mock()
    private lateinit var viewModel: CandidateViewModel

    @Before
    fun setUp() {
        viewModel = CandidateViewModel(imageRepository)
    }

    private fun createCandidate(
        id: Long = 42L,
        imageId: Long = 7L,
    ): Stage4Candidate =
        Stage4Candidate(
            inferenceResultId = id,
            imageId = imageId,
            imageFilename = "drone_image_001.jpg",
            imageDims = ImageDims(w = 3000, h = 3000),
            geoCentroid = null,
            geoArea = null,
            box = BoundingBox(x = 100, y = 100, w = 50, h = 50),
            confidence = 0.95,
            sizeM = null,
        )

    @Test
    fun `initial uiState is null`() =
        runTest {
            assertNull(viewModel.uiState.value)
        }

    @Test
    fun `initialization populates candidate and media model`() =
        runTest {
            val candidate = createCandidate(id = 42L)
            val surveyId = 10L
            val imageRequest: ImageRequest = mock()
            val tilePattern =
                "https://find.gfo.rocks/image_geotiff_candidate_tile/10/42/{z}/{x}/{y}/"

            whenever(imageRepository.buildCroppedImageRequest(42L, surveyId))
                .thenReturn(imageRequest)
            whenever(imageRepository.getCandidateTileUrlPattern(10L, 42L)).thenReturn(tilePattern)

            viewModel.uiState.test {
                assertNull(awaitItem())

                viewModel.initialize(candidate, surveyId)

                val state = awaitItem()
                assertEquals(candidate, state?.candidate)
                assertEquals(surveyId, state?.surveyId)
                assertEquals(CandidateViewMode.MAP, state?.viewMode)
                assertEquals(imageRequest, state?.croppedImageModel)
                assertEquals(tilePattern, state?.tileUrlPattern)
            }
        }

    @Test
    fun `changing view mode updates StateFlow`() =
        runTest {
            val candidate = createCandidate(id = 42L)
            val surveyId = 10L
            whenever(imageRepository.buildCroppedImageRequest(42L, surveyId))
                .thenReturn(mock())
            whenever(imageRepository.getCandidateTileUrlPattern(10L, 42L))
                .thenReturn("https://find.gfo.rocks/tiles/10/42/{z}/{x}/{y}/")

            viewModel.initialize(candidate, surveyId)

            viewModel.uiState.test {
                val initial = awaitItem()
                assertEquals(CandidateViewMode.MAP, initial?.viewMode)

                viewModel.setViewMode(CandidateViewMode.IMAGE)
                val updated = awaitItem()
                assertEquals(CandidateViewMode.IMAGE, updated?.viewMode)

                viewModel.setViewMode(CandidateViewMode.MAP)
                val reverted = awaitItem()
                assertEquals(CandidateViewMode.MAP, reverted?.viewMode)
            }
        }

    @Test
    fun `calling initialize again for same candidate preserves current viewMode`() =
        runTest {
            val candidate = createCandidate(id = 42L)
            val surveyId = 10L
            whenever(imageRepository.buildCroppedImageRequest(42L, surveyId))
                .thenReturn(mock())
            whenever(imageRepository.getCandidateTileUrlPattern(10L, 42L))
                .thenReturn("https://find.gfo.rocks/tiles/10/42/{z}/{x}/{y}/")

            viewModel.initialize(candidate, surveyId)
            viewModel.setViewMode(CandidateViewMode.IMAGE)
            assertEquals(CandidateViewMode.IMAGE, viewModel.uiState.value?.viewMode)

            val updatedCandidate = candidate.copy(claimedByMe = true)
            viewModel.initialize(updatedCandidate, surveyId)

            val state = viewModel.uiState.value
            assertEquals(CandidateViewMode.IMAGE, state?.viewMode)
            assertEquals(true, state?.candidate?.claimedByMe)
        }

    @Test
    fun `calling initialize for different candidate resets viewMode to MAP`() =
        runTest {
            val candidate1 = createCandidate(id = 42L)
            val candidate2 = createCandidate(id = 99L)
            val surveyId = 10L

            whenever(imageRepository.buildCroppedImageRequest(42L, surveyId))
                .thenReturn(mock())
            whenever(imageRepository.getCandidateTileUrlPattern(10L, 42L))
                .thenReturn("https://find.gfo.rocks/tiles/10/42/{z}/{x}/{y}/")
            whenever(imageRepository.buildCroppedImageRequest(99L, surveyId))
                .thenReturn(mock())
            whenever(imageRepository.getCandidateTileUrlPattern(10L, 99L))
                .thenReturn("https://find.gfo.rocks/tiles/10/99/{z}/{x}/{y}/")

            viewModel.initialize(candidate1, surveyId)
            viewModel.setViewMode(CandidateViewMode.IMAGE)
            assertEquals(CandidateViewMode.IMAGE, viewModel.uiState.value?.viewMode)

            viewModel.initialize(candidate2, surveyId)
            assertEquals(CandidateViewMode.MAP, viewModel.uiState.value?.viewMode)
            assertEquals(candidate2, viewModel.uiState.value?.candidate)
        }

    @Test
    fun `setViewMode when uninitialized does not throw and remains null`() =
        runTest {
            viewModel.setViewMode(CandidateViewMode.IMAGE)
            assertNull(viewModel.uiState.value)
        }
}
