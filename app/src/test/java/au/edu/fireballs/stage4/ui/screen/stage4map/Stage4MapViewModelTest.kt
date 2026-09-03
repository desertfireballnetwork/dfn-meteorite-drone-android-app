package au.edu.fireballs.stage4.ui.screen.stage4map

import au.edu.fireballs.stage4.data.local.LocalDecisionEntity
import au.edu.fireballs.stage4.data.local.dao.LocalDecisionDao
import au.edu.fireballs.stage4.data.repository.Stage4FetchResult
import au.edu.fireballs.stage4.data.repository.Stage4Repository
import au.edu.fireballs.stage4.domain.model.BoundingBox
import au.edu.fireballs.stage4.domain.model.GeoCoordinate
import au.edu.fireballs.stage4.domain.model.ImageDims
import au.edu.fireballs.stage4.domain.model.MapCameraTarget
import au.edu.fireballs.stage4.domain.model.Stage4Candidate
import au.edu.fireballs.stage4.domain.model.Stage4State
import au.edu.fireballs.stage4.domain.model.Stage4Survey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class Stage4MapViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val repository: Stage4Repository = mock()
    private val localDecisionDao: LocalDecisionDao = mock()
    private val pendingDecisionsFlow = MutableStateFlow<List<LocalDecisionEntity>>(emptyList())
    private lateinit var viewModel: Stage4MapViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        pendingDecisionsFlow.value = emptyList()
        whenever(localDecisionDao.observeDecisionsForSurvey(any()))
            .thenReturn(pendingDecisionsFlow)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun dummyState(
        surveyId: Long = 7L,
        base: GeoCoordinate? = GeoCoordinate(-37.8, 145.0),
    ): Stage4State =
        Stage4State(
            survey = Stage4Survey(id = surveyId, eventId = "DN240703-02", tilesetId = null),
            base = base,
            surveyedAreas = emptyList(),
            unprocessedCandidates = emptyList(),
            yesMeteorites = emptyList(),
            noMeteorites = emptyList(),
            detectionTags = emptyList(),
            userLocations = emptyList(),
            showGeolocationAccuracyCircle = false,
            latestTaskCreated = "2026-08-26T00:00:00Z",
        )

    @Test
    fun `initial state is Loading`() =
        runTest(testDispatcher) {
            viewModel = Stage4MapViewModel(repository, localDecisionDao)

            assertEquals(Stage4MapUiState.Loading, viewModel.uiState.value)
        }

    @Test
    fun `openSurvey success updates state to Loaded with camera target at base zoom 13`() =
        runTest(testDispatcher) {
            val base = GeoCoordinate(latitude = -29.467, longitude = 115.342)
            val fixture = dummyState(7L, base)
            whenever(repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Success(fixture))

            viewModel = Stage4MapViewModel(repository, localDecisionDao)
            val collectJob =
                backgroundScope.launch(testDispatcher) {
                    viewModel.uiState.collect {}
                }

            viewModel.openSurvey(7L)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is Stage4MapUiState.Loaded)
            val loaded = state as Stage4MapUiState.Loaded
            assertEquals(fixture, loaded.state)
            assertEquals(
                MapCameraTarget(latitude = -29.467, longitude = 115.342, zoom = 13.0),
                loaded.cameraTarget,
            )

            collectJob.cancel()
        }

    @Test
    fun `openSurvey success with null base and empty areas yields null camera target`() =
        runTest(testDispatcher) {
            val fixture = dummyState(7L, base = null)
            whenever(repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Success(fixture))

            viewModel = Stage4MapViewModel(repository, localDecisionDao)
            val collectJob =
                backgroundScope.launch(testDispatcher) {
                    viewModel.uiState.collect {}
                }

            viewModel.openSurvey(7L)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is Stage4MapUiState.Loaded)
            assertNull((state as Stage4MapUiState.Loaded).cameraTarget)

            collectJob.cancel()
        }

    @Test
    fun `openSurvey error updates state to Error with message passthrough`() =
        runTest(testDispatcher) {
            whenever(repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Error("No Stage 4 candidates task is available."))

            viewModel = Stage4MapViewModel(repository, localDecisionDao)
            val collectJob =
                backgroundScope.launch(testDispatcher) {
                    viewModel.uiState.collect {}
                }

            viewModel.openSurvey(7L)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is Stage4MapUiState.Error)
            assertEquals(
                "No Stage 4 candidates task is available.",
                (state as Stage4MapUiState.Error).message,
            )

            collectJob.cancel()
        }

    @Test
    fun `openSurvey network error updates state to Error with generic offline message`() =
        runTest(testDispatcher) {
            whenever(repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.NetworkError)

            viewModel = Stage4MapViewModel(repository, localDecisionDao)
            val collectJob =
                backgroundScope.launch(testDispatcher) {
                    viewModel.uiState.collect {}
                }

            viewModel.openSurvey(7L)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is Stage4MapUiState.Error)
            assertEquals(
                "Network error. Please check your connection.",
                (state as Stage4MapUiState.Error).message,
            )

            collectJob.cancel()
        }

    @Test
    fun `openSurvey auth expired updates state to AuthExpired`() =
        runTest(testDispatcher) {
            whenever(repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.AuthExpired)

            viewModel = Stage4MapViewModel(repository, localDecisionDao)
            val collectJob =
                backgroundScope.launch(testDispatcher) {
                    viewModel.uiState.collect {}
                }

            viewModel.openSurvey(7L)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value is Stage4MapUiState.AuthExpired)

            collectJob.cancel()
        }

    @Test
    fun `openSurvey access denied error updates state to Error not AuthExpired`() =
        runTest(testDispatcher) {
            whenever(repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Error("You don't have access to this survey"))

            viewModel = Stage4MapViewModel(repository, localDecisionDao)
            val collectJob =
                backgroundScope.launch(testDispatcher) {
                    viewModel.uiState.collect {}
                }

            viewModel.openSurvey(7L)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state is Stage4MapUiState.AuthExpired)
            assertTrue(state is Stage4MapUiState.Error)
            assertEquals(
                "You don't have access to this survey",
                (state as Stage4MapUiState.Error).message,
            )

            collectJob.cancel()
        }

    @Test
    fun `retry after failure re-calls repository and emits Loaded on second success`() =
        runTest(testDispatcher) {
            val base = GeoCoordinate(latitude = -29.467, longitude = 115.342)
            val fixture = dummyState(7L, base)
            whenever(repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.NetworkError)

            viewModel = Stage4MapViewModel(repository, localDecisionDao)
            val collectJob =
                backgroundScope.launch(testDispatcher) {
                    viewModel.uiState.collect {}
                }

            viewModel.openSurvey(7L)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value is Stage4MapUiState.Error)

            whenever(repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Success(fixture))

            viewModel.retry()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is Stage4MapUiState.Loaded)
            assertEquals(fixture, (state as Stage4MapUiState.Loaded).state)
            verify(repository, times(2)).getCandidatesState(7L)

            collectJob.cancel()
        }

    @Test
    fun `openSurvey on loaded survey refreshes in place without emitting Loading`() =
        runTest(testDispatcher) {
            val fixture = dummyState(7L, base = null)
            whenever(repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Success(fixture))

            viewModel = Stage4MapViewModel(repository, localDecisionDao)

            val states = mutableListOf<Stage4MapUiState>()
            val collectJob =
                backgroundScope.launch(testDispatcher) {
                    viewModel.uiState.collect { states.add(it) }
                }

            viewModel.openSurvey(7L)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value is Stage4MapUiState.Loaded)

            viewModel.openSurvey(7L)
            advanceUntilIdle()

            assertFalse(states.drop(1).any { it is Stage4MapUiState.Loading })
            val finalState = viewModel.uiState.value
            assertTrue(finalState is Stage4MapUiState.Loaded)
            assertFalse((finalState as Stage4MapUiState.Loaded).isRefreshing)

            collectJob.cancel()
        }

    @Test
    fun `retry with loaded survey keeps Loaded visible and surfaces message on failure`() =
        runTest(testDispatcher) {
            val fixture = dummyState(7L, base = null)
            whenever(repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Success(fixture))

            viewModel = Stage4MapViewModel(repository, localDecisionDao)

            val states = mutableListOf<Stage4MapUiState>()
            val collectJob =
                backgroundScope.launch(testDispatcher) {
                    viewModel.uiState.collect { states.add(it) }
                }

            viewModel.openSurvey(7L)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value is Stage4MapUiState.Loaded)

            whenever(repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.NetworkError)

            viewModel.retry()
            advanceUntilIdle()

            assertFalse(states.drop(1).any { it is Stage4MapUiState.Loading })
            assertFalse(states.any { it is Stage4MapUiState.Error })
            val state = viewModel.uiState.value
            assertTrue(state is Stage4MapUiState.Loaded)
            val loaded = state as Stage4MapUiState.Loaded
            assertEquals(fixture, loaded.state)
            assertFalse(loaded.isRefreshing)
            assertEquals("Offline — displaying previous map data", loaded.userMessage)

            collectJob.cancel()
        }

    @Test
    fun `refresh failure keeps previous loaded content with error message`() =
        runTest(testDispatcher) {
            val fixture = dummyState(7L, base = null)
            whenever(repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Success(fixture))

            viewModel = Stage4MapViewModel(repository, localDecisionDao)
            val collectJob =
                backgroundScope.launch(testDispatcher) {
                    viewModel.uiState.collect {}
                }

            viewModel.openSurvey(7L)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value is Stage4MapUiState.Loaded)

            whenever(repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Error("No Stage 4 candidates task is available."))

            viewModel.openSurvey(7L)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is Stage4MapUiState.Loaded)
            val loaded = state as Stage4MapUiState.Loaded
            assertEquals(fixture, loaded.state)
            assertFalse(loaded.isRefreshing)
            assertEquals("No Stage 4 candidates task is available.", loaded.userMessage)

            collectJob.cancel()
        }

    @Test
    fun `openSurvey with a different survey emits Loading before switching`() =
        runTest(testDispatcher) {
            val fixture7 = dummyState(7L, base = null)
            val fixture8 = dummyState(8L, base = null)
            whenever(repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Success(fixture7))
            whenever(repository.getCandidatesState(8L))
                .thenReturn(Stage4FetchResult.Success(fixture8))

            viewModel = Stage4MapViewModel(repository, localDecisionDao)

            val states = mutableListOf<Stage4MapUiState>()
            val collectJob =
                backgroundScope.launch(testDispatcher) {
                    viewModel.uiState.collect { states.add(it) }
                }

            viewModel.openSurvey(7L)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value is Stage4MapUiState.Loaded)

            viewModel.openSurvey(8L)
            advanceUntilIdle()

            assertTrue(states.any { it is Stage4MapUiState.Loading })
            val finalState = viewModel.uiState.value
            assertTrue(finalState is Stage4MapUiState.Loaded)
            assertEquals(8L, (finalState as Stage4MapUiState.Loaded).state.survey.id)

            collectJob.cancel()
        }

    @Test
    fun `toggleLayer updates layerToggleState`() =
        runTest(testDispatcher) {
            val stateData = dummyState(7L)
            whenever(repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Success(stateData))

            viewModel = Stage4MapViewModel(repository, localDecisionDao)
            val collectJob =
                backgroundScope.launch(testDispatcher) {
                    viewModel.uiState.collect {}
                }

            viewModel.openSurvey(7L)
            advanceUntilIdle()

            viewModel.toggleLayer(LayerType.UNPROCESSED, false)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is Stage4MapUiState.Loaded)
            val loadedState = state as Stage4MapUiState.Loaded
            assertFalse(loadedState.layerToggleState.showUnprocessed)

            collectJob.cancel()
        }

    @Test
    fun `mergeStateWithDecisions applies local verdict and moves candidate`() =
        runTest(testDispatcher) {
            val candidateId = 101L
            val dummyCandidate =
                Stage4Candidate(
                    inferenceResultId = candidateId,
                    imageId = 1001L,
                    imageFilename = "img_101.jpg",
                    imageDims = ImageDims(w = 100, h = 100),
                    geoCentroid = GeoCoordinate(-37.8, 145.0),
                    geoArea = null,
                    box = BoundingBox(x = 0, y = 0, w = 10, h = 10),
                    confidence = 0.95,
                    sizeM = null,
                    claimedByMe = false,
                    claimedByOther = false,
                )
            val baseState =
                dummyState(7L).copy(
                    unprocessedCandidates = listOf(dummyCandidate),
                )

            whenever(repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Success(baseState))

            pendingDecisionsFlow.value =
                listOf(
                    LocalDecisionEntity(
                        inferenceResultId = candidateId,
                        surveyId = 7L,
                        verdict = true,
                        detectionTagId = null,
                        capturedAt = "2026-09-02T12:00:00Z",
                        evidencePhotoRowId = null,
                        synced = false,
                    ),
                )

            viewModel = Stage4MapViewModel(repository, localDecisionDao)
            val collectJob =
                backgroundScope.launch(testDispatcher) {
                    viewModel.uiState.collect {}
                }

            viewModel.openSurvey(7L)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is Stage4MapUiState.Loaded)
            val loadedState = state as Stage4MapUiState.Loaded
            val movedToYes =
                loadedState.state.yesMeteorites.any {
                    it.inferenceResultId == candidateId
                }
            assertTrue(movedToYes)

            collectJob.cancel()
        }
}
