package au.edu.fireballs.stage4.ui.screen.stage4map

import au.edu.fireballs.stage4.data.repository.Stage4FetchResult
import au.edu.fireballs.stage4.data.repository.Stage4Repository
import au.edu.fireballs.stage4.domain.model.GeoCoordinate
import au.edu.fireballs.stage4.domain.model.MapCameraTarget
import au.edu.fireballs.stage4.domain.model.Stage4State
import au.edu.fireballs.stage4.domain.model.Stage4Survey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class Stage4MapViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var stage4Repository: Stage4Repository
    private lateinit var viewModel: Stage4MapViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        stage4Repository = mock(Stage4Repository::class.java)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun stage4State(base: GeoCoordinate?): Stage4State =
        Stage4State(
            survey = Stage4Survey(id = 7L, eventId = "DN240703-02", tilesetId = null),
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
        runTest {
            viewModel = Stage4MapViewModel(stage4Repository)

            assertEquals(Stage4MapUiState.Loading, viewModel.uiState.value)
        }

    @Test
    fun `openSurvey success updates state to Loaded with camera target at base zoom 13`() =
        runTest {
            val base = GeoCoordinate(latitude = -29.467, longitude = 115.342)
            val fixture = stage4State(base)
            `when`(stage4Repository.getCandidatesState(7L)).thenReturn(
                Stage4FetchResult.Success(fixture),
            )

            viewModel = Stage4MapViewModel(stage4Repository)
            viewModel.openSurvey(7L)
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is Stage4MapUiState.Loaded)
            val loaded = state as Stage4MapUiState.Loaded
            assertEquals(fixture, loaded.state)
            assertEquals(
                MapCameraTarget(latitude = -29.467, longitude = 115.342, zoom = 13.0),
                loaded.cameraTarget,
            )
        }

    @Test
    fun `openSurvey success with null base and empty areas yields null camera target`() =
        runTest {
            val fixture = stage4State(base = null)
            `when`(stage4Repository.getCandidatesState(7L)).thenReturn(
                Stage4FetchResult.Success(fixture),
            )

            viewModel = Stage4MapViewModel(stage4Repository)
            viewModel.openSurvey(7L)
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is Stage4MapUiState.Loaded)
            assertNull((state as Stage4MapUiState.Loaded).cameraTarget)
        }

    @Test
    fun `openSurvey error updates state to Error with message passthrough`() =
        runTest {
            `when`(stage4Repository.getCandidatesState(7L)).thenReturn(
                Stage4FetchResult.Error("No Stage 4 candidates task is available."),
            )

            viewModel = Stage4MapViewModel(stage4Repository)
            viewModel.openSurvey(7L)
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is Stage4MapUiState.Error)
            assertEquals(
                "No Stage 4 candidates task is available.",
                (state as Stage4MapUiState.Error).message,
            )
        }

    @Test
    fun `openSurvey network error updates state to Error with generic offline message`() =
        runTest {
            `when`(stage4Repository.getCandidatesState(7L)).thenReturn(
                Stage4FetchResult.NetworkError,
            )

            viewModel = Stage4MapViewModel(stage4Repository)
            viewModel.openSurvey(7L)
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is Stage4MapUiState.Error)
            assertEquals(
                "Network error. Please check your connection.",
                (state as Stage4MapUiState.Error).message,
            )
        }

    @Test
    fun `openSurvey auth expired updates state to AuthExpired`() =
        runTest {
            `when`(stage4Repository.getCandidatesState(7L)).thenReturn(
                Stage4FetchResult.AuthExpired,
            )

            viewModel = Stage4MapViewModel(stage4Repository)
            viewModel.openSurvey(7L)
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(viewModel.uiState.value is Stage4MapUiState.AuthExpired)
        }

    @Test
    fun `retry after failure re-calls repository and emits Loaded on second success`() =
        runTest {
            val base = GeoCoordinate(latitude = -29.467, longitude = 115.342)
            val fixture = stage4State(base)
            `when`(stage4Repository.getCandidatesState(7L)).thenReturn(
                Stage4FetchResult.NetworkError,
            )

            viewModel = Stage4MapViewModel(stage4Repository)
            viewModel.openSurvey(7L)
            testDispatcher.scheduler.advanceUntilIdle()
            assertTrue(viewModel.uiState.value is Stage4MapUiState.Error)

            `when`(stage4Repository.getCandidatesState(7L)).thenReturn(
                Stage4FetchResult.Success(fixture),
            )
            viewModel.retry()
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is Stage4MapUiState.Loaded)
            assertEquals(fixture, (state as Stage4MapUiState.Loaded).state)
            verify(stage4Repository, times(2)).getCandidatesState(7L)
        }
}
