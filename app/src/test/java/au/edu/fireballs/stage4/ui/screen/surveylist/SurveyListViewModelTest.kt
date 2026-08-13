package au.edu.fireballs.stage4.ui.screen.surveylist

import au.edu.fireballs.stage4.data.repository.SurveyFetchResult
import au.edu.fireballs.stage4.data.repository.SurveyRepository
import au.edu.fireballs.stage4.domain.model.Survey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class SurveyListViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var surveyRepository: SurveyRepository
    private lateinit var viewModel: SurveyListViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        surveyRepository = mock(SurveyRepository::class.java)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadSurveys success updates state to Loaded`() =
        runTest {
            val mockSurveys =
                listOf(
                    Survey(
                        id = 42L,
                        eventId = "DN240703-02",
                        description = "Murchison search",
                        createdIso = "2026-04-08T10:15:00Z",
                        hasStage4 = true,
                        isActive = true,
                    ),
                )
            `when`(surveyRepository.getSurveys()).thenReturn(SurveyFetchResult.Success(mockSurveys))

            viewModel = SurveyListViewModel(surveyRepository)
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is SurveyListUiState.Loaded)
            assertEquals(mockSurveys, (state as SurveyListUiState.Loaded).surveys)
        }

    @Test
    fun `loadSurveys empty response updates state to Empty`() =
        runTest {
            `when`(surveyRepository.getSurveys()).thenReturn(SurveyFetchResult.Success(emptyList()))

            viewModel = SurveyListViewModel(surveyRepository)
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is SurveyListUiState.Empty)
        }

    @Test
    fun `loadSurveys auth expired updates state to AuthExpired`() =
        runTest {
            `when`(surveyRepository.getSurveys()).thenReturn(SurveyFetchResult.AuthExpired)

            viewModel = SurveyListViewModel(surveyRepository)
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is SurveyListUiState.AuthExpired)
        }

    @Test
    fun `loadSurveys error updates state to Error`() =
        runTest {
            `when`(
                surveyRepository.getSurveys(),
            ).thenReturn(SurveyFetchResult.Error("Network failure"))

            viewModel = SurveyListViewModel(surveyRepository)
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is SurveyListUiState.Error)
            assertEquals("Network failure", (state as SurveyListUiState.Error).message)
        }
}
