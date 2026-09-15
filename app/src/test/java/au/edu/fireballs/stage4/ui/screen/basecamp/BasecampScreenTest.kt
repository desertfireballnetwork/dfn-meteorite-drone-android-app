package au.edu.fireballs.stage4.ui.screen.basecamp

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import au.edu.fireballs.stage4.data.repository.ClaimRepository
import au.edu.fireballs.stage4.data.repository.ClaimResult
import au.edu.fireballs.stage4.data.repository.SelectedSurveyRepository
import au.edu.fireballs.stage4.data.repository.Stage4FetchResult
import au.edu.fireballs.stage4.data.repository.Stage4Repository
import au.edu.fireballs.stage4.data.repository.SurveyRepository
import au.edu.fireballs.stage4.domain.model.BoundingBox
import au.edu.fireballs.stage4.domain.model.Claim
import au.edu.fireballs.stage4.domain.model.GeoCoordinate
import au.edu.fireballs.stage4.domain.model.ImageDims
import au.edu.fireballs.stage4.domain.model.Stage4Candidate
import au.edu.fireballs.stage4.domain.model.Stage4State
import au.edu.fireballs.stage4.domain.model.Stage4Survey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BasecampScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val stage4Repository: Stage4Repository = mock()
    private val claimRepository: ClaimRepository = mock()
    private val surveyRepository: SurveyRepository = mock()
    private val selectedSurveyRepository: SelectedSurveyRepository = mock()
    private lateinit var viewModel: BasecampViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        wheneverBlocking { claimRepository.claim(any()) }
            .thenReturn(ClaimResult.Claimed(claimed = emptyList(), alreadyClaimed = emptyList()))
        wheneverBlocking { claimRepository.release(any()) }
            .thenReturn(ClaimResult.Released(released = emptyList()))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun state(vararg candidates: Stage4Candidate): Stage4State =
        Stage4State(
            survey = Stage4Survey(id = 7L, eventId = "DN240703-02", tilesetId = null),
            base = null,
            surveyedAreas = emptyList(),
            unprocessedCandidates = emptyList(),
            yesMeteorites = candidates.toList(),
            noMeteorites = emptyList(),
            detectionTags = emptyList(),
            userLocations = emptyList(),
            showGeolocationAccuracyCircle = false,
            latestTaskCreated = "2026-08-26T00:00:00Z",
        )

    private fun candidate(
        id: Long,
        centroid: GeoCoordinate? = null,
        claimedByMe: Boolean = false,
        claimedByOther: Boolean = false,
    ): Stage4Candidate =
        Stage4Candidate(
            inferenceResultId = id,
            imageId = id,
            imageFilename = "frame_$id.png",
            imageDims = ImageDims(w = 1920, h = 1080),
            geoCentroid = centroid,
            geoArea = null,
            box = BoundingBox(x = 0, y = 0, w = 10, h = 10),
            confidence = 0.9,
            sizeM = null,
            claimedByMe = claimedByMe,
            claimedByOther = claimedByOther,
        )

    private fun claim(
        id: Long,
        isMe: Boolean,
        fullName: String? = null,
    ): Claim =
        Claim(
            inferenceResultId = id,
            userId = if (isMe) 2L else 3L,
            username = if (isMe) "me" else "other",
            fullName = fullName,
            claimedAt = "2026-01-01T00:00:00Z",
            isMe = isMe,
        )

    private fun openLoaded(
        fixture: Stage4State,
        claims: List<Claim>,
        offline: Boolean = false,
    ) {
        wheneverBlocking { stage4Repository.getCandidatesState(7L) }
            .thenReturn(Stage4FetchResult.Success(fixture, isOffline = offline))
        wheneverBlocking { claimRepository.listClaims() }
            .thenReturn(ClaimResult.Listed(claims = claims))
        viewModel =
            BasecampViewModel(
                stage4Repository,
                claimRepository,
                surveyRepository,
                selectedSurveyRepository,
            )
        val preDownloadViewModel: PreDownloadViewModel = mock()
        whenever(preDownloadViewModel.uiState)
            .thenReturn(MutableStateFlow(PreDownloadUiState.Idle))
        kotlinx.coroutines.CoroutineScope(testDispatcher).launch {
            viewModel.uiState.collect {}
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                BasecampScreen(
                    surveyId = 7L,
                    onAuthExpired = {},
                    onNavigateToSettings = {},
                    viewModel = viewModel,
                    preDownloadViewModel = preDownloadViewModel,
                )
            }
        }
        composeRule.waitForIdle()
        viewModel.openSurvey(7L)
        composeRule.waitForIdle()
        val loaded = viewModel.uiState.value as? BasecampUiState.Loaded
        if (loaded == null) {
            throw AssertionError("Expected Loaded but got ${viewModel.uiState.value}")
        }
    }

    @Test
    fun releaseFlow_myClaimShowsReleaseButtonAndReleases() {
        openLoaded(
            fixture = state(candidate(1L, GeoCoordinate(0.0, 0.0))),
            claims = listOf(claim(1L, isMe = true, fullName = "Jane Doe")),
        )

        composeRule.onNodeWithText("Candidate #1").assertExists()
        composeRule.onNodeWithText("Jane Doe").assertExists()
        composeRule.onNodeWithText("Release").performClick()
        composeRule.waitForIdle()

        verifyBlocking(claimRepository) { release(listOf(1L)) }
    }

    @Test
    fun conflictFlow_otherUsersClaimShowsNoReleaseButton() {
        openLoaded(
            fixture = state(candidate(1L, GeoCoordinate(0.0, 0.0))),
            claims = listOf(claim(1L, isMe = false, fullName = "Other User")),
        )

        composeRule.onNodeWithText("Candidate #1").assertExists()
        composeRule.onNodeWithText("Other User").assertExists()
        composeRule.onNodeWithText("Release").assertDoesNotExist()

        verifyBlocking(claimRepository, never()) { release(any()) }
    }

    @Test
    fun emptyClaims_showsNoClaims() {
        openLoaded(
            fixture = state(candidate(1L, GeoCoordinate(0.0, 0.0))),
            claims = emptyList(),
        )

        composeRule.onNodeWithText("No claims").assertExists()
    }

    @Test
    fun claimFlow_unclaimedCandidateClaimsViaTap() {
        openLoaded(
            fixture = state(candidate(1L, GeoCoordinate(0.0, 0.0))),
            claims = emptyList(),
        )

        viewModel.onCandidateTap(1L)
        composeRule.waitForIdle()

        verifyBlocking(claimRepository) { claim(listOf(1L)) }
    }

    @Test
    fun offlineStatus_showsOfflineMessageWhenCachedDataReturned() {
        openLoaded(
            fixture = state(candidate(1L, GeoCoordinate(0.0, 0.0))),
            claims = emptyList(),
            offline = true,
        )

        composeRule.onNodeWithText("Offline — displaying previous map data").assertExists()
    }
}
