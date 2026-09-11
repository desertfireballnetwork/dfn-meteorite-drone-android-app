package au.edu.fireballs.stage4.ui.screen.candidate

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import au.edu.fireballs.stage4.data.local.Stage4Database
import au.edu.fireballs.stage4.data.repository.CandidateImageRepository
import au.edu.fireballs.stage4.data.repository.DecisionRepository
import au.edu.fireballs.stage4.domain.model.BoundingBox
import au.edu.fireballs.stage4.domain.model.ImageDims
import au.edu.fireballs.stage4.domain.model.Stage4Candidate
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CandidateViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var db: Stage4Database
    private lateinit var decisionRepository: DecisionRepository
    private val imageRepository: CandidateImageRepository = mock()
    private lateinit var viewModel: CandidateViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        db =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    Stage4Database::class.java,
                ).allowMainThreadQueries()
                .build()
        decisionRepository = DecisionRepository(db.localDecisionDao(), UnconfinedTestDispatcher())
        viewModel = CandidateViewModel(imageRepository, decisionRepository)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
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

    private fun stubImageRepository(
        candidateId: Long = 42L,
        surveyId: Long = 10L,
    ) {
        whenever(imageRepository.buildCroppedImageRequest(candidateId, surveyId))
            .thenReturn(mock())
        whenever(imageRepository.getCandidateTileUrlPattern(surveyId, candidateId))
            .thenReturn("https://find.gfo.rocks/tiles/$surveyId/$candidateId/{z}/{x}/{y}/")
    }

    private fun TestScope.awaitVerdict(expected: Boolean?) {
        var waited = 0
        while (viewModel.verdict.value != expected && waited < 500) {
            Thread.sleep(10)
            advanceUntilIdle()
            waited++
        }
    }

    @Test
    fun `initial uiState is null`() =
        runTest(testDispatcher) {
            assertNull(viewModel.uiState.value)
        }

    @Test
    fun `initialization populates candidate and media model`() =
        runTest(testDispatcher) {
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
        runTest(testDispatcher) {
            val candidate = createCandidate(id = 42L)
            val surveyId = 10L
            stubImageRepository()

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
        runTest(testDispatcher) {
            val candidate = createCandidate(id = 42L)
            val surveyId = 10L
            stubImageRepository()

            viewModel.initialize(candidate, surveyId)
            viewModel.setViewMode(CandidateViewMode.IMAGE)
            assertEquals(CandidateViewMode.IMAGE, viewModel.uiState.value?.viewMode)

            val updatedCandidate = candidate.copy(claimedByMe = true)
            viewModel.initialize(updatedCandidate, surveyId)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(CandidateViewMode.IMAGE, state?.viewMode)
            assertEquals(true, state?.candidate?.claimedByMe)
        }

    @Test
    fun `calling initialize for different candidate resets viewMode to MAP`() =
        runTest(testDispatcher) {
            val candidate1 = createCandidate(id = 42L)
            val candidate2 = createCandidate(id = 99L)
            val surveyId = 10L

            stubImageRepository()
            whenever(imageRepository.buildCroppedImageRequest(99L, surveyId))
                .thenReturn(mock())
            whenever(imageRepository.getCandidateTileUrlPattern(10L, 99L))
                .thenReturn("https://find.gfo.rocks/tiles/10/99/{z}/{x}/{y}/")

            viewModel.initialize(candidate1, surveyId)
            viewModel.setViewMode(CandidateViewMode.IMAGE)
            assertEquals(CandidateViewMode.IMAGE, viewModel.uiState.value?.viewMode)

            viewModel.initialize(candidate2, surveyId)
            advanceUntilIdle()
            assertEquals(CandidateViewMode.MAP, viewModel.uiState.value?.viewMode)
            assertEquals(candidate2, viewModel.uiState.value?.candidate)
        }

    @Test
    fun `setViewMode when uninitialized does not throw and remains null`() =
        runTest(testDispatcher) {
            viewModel.setViewMode(CandidateViewMode.IMAGE)
            assertNull(viewModel.uiState.value)
        }

    @Test
    fun `retryImage rebuilds cropped image model with a new retry key`() =
        runTest(testDispatcher) {
            val candidate = createCandidate(id = 42L)
            val surveyId = 10L
            val initialRequest: ImageRequest = mock()
            val retriedRequest: ImageRequest = mock()
            whenever(imageRepository.buildCroppedImageRequest(42L, surveyId, 0))
                .thenReturn(initialRequest)
            whenever(imageRepository.buildCroppedImageRequest(42L, surveyId, 1))
                .thenReturn(retriedRequest)
            whenever(imageRepository.getCandidateTileUrlPattern(10L, 42L))
                .thenReturn("https://find.gfo.rocks/tiles/10/42/{z}/{x}/{y}/")

            viewModel.initialize(candidate, surveyId)
            assertEquals(initialRequest, viewModel.uiState.value?.croppedImageModel)

            viewModel.retryImage()
            assertEquals(retriedRequest, viewModel.uiState.value?.croppedImageModel)
        }

    @Test
    fun `tapping Yes accepts verdict and emits it`() =
        runTest(testDispatcher) {
            val candidate = createCandidate(id = 42L)
            stubImageRepository()
            viewModel.initialize(candidate, 10L)

            viewModel.submitVerdict(true, null)
            advanceUntilIdle()

            assertEquals(true, viewModel.verdict.value)
            assertNull(viewModel.detectionTagId.value)
        }

    @Test
    fun `tapping No offers tag picker`() =
        runTest(testDispatcher) {
            val candidate = createCandidate(id = 42L)
            stubImageRepository()
            viewModel.initialize(candidate, 10L)

            viewModel.submitVerdict(false, 5L)
            advanceUntilIdle()

            assertEquals(false, viewModel.verdict.value)
            assertEquals(5L, viewModel.detectionTagId.value)
        }

    @Test
    fun `switching from No to Yes clears tag`() =
        runTest(testDispatcher) {
            val candidate = createCandidate(id = 42L)
            stubImageRepository()
            viewModel.initialize(candidate, 10L)

            viewModel.submitVerdict(true, null)
            advanceUntilIdle()
            viewModel.submitVerdict(false, 5L)
            advanceUntilIdle()
            viewModel.submitVerdict(true, null)
            advanceUntilIdle()

            assertEquals(true, viewModel.verdict.value)
            assertNull(viewModel.detectionTagId.value)
        }

    @Test
    fun `submitVerdict persists a row with synced false`() =
        runTest(testDispatcher) {
            val candidate = createCandidate(id = 42L)
            stubImageRepository()
            viewModel.initialize(candidate, 10L)

            viewModel.submitVerdict(true, null)
            advanceUntilIdle()

            val row = db.localDecisionDao().getVerdict(42L).first()
            assertEquals(42L, row?.inferenceResultId)
            assertEquals(10L, row?.surveyId)
            assertEquals(true, row?.verdict)
            assertFalse(row?.synced ?: true)
        }

    @Test
    fun `initialize pre-selects an existing verdict and tag from Room`() =
        runTest(testDispatcher) {
            val candidate = createCandidate(id = 42L)
            stubImageRepository()
            val decisionDao = db.localDecisionDao()
            decisionDao.saveDecision(
                au.edu.fireballs.stage4.data.local.LocalDecisionEntity(
                    inferenceResultId = 42L,
                    surveyId = 10L,
                    verdict = false,
                    detectionTagId = 9L,
                    capturedAt = "2026-09-11T00:00:00Z",
                    evidencePhotoRowId = null,
                    synced = false,
                ),
            )

            viewModel.initialize(candidate, 10L)
            awaitVerdict(false)

            assertEquals(false, viewModel.verdict.value)
            assertEquals(9L, viewModel.detectionTagId.value)
        }

    @Test
    fun `clearVerdict clears in-modal selection without touching Room`() =
        runTest(testDispatcher) {
            val candidate = createCandidate(id = 42L)
            stubImageRepository()
            viewModel.initialize(candidate, 10L)
            viewModel.submitVerdict(true, null)
            advanceUntilIdle()

            viewModel.clearVerdict()

            assertNull(viewModel.verdict.value)
            assertNull(viewModel.detectionTagId.value)
            val row = db.localDecisionDao().getVerdict(42L).first()
            assertEquals(true, row?.verdict)
        }
}
