package au.edu.fireballs.stage4.ui.screen.basecamp

import au.edu.fireballs.stage4.data.repository.ClaimRepository
import au.edu.fireballs.stage4.data.repository.ClaimResult
import au.edu.fireballs.stage4.data.repository.SetCarLocationResult
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking

@OptIn(ExperimentalCoroutinesApi::class)
class BasecampViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val stage4Repository: Stage4Repository = mock()
    private val claimRepository: ClaimRepository = mock()
    private val surveyRepository: SurveyRepository = mock()
    private lateinit var viewModel: BasecampViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        wheneverBlocking { claimRepository.claim(any()) }
            .thenReturn(ClaimResult.Claimed(claimed = emptyList(), alreadyClaimed = emptyList()))
        wheneverBlocking { claimRepository.release(any()) }
            .thenReturn(ClaimResult.Released(released = emptyList()))
        wheneverBlocking { claimRepository.listClaims() }
            .thenReturn(ClaimResult.Listed(claims = emptyList()))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

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

    @Test
    fun `openSurvey loads candidates and claims into Loaded state`() =
        runTest(testDispatcher) {
            val fixture = state(candidate(1L))
            whenever(stage4Repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Success(fixture))
            whenever(claimRepository.listClaims())
                .thenReturn(
                    ClaimResult.Listed(
                        claims =
                            listOf(
                                Claim(
                                    inferenceResultId = 1L,
                                    userId = 2L,
                                    username = "jdoe",
                                    fullName = "Jane Doe",
                                    claimedAt = "2026-01-01T00:00:00Z",
                                    isMe = true,
                                ),
                            ),
                    ),
                )

            viewModel = BasecampViewModel(stage4Repository, claimRepository, surveyRepository)
            val collectJob = backgroundScope.launch(testDispatcher) { viewModel.uiState.collect {} }

            viewModel.openSurvey(7L)
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as BasecampUiState.Loaded
            assertEquals(1, loaded.claims.size)
            assertTrue(loaded.claims.single().isMe)
            collectJob.cancel()
        }

    @Test
    fun `onCandidateTap claims a neutral candidate`() =
        runTest(testDispatcher) {
            val fixture = state(candidate(1L, GeoCoordinate(0.0, 0.0)))
            whenever(stage4Repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Success(fixture))

            viewModel = BasecampViewModel(stage4Repository, claimRepository, surveyRepository)
            val collectJob = backgroundScope.launch(testDispatcher) { viewModel.uiState.collect {} }
            viewModel.openSurvey(7L)
            advanceUntilIdle()

            viewModel.onCandidateTap(1L)
            advanceUntilIdle()

            verify(claimRepository).claim(listOf(1L))
            collectJob.cancel()
        }

    @Test
    fun `onCandidateTap releases an own candidate`() =
        runTest(testDispatcher) {
            val fixture = state(candidate(1L, GeoCoordinate(0.0, 0.0), claimedByMe = true))
            whenever(stage4Repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Success(fixture))

            viewModel = BasecampViewModel(stage4Repository, claimRepository, surveyRepository)
            val collectJob = backgroundScope.launch(testDispatcher) { viewModel.uiState.collect {} }
            viewModel.openSurvey(7L)
            advanceUntilIdle()

            viewModel.onCandidateTap(1L)
            advanceUntilIdle()

            verify(claimRepository).release(listOf(1L))
            collectJob.cancel()
        }

    @Test
    fun `onCandidateTap on other-claimed candidate emits message and does not claim`() =
        runTest(testDispatcher) {
            val fixture = state(candidate(1L, GeoCoordinate(0.0, 0.0), claimedByOther = true))
            whenever(stage4Repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Success(fixture))

            viewModel = BasecampViewModel(stage4Repository, claimRepository, surveyRepository)
            val collectJob = backgroundScope.launch(testDispatcher) { viewModel.uiState.collect {} }
            viewModel.openSurvey(7L)
            advanceUntilIdle()

            viewModel.onCandidateTap(1L)
            advanceUntilIdle()

            verify(claimRepository, never())
                .claim(any())
            verify(claimRepository, never())
                .release(any())
            collectJob.cancel()
        }

    @Test
    fun `commitPolygon batch claims unclaimed candidates inside polygon`() =
        runTest(testDispatcher) {
            val fixture =
                state(
                    candidate(1L, GeoCoordinate(0.0, 0.0)),
                    candidate(2L, GeoCoordinate(10.0, 10.0)),
                    candidate(3L, GeoCoordinate(100.0, 100.0)),
                )
            whenever(stage4Repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Success(fixture))

            viewModel = BasecampViewModel(stage4Repository, claimRepository, surveyRepository)
            val collectJob = backgroundScope.launch(testDispatcher) { viewModel.uiState.collect {} }
            viewModel.openSurvey(7L)
            advanceUntilIdle()

            viewModel.startPolygon()
            viewModel.onPolygonVertex(GeoCoordinate(-5.0, -5.0))
            viewModel.onPolygonVertex(GeoCoordinate(-5.0, 15.0))
            viewModel.onPolygonVertex(GeoCoordinate(15.0, 15.0))
            viewModel.onPolygonVertex(GeoCoordinate(15.0, -5.0))
            viewModel.commitPolygon()
            advanceUntilIdle()

            verify(claimRepository).claim(listOf(1L, 2L))
            collectJob.cancel()
        }

    @Test
    fun `commitPolygon ignores already-claimed candidates inside polygon`() =
        runTest(testDispatcher) {
            val fixture =
                state(
                    candidate(1L, GeoCoordinate(0.0, 0.0)),
                    candidate(2L, GeoCoordinate(10.0, 10.0), claimedByMe = true),
                )
            whenever(stage4Repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Success(fixture))

            viewModel = BasecampViewModel(stage4Repository, claimRepository, surveyRepository)
            val collectJob = backgroundScope.launch(testDispatcher) { viewModel.uiState.collect {} }
            viewModel.openSurvey(7L)
            advanceUntilIdle()

            viewModel.startPolygon()
            viewModel.onPolygonVertex(GeoCoordinate(-5.0, -5.0))
            viewModel.onPolygonVertex(GeoCoordinate(-5.0, 15.0))
            viewModel.onPolygonVertex(GeoCoordinate(15.0, 15.0))
            viewModel.onPolygonVertex(GeoCoordinate(15.0, -5.0))
            viewModel.commitPolygon()
            advanceUntilIdle()

            verify(claimRepository).claim(listOf(1L))
            collectJob.cancel()
        }

    @Test
    fun `commitPolygon with fewer than three vertices does not claim`() =
        runTest(testDispatcher) {
            val fixture = state(candidate(1L, GeoCoordinate(0.0, 0.0)))
            whenever(stage4Repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Success(fixture))

            viewModel = BasecampViewModel(stage4Repository, claimRepository, surveyRepository)
            val collectJob = backgroundScope.launch(testDispatcher) { viewModel.uiState.collect {} }
            viewModel.openSurvey(7L)
            advanceUntilIdle()

            viewModel.startPolygon()
            viewModel.onPolygonVertex(GeoCoordinate(0.0, 0.0))
            viewModel.onPolygonVertex(GeoCoordinate(1.0, 1.0))
            viewModel.commitPolygon()
            advanceUntilIdle()

            verify(claimRepository, never())
                .claim(any())
            collectJob.cancel()
        }

    @Test
    fun `setMineFilter filters claims to mine`() =
        runTest(testDispatcher) {
            val fixture = state()
            whenever(stage4Repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Success(fixture))
            whenever(claimRepository.listClaims())
                .thenReturn(
                    ClaimResult.Listed(
                        claims =
                            listOf(
                                Claim(inferenceResultId = 1L, userId = 2L, isMe = true),
                                Claim(inferenceResultId = 2L, userId = 3L, isMe = false),
                            ),
                    ),
                )

            viewModel = BasecampViewModel(stage4Repository, claimRepository, surveyRepository)
            val collectJob = backgroundScope.launch(testDispatcher) { viewModel.uiState.collect {} }
            viewModel.openSurvey(7L)
            advanceUntilIdle()

            viewModel.setMineFilter(true)
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as BasecampUiState.Loaded
            assertTrue(loaded.mineOnly)
            assertEquals(listOf(1L), loaded.claims.map { it.inferenceResultId })
            collectJob.cancel()
        }

    @Test
    fun `refresh reloads candidates and claims`() =
        runTest(testDispatcher) {
            val fixture = state(candidate(1L))
            whenever(stage4Repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Success(fixture))
            whenever(claimRepository.listClaims())
                .thenReturn(
                    ClaimResult.Listed(
                        claims = listOf(Claim(inferenceResultId = 1L, userId = 2L, isMe = true)),
                    ),
                )

            viewModel = BasecampViewModel(stage4Repository, claimRepository, surveyRepository)
            val collectJob = backgroundScope.launch(testDispatcher) { viewModel.uiState.collect {} }
            viewModel.openSurvey(7L)
            advanceUntilIdle()

            viewModel.refresh()
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as BasecampUiState.Loaded
            assertEquals(1, loaded.claims.size)
            collectJob.cancel()
        }

    @Test
    fun `releaseClaim self-releases the given candidate`() =
        runTest(testDispatcher) {
            val fixture = state(candidate(1L, GeoCoordinate(0.0, 0.0)))
            whenever(stage4Repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Success(fixture))
            wheneverBlocking { claimRepository.listClaims() }
                .thenReturn(
                    ClaimResult.Listed(
                        claims = listOf(Claim(inferenceResultId = 1L, userId = 2L, isMe = true)),
                    ),
                )
            wheneverBlocking { claimRepository.release(listOf(1L)) }
                .thenReturn(ClaimResult.Released(released = listOf(1L)))

            viewModel = BasecampViewModel(stage4Repository, claimRepository, surveyRepository)
            val collectJob = backgroundScope.launch(testDispatcher) { viewModel.uiState.collect {} }
            viewModel.openSurvey(7L)
            advanceUntilIdle()

            viewModel.releaseClaim(1L)
            advanceUntilIdle()

            verify(claimRepository).release(listOf(1L))
            collectJob.cancel()
        }

    @Test
    fun `releaseClaim refuses to release another users claim`() =
        runTest(testDispatcher) {
            val fixture = state(candidate(1L, GeoCoordinate(0.0, 0.0)))
            whenever(stage4Repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Success(fixture))
            wheneverBlocking { claimRepository.listClaims() }
                .thenReturn(
                    ClaimResult.Listed(
                        claims = listOf(Claim(inferenceResultId = 1L, userId = 3L, isMe = false)),
                    ),
                )

            viewModel = BasecampViewModel(stage4Repository, claimRepository, surveyRepository)
            val collectJob = backgroundScope.launch(testDispatcher) { viewModel.uiState.collect {} }
            viewModel.openSurvey(7L)
            advanceUntilIdle()

            viewModel.releaseClaim(1L)
            advanceUntilIdle()

            verify(claimRepository, never()).release(any())
            collectJob.cancel()
        }

    @Test
    fun `release clears ownership flags on the map`() =
        runTest(testDispatcher) {
            val fixture = state(candidate(1L, GeoCoordinate(0.0, 0.0)))
            whenever(stage4Repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Success(fixture))
            var claimsToReturn: List<Claim> =
                listOf(Claim(inferenceResultId = 1L, userId = 2L, isMe = true))
            wheneverBlocking { claimRepository.listClaims() }
                .thenAnswer { ClaimResult.Listed(claims = claimsToReturn) }
            wheneverBlocking { claimRepository.release(listOf(1L)) }
                .thenReturn(ClaimResult.Released(released = listOf(1L)))

            viewModel = BasecampViewModel(stage4Repository, claimRepository, surveyRepository)
            val collectJob = backgroundScope.launch(testDispatcher) { viewModel.uiState.collect {} }
            viewModel.openSurvey(7L)
            advanceUntilIdle()

            val claimed = viewModel.uiState.value as BasecampUiState.Loaded
            val claimedCandidate = claimed.state.yesMeteorites.single()
            assertTrue(claimedCandidate.claimedByMe)

            claimsToReturn = emptyList()
            viewModel.releaseClaim(1L)
            advanceUntilIdle()

            val released = viewModel.uiState.value as BasecampUiState.Loaded
            val releasedCandidate = released.state.yesMeteorites.single()
            assertTrue(!releasedCandidate.claimedByMe)
            assertTrue(!releasedCandidate.claimedByOther)
            collectJob.cancel()
        }

    @Test
    fun `setCarLocation updates base and emits CarLocationSet on success`() =
        runTest(testDispatcher) {
            val fixture = state(candidate(1L))
            whenever(stage4Repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Success(fixture))
            wheneverBlocking { surveyRepository.setCarLocation(7L, -25.0, 134.0) }
                .thenReturn(SetCarLocationResult.Success)

            viewModel = BasecampViewModel(stage4Repository, claimRepository, surveyRepository)
            val collectJob = backgroundScope.launch(testDispatcher) { viewModel.uiState.collect {} }
            val events = mutableListOf<BasecampEvent>()
            val eventJob =
                backgroundScope.launch(testDispatcher) {
                    viewModel.events.collect { events.add(it) }
                }
            viewModel.openSurvey(7L)
            advanceUntilIdle()

            viewModel.setCarLocation(-25.0, 134.0)
            advanceUntilIdle()
            runCurrent()
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as BasecampUiState.Loaded
            assertEquals(GeoCoordinate(-25.0, 134.0), loaded.state.base)
            assertTrue(events.any { it is BasecampEvent.CarLocationSet })
            collectJob.cancel()
            eventJob.cancel()
        }

    @Test
    fun `setCarLocation emits error message and leaves base unchanged`() =
        runTest(testDispatcher) {
            val fixture = state(candidate(1L))
            whenever(stage4Repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Success(fixture))
            wheneverBlocking { surveyRepository.setCarLocation(7L, -25.0, 134.0) }
                .thenReturn(SetCarLocationResult.Error("Invalid coordinates"))

            viewModel = BasecampViewModel(stage4Repository, claimRepository, surveyRepository)
            val collectJob = backgroundScope.launch(testDispatcher) { viewModel.uiState.collect {} }
            val events = mutableListOf<BasecampEvent>()
            val eventJob =
                backgroundScope.launch(testDispatcher) {
                    viewModel.events.collect { events.add(it) }
                }
            viewModel.openSurvey(7L)
            advanceUntilIdle()

            viewModel.setCarLocation(-25.0, 134.0)
            advanceUntilIdle()
            runCurrent()
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as BasecampUiState.Loaded
            assertEquals(null, loaded.state.base)
            assertTrue(
                events.any {
                    it is BasecampEvent.ShowMessage && it.message == "Invalid coordinates"
                },
            )
            assertTrue(events.none { it is BasecampEvent.CarLocationSet })
            collectJob.cancel()
            eventJob.cancel()
        }

    @Test
    fun `setCarLocation emits network error and no CarLocationSet`() =
        runTest(testDispatcher) {
            val fixture = state(candidate(1L))
            whenever(stage4Repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Success(fixture))
            wheneverBlocking { surveyRepository.setCarLocation(7L, -25.0, 134.0) }
                .thenReturn(SetCarLocationResult.NetworkError)

            viewModel = BasecampViewModel(stage4Repository, claimRepository, surveyRepository)
            val collectJob = backgroundScope.launch(testDispatcher) { viewModel.uiState.collect {} }
            val events = mutableListOf<BasecampEvent>()
            val eventJob =
                backgroundScope.launch(testDispatcher) {
                    viewModel.events.collect { events.add(it) }
                }
            viewModel.openSurvey(7L)
            advanceUntilIdle()

            viewModel.setCarLocation(-25.0, 134.0)
            advanceUntilIdle()
            runCurrent()
            advanceUntilIdle()

            assertTrue(
                events.any {
                    it is BasecampEvent.ShowMessage &&
                        it.message == "Network error setting car location"
                },
            )
            assertTrue(events.none { it is BasecampEvent.CarLocationSet })
            collectJob.cancel()
            eventJob.cancel()
        }

    @Test
    fun `setCarLocation results in AuthExpired state`() =
        runTest(testDispatcher) {
            val fixture = state(candidate(1L))
            whenever(stage4Repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Success(fixture))
            wheneverBlocking { surveyRepository.setCarLocation(7L, -25.0, 134.0) }
                .thenReturn(SetCarLocationResult.AuthExpired)

            viewModel = BasecampViewModel(stage4Repository, claimRepository, surveyRepository)
            val collectJob = backgroundScope.launch(testDispatcher) { viewModel.uiState.collect {} }
            viewModel.openSurvey(7L)
            advanceUntilIdle()

            viewModel.setCarLocation(-25.0, 134.0)
            advanceUntilIdle()

            assertEquals(BasecampUiState.AuthExpired, viewModel.uiState.value)
            collectJob.cancel()
        }
}
