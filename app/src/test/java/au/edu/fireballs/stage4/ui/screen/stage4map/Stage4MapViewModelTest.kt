package au.edu.fireballs.stage4.ui.screen.stage4map

import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.workDataOf
import au.edu.fireballs.stage4.data.local.ClaimEntity
import au.edu.fireballs.stage4.data.local.LocalDecisionEntity
import au.edu.fireballs.stage4.data.local.OfflineBundleEntity
import au.edu.fireballs.stage4.data.local.dao.ClaimDao
import au.edu.fireballs.stage4.data.local.dao.LocalDecisionDao
import au.edu.fireballs.stage4.data.local.dao.OfflineBundleDao
import au.edu.fireballs.stage4.data.repository.CandidateImageRepository
import au.edu.fireballs.stage4.data.repository.NetworkState
import au.edu.fireballs.stage4.data.repository.NetworkStateRepository
import au.edu.fireballs.stage4.data.repository.Stage4FetchResult
import au.edu.fireballs.stage4.data.repository.Stage4Repository
import au.edu.fireballs.stage4.data.tiles.AuthenticatedTileHttpInterceptor
import au.edu.fireballs.stage4.data.tiles.TileStore
import au.edu.fireballs.stage4.domain.model.BoundingBox
import au.edu.fireballs.stage4.domain.model.GeoCoordinate
import au.edu.fireballs.stage4.domain.model.ImageDims
import au.edu.fireballs.stage4.domain.model.MapCameraTarget
import au.edu.fireballs.stage4.domain.model.Stage4Candidate
import au.edu.fireballs.stage4.domain.model.Stage4State
import au.edu.fireballs.stage4.domain.model.Stage4Survey
import au.edu.fireballs.stage4.sync.SyncWorker
import au.edu.fireballs.stage4.ui.screen.basecamp.PreDownloadWorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
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
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class Stage4MapViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val repository: Stage4Repository = mock()
    private val claimDao: ClaimDao = mock()
    private val localDecisionDao: LocalDecisionDao = mock()
    private val offlineBundleDao: OfflineBundleDao = mock()
    private val candidateImageRepository: CandidateImageRepository = mock()
    private val claimsFlow = MutableStateFlow<List<ClaimEntity>>(emptyList())
    private val pendingDecisionsFlow = MutableStateFlow<List<LocalDecisionEntity>>(emptyList())
    private val tileStore = TileStore(File.createTempFile("vm-tiles", "").parentFile)
    private val tileHttpInterceptor: AuthenticatedTileHttpInterceptor = mock()
    private val syncWorkManager: SyncWorkManager = mock()
    private val networkStateRepository: NetworkStateRepository = mock()
    private val preDownloadWorkManager: PreDownloadWorkManager = mock()
    private val networkStateFlow = MutableStateFlow<NetworkState>(NetworkState.Online)
    private lateinit var viewModel: Stage4MapViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        claimsFlow.value = emptyList()
        pendingDecisionsFlow.value = emptyList()
        whenever(claimDao.getClaims(any(), eq(true)))
            .thenReturn(claimsFlow)
        whenever(localDecisionDao.observeDecisionsForSurvey(any()))
            .thenReturn(pendingDecisionsFlow)
        whenever(offlineBundleDao.observeLatestBundleForSurvey(any()))
            .thenReturn(MutableStateFlow(null))
        whenever(networkStateRepository.networkState).thenReturn(networkStateFlow)
        whenever(preDownloadWorkManager.getWorkInfosForUniqueWorkFlow(any()))
            .thenReturn(emptyFlow())
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
            viewModel =
                Stage4MapViewModel(
                    repository,
                    claimDao,
                    localDecisionDao,
                    offlineBundleDao,
                    candidateImageRepository,
                    tileStore,
                    tileHttpInterceptor,
                    syncWorkManager,
                    networkStateRepository,
                    preDownloadWorkManager,
                )

            assertEquals(Stage4MapUiState.Loading, viewModel.uiState.value)
        }

    @Test
    fun `openSurvey with offline Success result surfaces user message`() =
        runTest(testDispatcher) {
            val fixture = dummyState(7L)
            whenever(repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Success(fixture, isOffline = true))

            viewModel =
                Stage4MapViewModel(
                    repository,
                    claimDao,
                    localDecisionDao,
                    offlineBundleDao,
                    candidateImageRepository,
                    tileStore,
                    tileHttpInterceptor,
                    syncWorkManager,
                    networkStateRepository,
                    preDownloadWorkManager,
                )
            val collectJob =
                backgroundScope.launch(testDispatcher) {
                    viewModel.uiState.collect {}
                }

            viewModel.openSurvey(7L)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is Stage4MapUiState.Loaded)
            val loaded = state as Stage4MapUiState.Loaded
            assertEquals("Offline — displaying previous map data", loaded.userMessage)

            collectJob.cancel()
        }

    @Test
    fun `offline bundle camera targets centroid of own claimed candidates at zoom 18`() =
        runTest(testDispatcher) {
            val bundleFlow =
                MutableStateFlow<OfflineBundleEntity?>(
                    OfflineBundleEntity(
                        surveyId = 7L,
                        created = "2026-09-16T00:00:00Z",
                        totalBytes = 1L,
                        tileCount = 1,
                        satelliteRegionCount = 1,
                        candidateCount = 2,
                        bufferMeters = 100f,
                    ),
                )
            val first =
                Stage4Candidate(
                    inferenceResultId = 11L,
                    imageId = 11L,
                    imageFilename = "11.png",
                    imageDims = ImageDims(100, 100),
                    geoCentroid = GeoCoordinate(-29.8526, 124.8098),
                    geoArea = null,
                    box = BoundingBox(0, 0, 10, 10),
                    confidence = 0.9,
                    sizeM = null,
                    claimedByMe = false,
                    claimedByOther = false,
                )
            val second =
                first.copy(
                    inferenceResultId = 12L,
                    geoCentroid = GeoCoordinate(-29.8528, 124.8108),
                )
            val fixture =
                dummyState(7L).copy(unprocessedCandidates = listOf(first, second))
            claimsFlow.value =
                listOf(
                    ClaimEntity(11L, 7L, 1L, "me", "", isMine = true, isActive = true),
                    ClaimEntity(12L, 7L, 1L, "me", "", isMine = true, isActive = true),
                )
            networkStateFlow.value = NetworkState.Offline
            whenever(offlineBundleDao.observeLatestBundleForSurvey(any()))
                .thenReturn(bundleFlow)
            whenever(repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Success(fixture, isOffline = true))

            viewModel =
                Stage4MapViewModel(
                    repository,
                    claimDao,
                    localDecisionDao,
                    offlineBundleDao,
                    candidateImageRepository,
                    tileStore,
                    tileHttpInterceptor,
                    syncWorkManager,
                    networkStateRepository,
                    preDownloadWorkManager,
                )
            val collectJob =
                backgroundScope.launch(testDispatcher) {
                    viewModel.uiState.collect {}
                }

            viewModel.openSurvey(7L)
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as Stage4MapUiState.Loaded
            assertEquals(
                MapCameraTarget(-29.8527, 124.8103, 18.0),
                loaded.cameraTarget,
            )

            collectJob.cancel()
        }

    @Test
    fun `openSurvey success updates state to Loaded with camera target at base zoom 13`() =
        runTest(testDispatcher) {
            val base = GeoCoordinate(latitude = -29.467, longitude = 115.342)
            val fixture = dummyState(7L, base)
            whenever(repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Success(fixture))

            viewModel =
                Stage4MapViewModel(
                    repository,
                    claimDao,
                    localDecisionDao,
                    offlineBundleDao,
                    candidateImageRepository,
                    tileStore,
                    tileHttpInterceptor,
                    syncWorkManager,
                    networkStateRepository,
                    preDownloadWorkManager,
                )
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

            viewModel =
                Stage4MapViewModel(
                    repository,
                    claimDao,
                    localDecisionDao,
                    offlineBundleDao,
                    candidateImageRepository,
                    tileStore,
                    tileHttpInterceptor,
                    syncWorkManager,
                    networkStateRepository,
                    preDownloadWorkManager,
                )
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

            viewModel =
                Stage4MapViewModel(
                    repository,
                    claimDao,
                    localDecisionDao,
                    offlineBundleDao,
                    candidateImageRepository,
                    tileStore,
                    tileHttpInterceptor,
                    syncWorkManager,
                    networkStateRepository,
                    preDownloadWorkManager,
                )
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

            viewModel =
                Stage4MapViewModel(
                    repository,
                    claimDao,
                    localDecisionDao,
                    offlineBundleDao,
                    candidateImageRepository,
                    tileStore,
                    tileHttpInterceptor,
                    syncWorkManager,
                    networkStateRepository,
                    preDownloadWorkManager,
                )
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

            viewModel =
                Stage4MapViewModel(
                    repository,
                    claimDao,
                    localDecisionDao,
                    offlineBundleDao,
                    candidateImageRepository,
                    tileStore,
                    tileHttpInterceptor,
                    syncWorkManager,
                    networkStateRepository,
                    preDownloadWorkManager,
                )
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
    fun `tile interceptor auth loss surfaces AuthExpired`() =
        runTest(testDispatcher) {
            val fixture = dummyState(7L)
            whenever(repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Success(fixture))

            viewModel =
                Stage4MapViewModel(
                    repository,
                    claimDao,
                    localDecisionDao,
                    offlineBundleDao,
                    candidateImageRepository,
                    tileStore,
                    tileHttpInterceptor,
                    syncWorkManager,
                    networkStateRepository,
                    preDownloadWorkManager,
                )
            val onAuthLostCaptor = argumentCaptor<() -> Unit>()
            verify(tileHttpInterceptor).onAuthLost = onAuthLostCaptor.capture()

            val collectJob =
                backgroundScope.launch(testDispatcher) {
                    viewModel.uiState.collect {}
                }

            viewModel.openSurvey(7L)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value is Stage4MapUiState.Loaded)

            onAuthLostCaptor.firstValue.invoke()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value is Stage4MapUiState.AuthExpired)

            collectJob.cancel()
        }

    @Test
    fun `openSurvey access denied error updates state to Error not AuthExpired`() =
        runTest(testDispatcher) {
            whenever(repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Error("You don't have access to this survey"))

            viewModel =
                Stage4MapViewModel(
                    repository,
                    claimDao,
                    localDecisionDao,
                    offlineBundleDao,
                    candidateImageRepository,
                    tileStore,
                    tileHttpInterceptor,
                    syncWorkManager,
                    networkStateRepository,
                    preDownloadWorkManager,
                )
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

            viewModel =
                Stage4MapViewModel(
                    repository,
                    claimDao,
                    localDecisionDao,
                    offlineBundleDao,
                    candidateImageRepository,
                    tileStore,
                    tileHttpInterceptor,
                    syncWorkManager,
                    networkStateRepository,
                    preDownloadWorkManager,
                )
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

            viewModel =
                Stage4MapViewModel(
                    repository,
                    claimDao,
                    localDecisionDao,
                    offlineBundleDao,
                    candidateImageRepository,
                    tileStore,
                    tileHttpInterceptor,
                    syncWorkManager,
                    networkStateRepository,
                    preDownloadWorkManager,
                )

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

            viewModel =
                Stage4MapViewModel(
                    repository,
                    claimDao,
                    localDecisionDao,
                    offlineBundleDao,
                    candidateImageRepository,
                    tileStore,
                    tileHttpInterceptor,
                    syncWorkManager,
                    networkStateRepository,
                    preDownloadWorkManager,
                )

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

            viewModel =
                Stage4MapViewModel(
                    repository,
                    claimDao,
                    localDecisionDao,
                    offlineBundleDao,
                    candidateImageRepository,
                    tileStore,
                    tileHttpInterceptor,
                    syncWorkManager,
                    networkStateRepository,
                    preDownloadWorkManager,
                )
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

            viewModel =
                Stage4MapViewModel(
                    repository,
                    claimDao,
                    localDecisionDao,
                    offlineBundleDao,
                    candidateImageRepository,
                    tileStore,
                    tileHttpInterceptor,
                    syncWorkManager,
                    networkStateRepository,
                    preDownloadWorkManager,
                )

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

            viewModel =
                Stage4MapViewModel(
                    repository,
                    claimDao,
                    localDecisionDao,
                    offlineBundleDao,
                    candidateImageRepository,
                    tileStore,
                    tileHttpInterceptor,
                    syncWorkManager,
                    networkStateRepository,
                    preDownloadWorkManager,
                )
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

            viewModel =
                Stage4MapViewModel(
                    repository,
                    claimDao,
                    localDecisionDao,
                    offlineBundleDao,
                    candidateImageRepository,
                    tileStore,
                    tileHttpInterceptor,
                    syncWorkManager,
                    networkStateRepository,
                    preDownloadWorkManager,
                )
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

    @Test
    fun `syncNow enqueues sync and tracks work status`() =
        runTest(testDispatcher) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>().build()
            val workInfoFlow = MutableSharedFlow<WorkInfo>(extraBufferCapacity = 4)
            whenever(syncWorkManager.enqueueSync()).thenReturn(request)
            whenever(syncWorkManager.getWorkInfoByIdFlow(request.id)).thenReturn(workInfoFlow)

            viewModel =
                Stage4MapViewModel(
                    repository,
                    claimDao,
                    localDecisionDao,
                    offlineBundleDao,
                    candidateImageRepository,
                    tileStore,
                    tileHttpInterceptor,
                    syncWorkManager,
                    networkStateRepository,
                    preDownloadWorkManager,
                )

            viewModel.syncNow()
            advanceUntilIdle()
            verify(syncWorkManager).enqueueSync()

            workInfoFlow.tryEmit(
                WorkInfo(request.id, WorkInfo.State.RUNNING, emptySet(), Data.EMPTY, Data.EMPTY),
            )
            advanceUntilIdle()
            assertEquals(SyncStatus.Syncing, viewModel.syncStatus.value)

            workInfoFlow.tryEmit(
                WorkInfo(request.id, WorkInfo.State.SUCCEEDED, emptySet(), Data.EMPTY, Data.EMPTY),
            )
            advanceUntilIdle()
            assertEquals(SyncStatus.Complete, viewModel.syncStatus.value)
        }

    @Test
    fun `syncNow surfaces auth expired when worker reports auth expiry`() =
        runTest(testDispatcher) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>().build()
            val workInfoFlow = MutableSharedFlow<WorkInfo>(extraBufferCapacity = 4)
            whenever(syncWorkManager.enqueueSync()).thenReturn(request)
            whenever(syncWorkManager.getWorkInfoByIdFlow(request.id)).thenReturn(workInfoFlow)

            viewModel =
                Stage4MapViewModel(
                    repository,
                    claimDao,
                    localDecisionDao,
                    offlineBundleDao,
                    candidateImageRepository,
                    tileStore,
                    tileHttpInterceptor,
                    syncWorkManager,
                    networkStateRepository,
                    preDownloadWorkManager,
                )
            val collectJob =
                backgroundScope.launch(testDispatcher) {
                    viewModel.uiState.collect {}
                }

            viewModel.syncNow()
            advanceUntilIdle()

            workInfoFlow.tryEmit(
                WorkInfo(
                    request.id,
                    WorkInfo.State.SUCCEEDED,
                    emptySet(),
                    workDataOf(SyncWorker.KEY_AUTH_EXPIRED to true),
                    Data.EMPTY,
                ),
            )
            advanceUntilIdle()

            assertEquals(SyncStatus.AuthExpired, viewModel.syncStatus.value)
            assertTrue(viewModel.uiState.value is Stage4MapUiState.AuthExpired)
            collectJob.cancel()
        }

    @Test
    fun `syncNow while sync already running keeps status Syncing`() =
        runTest(testDispatcher) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>().build()
            val workInfoFlow = MutableSharedFlow<WorkInfo>(extraBufferCapacity = 4)
            whenever(syncWorkManager.enqueueSync()).thenReturn(request)
            whenever(syncWorkManager.getWorkInfoByIdFlow(request.id)).thenReturn(workInfoFlow)

            viewModel =
                Stage4MapViewModel(
                    repository,
                    claimDao,
                    localDecisionDao,
                    offlineBundleDao,
                    candidateImageRepository,
                    tileStore,
                    tileHttpInterceptor,
                    syncWorkManager,
                    networkStateRepository,
                    preDownloadWorkManager,
                )

            viewModel.syncNow()
            advanceUntilIdle()
            verify(syncWorkManager).enqueueSync()

            workInfoFlow.tryEmit(
                WorkInfo(request.id, WorkInfo.State.RUNNING, emptySet(), Data.EMPTY, Data.EMPTY),
            )
            advanceUntilIdle()
            assertEquals(SyncStatus.Syncing, viewModel.syncStatus.value)

            viewModel.syncNow()
            advanceUntilIdle()

            assertEquals(SyncStatus.Syncing, viewModel.syncStatus.value)
            verify(syncWorkManager, times(2)).enqueueSync()
        }

    @Test
    fun `overlay candidates are empty below zoom floor`() =
        runTest(testDispatcher) {
            val candidate = overlayCandidate(1L, 0.0, 0.0)
            prepareOverlayTest(listOf(candidate), setOf(1L))
            val collectJob =
                backgroundScope.launch(testDispatcher) {
                    viewModel.overlayCandidates.collect {}
                }

            viewModel.updateCamera(MapCameraTarget(0.0, 0.0, 13.0))
            advanceUntilIdle()

            assertTrue(viewModel.overlayCandidates.value.isEmpty())
            collectJob.cancel()
        }

    @Test
    fun `overlay candidates include claimed and exclude unclaimed`() =
        runTest(testDispatcher) {
            val claimed = overlayCandidate(1L, 0.0, 0.0)
            val unclaimed = overlayCandidate(2L, 0.0, 0.0)
            prepareOverlayTest(listOf(claimed, unclaimed), setOf(1L))
            val collectJob =
                backgroundScope.launch(testDispatcher) {
                    viewModel.overlayCandidates.collect {}
                }

            viewModel.updateCamera(MapCameraTarget(0.0, 0.0, 16.0))
            advanceUntilIdle()

            assertEquals(listOf(1L), viewModel.overlayCandidates.value.map { it.first })
            collectJob.cancel()
        }

    @Test
    fun `overlay candidates exclude claimed candidate outside viewport`() =
        runTest(testDispatcher) {
            val nearby = overlayCandidate(1L, 0.0, 0.0)
            val distant = overlayCandidate(2L, 5.0, 5.0)
            prepareOverlayTest(listOf(nearby, distant), setOf(1L, 2L))
            val collectJob =
                backgroundScope.launch(testDispatcher) {
                    viewModel.overlayCandidates.collect {}
                }

            viewModel.updateCamera(MapCameraTarget(0.0, 0.0, 16.0))
            advanceUntilIdle()

            assertEquals(listOf(1L), viewModel.overlayCandidates.value.map { it.first })
            collectJob.cancel()
        }

    @Test
    fun `overlay candidates cap nearest claimed candidates at thirty`() =
        runTest(testDispatcher) {
            val candidates =
                (1L..31L).map { id ->
                    overlayCandidate(id, 0.0, id.toDouble() * 0.000001)
                }
            prepareOverlayTest(candidates, candidates.map { it.inferenceResultId }.toSet())
            val collectJob =
                backgroundScope.launch(testDispatcher) {
                    viewModel.overlayCandidates.collect {}
                }

            viewModel.updateCamera(MapCameraTarget(0.0, 0.0, 16.0))
            advanceUntilIdle()

            val ids = viewModel.overlayCandidates.value.map { it.first }
            assertEquals(30, ids.size)
            assertFalse(31L in ids)
            collectJob.cancel()
        }

    @Test
    fun `overlay candidates include selected candidate without claim`() =
        runTest(testDispatcher) {
            val candidate = overlayCandidate(7L, 0.0, 0.0)
            prepareOverlayTest(listOf(candidate), emptySet())
            val collectJob =
                backgroundScope.launch(testDispatcher) {
                    viewModel.overlayCandidates.collect {}
                }

            viewModel.setSelectedCandidate(7L)
            viewModel.updateCamera(MapCameraTarget(0.0, 0.0, 16.0))
            advanceUntilIdle()

            assertEquals(listOf(7L), viewModel.overlayCandidates.value.map { it.first })
            collectJob.cancel()
        }

    @Test
    fun `hasOfflineBundle reflects bundle presence for the opened survey`() =
        runTest(testDispatcher) {
            val bundleFlow = MutableStateFlow<OfflineBundleEntity?>(null)
            whenever(offlineBundleDao.observeLatestBundleForSurvey(any())).thenReturn(bundleFlow)
            val fixture = dummyState(7L)
            whenever(repository.getCandidatesState(7L))
                .thenReturn(Stage4FetchResult.Success(fixture))

            viewModel =
                Stage4MapViewModel(
                    repository,
                    claimDao,
                    localDecisionDao,
                    offlineBundleDao,
                    candidateImageRepository,
                    tileStore,
                    tileHttpInterceptor,
                    syncWorkManager,
                    networkStateRepository,
                    preDownloadWorkManager,
                )
            val collectJob =
                backgroundScope.launch(testDispatcher) {
                    viewModel.hasOfflineBundle.collect {}
                }

            viewModel.openSurvey(7L)
            advanceUntilIdle()
            assertFalse(viewModel.hasOfflineBundle.value)

            bundleFlow.value =
                OfflineBundleEntity(
                    surveyId = 7L,
                    created = "2026-09-14T00:00:00Z",
                    totalBytes = 100L,
                    tileCount = 1,
                    satelliteRegionCount = 1,
                    candidateCount = 0,
                    bufferMeters = 0f,
                )
            advanceUntilIdle()
            assertTrue(viewModel.hasOfflineBundle.value)

            collectJob.cancel()
        }

    private suspend fun prepareOverlayTest(
        candidates: List<Stage4Candidate>,
        claimedIds: Set<Long>,
    ) {
        claimsFlow.value =
            claimedIds.map { id ->
                ClaimEntity(id, 7L, 1L, "me", "", isMine = true, isActive = true)
            }
        whenever(candidateImageRepository.getCandidateTileUrlPattern(any(), any()))
            .thenAnswer { invocation ->
                val surveyId = invocation.getArgument<Long>(0)
                val candidateId = invocation.getArgument<Long>(1)
                "https://tiles/$surveyId/$candidateId/{z}/{x}/{y}"
            }
        whenever(repository.getCandidatesState(7L))
            .thenReturn(
                Stage4FetchResult.Success(
                    dummyState(7L).copy(unprocessedCandidates = candidates),
                ),
            )
        viewModel =
            Stage4MapViewModel(
                repository,
                claimDao,
                localDecisionDao,
                offlineBundleDao,
                candidateImageRepository,
                tileStore,
                tileHttpInterceptor,
                syncWorkManager,
                networkStateRepository,
                preDownloadWorkManager,
            )
        viewModel.openSurvey(7L)
    }

    private fun overlayCandidate(
        id: Long,
        latitude: Double,
        longitude: Double,
    ): Stage4Candidate =
        Stage4Candidate(
            inferenceResultId = id,
            imageId = id,
            imageFilename = "$id.png",
            imageDims = ImageDims(100, 100),
            geoCentroid = GeoCoordinate(latitude, longitude),
            geoArea = null,
            box = BoundingBox(0, 0, 10, 10),
            confidence = 0.9,
            sizeM = null,
            claimedByMe = false,
            claimedByOther = false,
        )
}
