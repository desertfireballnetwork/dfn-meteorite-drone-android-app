package au.edu.fireballs.stage4.ui.screen.stage4map

import au.edu.fireballs.stage4.data.local.LocalDecisionEntity
import au.edu.fireballs.stage4.data.local.dao.LocalDecisionDao
import au.edu.fireballs.stage4.data.repository.Stage4FetchResult
import au.edu.fireballs.stage4.data.repository.Stage4Repository
import au.edu.fireballs.stage4.domain.model.BoundingBox
import au.edu.fireballs.stage4.domain.model.GeoCoordinate
import au.edu.fireballs.stage4.domain.model.ImageDims
import au.edu.fireballs.stage4.domain.model.Stage4Candidate
import au.edu.fireballs.stage4.domain.model.Stage4State
import au.edu.fireballs.stage4.domain.model.Stage4Survey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
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

    private fun dummyState(surveyId: Long): Stage4State =
        Stage4State(
            survey = Stage4Survey(id = surveyId, eventId = "EVENT_01", tilesetId = null),
            base = GeoCoordinate(-37.8, 145.0),
            surveyedAreas = emptyList(),
            unprocessedCandidates = emptyList(),
            yesMeteorites = emptyList(),
            noMeteorites = emptyList(),
            detectionTags = emptyList(),
            userLocations = emptyList(),
            showGeolocationAccuracyCircle = false,
            latestTaskCreated = "",
        )

    @Test
    fun openSurvey_success_updatesUiState() =
        runTest(testDispatcher) {
            val stateData = dummyState(7L)
            whenever(repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Success(stateData))

            viewModel = Stage4MapViewModel(repository, localDecisionDao)

            // Subscribe to uiState so WhileSubscribed flow collection starts
            val collectJob =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    viewModel.uiState.collect {}
                }

            viewModel.openSurvey(7L)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(
                "Expected Stage4MapUiState.Loaded but got $state",
                state is Stage4MapUiState.Loaded,
            )
            val loadedState = state as Stage4MapUiState.Loaded
            assertEquals(7L, loadedState.state.survey.id)

            collectJob.cancel()
        }

    @Test
    fun toggleLayer_updatesState() =
        runTest(testDispatcher) {
            val stateData = dummyState(7L)
            whenever(repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Success(stateData))

            viewModel = Stage4MapViewModel(repository, localDecisionDao)

            val collectJob =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    viewModel.uiState.collect {}
                }

            viewModel.openSurvey(7L)
            advanceUntilIdle()

            viewModel.toggleLayer(LayerType.UNPROCESSED, false)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(
                "Expected Stage4MapUiState.Loaded but got $state",
                state is Stage4MapUiState.Loaded,
            )
            val loadedState = state as Stage4MapUiState.Loaded
            assertFalse(
                "Expected showUnprocessed to be false",
                loadedState.layerToggleState.showUnprocessed,
            )

            collectJob.cancel()
        }

    @Test
    fun mergeStateWithDecisions_appliesLocalVerdict() =
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
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    viewModel.uiState.collect {}
                }

            viewModel.openSurvey(7L)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(
                "Expected Stage4MapUiState.Loaded but got $state",
                state is Stage4MapUiState.Loaded,
            )
            val loadedState = state as Stage4MapUiState.Loaded
            val movedToYes =
                loadedState.state.yesMeteorites.any {
                    it.inferenceResultId ==
                        candidateId
                }
            assertTrue("Expected candidate to be moved to yesMeteorites", movedToYes)

            collectJob.cancel()
        }
}
