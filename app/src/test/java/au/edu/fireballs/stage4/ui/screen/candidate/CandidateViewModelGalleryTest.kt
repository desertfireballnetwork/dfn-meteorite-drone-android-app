package au.edu.fireballs.stage4.ui.screen.candidate

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import au.edu.fireballs.stage4.data.local.Stage4Database
import au.edu.fireballs.stage4.data.remote.EvidenceService
import au.edu.fireballs.stage4.data.remote.dto.EvidenceListResponseDto
import au.edu.fireballs.stage4.data.remote.dto.EvidencePhotoDto
import au.edu.fireballs.stage4.data.repository.CandidateImageRepository
import au.edu.fireballs.stage4.data.repository.DecisionRepository
import au.edu.fireballs.stage4.data.repository.EvidencePhotoRepository
import au.edu.fireballs.stage4.domain.model.BoundingBox
import au.edu.fireballs.stage4.domain.model.ImageDims
import au.edu.fireballs.stage4.domain.model.Stage4Candidate
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import retrofit2.Response
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CandidateViewModelGalleryTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var db: Stage4Database
    private lateinit var decisionRepository: DecisionRepository
    private lateinit var evidencePhotoRepository: EvidencePhotoRepository
    private val imageRepository: CandidateImageRepository = mock()
    private val evidenceService: EvidenceService = mock()
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
        evidencePhotoRepository =
            EvidencePhotoRepository(
                ApplicationProvider.getApplicationContext(),
                db.pendingPhotoUploadDao(),
                evidenceService,
                UnconfinedTestDispatcher(),
            )
        viewModel =
            CandidateViewModel(
                imageRepository,
                decisionRepository,
                evidencePhotoRepository,
            )
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun createCandidate(id: Long = 42L): Stage4Candidate =
        Stage4Candidate(
            inferenceResultId = id,
            imageId = id,
            imageFilename = "drone_image_001.jpg",
            imageDims = ImageDims(w = 3000, h = 3000),
            geoCentroid = null,
            geoArea = null,
            box = BoundingBox(x = 100, y = 100, w = 50, h = 50),
            confidence = 0.95,
            sizeM = null,
        )

    private fun stubImageRepository(
        candidateId: Long,
        surveyId: Long,
    ) {
        whenever(imageRepository.buildCroppedImageRequest(candidateId, surveyId))
            .thenReturn(mock<ImageRequest>())
        whenever(imageRepository.getCandidateTileUrlPattern(surveyId, candidateId))
            .thenReturn("https://find.gfo.rocks/tiles/$surveyId/$candidateId/{z}/{x}/{y}/")
    }

    private fun photo(id: Long): EvidencePhotoDto =
        EvidencePhotoDto(
            id = id,
            capturedAt = "2026-08-27T09:00:00Z",
            created = "2026-08-27T09:01:00Z",
            userId = 1L,
            username = "operator",
        )

    private suspend fun stubSuccess(photos: List<EvidencePhotoDto>) {
        whenever(evidenceService.listEvidencePhotos(any(), any()))
            .thenReturn(Response.success(EvidenceListResponseDto(photos)))
    }

    private suspend fun stubAuthExpired() {
        whenever(evidenceService.listEvidencePhotos(any(), any()))
            .thenReturn(Response.error(401, "".toResponseBody()))
    }

    @Test
    fun `initialize fetches server evidence and exposes content`() =
        runTest {
            val candidate = createCandidate(id = 42L)
            val surveyId = 10L
            stubImageRepository(42L, surveyId)
            stubSuccess(listOf(photo(1L), photo(2L)))

            viewModel.initialize(candidate, surveyId)
            advanceUntilIdle()

            val state = viewModel.serverGalleryState.value
            assertTrue(state is EvidenceGalleryUiState.Content)
            assertEquals(2, (state as EvidenceGalleryUiState.Content).photos.size)
            verify(evidenceService, times(1)).listEvidencePhotos(any(), any())
        }

    @Test
    fun `re-initializing same candidate creates a new fetch boundary`() =
        runTest {
            val candidate = createCandidate(id = 42L)
            val surveyId = 10L
            stubImageRepository(42L, surveyId)
            stubSuccess(listOf(photo(1L)))

            viewModel.initialize(candidate, surveyId)
            advanceUntilIdle()
            viewModel.initialize(candidate, surveyId)
            advanceUntilIdle()
            viewModel.initialize(candidate.copy(claimedByMe = true), surveyId)
            advanceUntilIdle()

            verify(evidenceService, times(3)).listEvidencePhotos(any(), any())
        }

    @Test
    fun `retryServerGallery refetches server evidence`() =
        runTest {
            val candidate = createCandidate(id = 42L)
            val surveyId = 10L
            stubImageRepository(42L, surveyId)
            stubSuccess(listOf(photo(1L)))

            viewModel.initialize(candidate, surveyId)
            advanceUntilIdle()
            viewModel.retryServerGallery()
            advanceUntilIdle()

            verify(evidenceService, times(2)).listEvidencePhotos(any(), any())
            assertTrue(viewModel.serverGalleryState.value is EvidenceGalleryUiState.Content)
        }

    @Test
    fun `switching candidate creates a new fetch boundary`() =
        runTest {
            val candidate1 = createCandidate(id = 42L)
            val candidate2 = createCandidate(id = 99L)
            val surveyId = 10L
            stubImageRepository(42L, surveyId)
            stubImageRepository(99L, surveyId)
            stubSuccess(listOf(photo(1L)))

            viewModel.initialize(candidate1, surveyId)
            advanceUntilIdle()
            viewModel.initialize(candidate2, surveyId)
            advanceUntilIdle()

            verify(evidenceService, times(2)).listEvidencePhotos(any(), any())
            assertEquals(
                99L,
                viewModel
                    .uiState
                    .value
                    ?.candidate
                    ?.inferenceResultId,
            )
        }

    @Test
    fun `auth expired result maps to AuthExpired state`() =
        runTest {
            val candidate = createCandidate(id = 42L)
            val surveyId = 10L
            stubImageRepository(42L, surveyId)
            stubAuthExpired()

            viewModel.initialize(candidate, surveyId)
            advanceUntilIdle()

            assertEquals(EvidenceGalleryUiState.AuthExpired, viewModel.serverGalleryState.value)
        }

    @Test
    fun `empty server evidence maps to Empty state`() =
        runTest {
            val candidate = createCandidate(id = 42L)
            val surveyId = 10L
            stubImageRepository(42L, surveyId)
            stubSuccess(emptyList())

            viewModel.initialize(candidate, surveyId)
            advanceUntilIdle()

            assertEquals(EvidenceGalleryUiState.Empty, viewModel.serverGalleryState.value)
        }

    @Test
    fun `network error maps to Offline state`() =
        runTest {
            val candidate = createCandidate(id = 42L)
            val surveyId = 10L
            stubImageRepository(42L, surveyId)
            whenever(evidenceService.listEvidencePhotos(any(), any()))
                .thenAnswer { throw IOException("boom") }

            viewModel.initialize(candidate, surveyId)
            advanceUntilIdle()

            assertEquals(EvidenceGalleryUiState.Offline, viewModel.serverGalleryState.value)
        }
}
