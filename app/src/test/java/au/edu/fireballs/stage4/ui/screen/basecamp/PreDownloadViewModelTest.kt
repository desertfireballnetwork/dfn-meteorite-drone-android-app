package au.edu.fireballs.stage4.ui.screen.basecamp

import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.workDataOf
import au.edu.fireballs.stage4.data.repository.ClaimRepository
import au.edu.fireballs.stage4.data.repository.ClaimResult
import au.edu.fireballs.stage4.data.repository.PreDownloadInventory
import au.edu.fireballs.stage4.data.repository.PreDownloadPreflightResult
import au.edu.fireballs.stage4.data.repository.PreDownloadSpaceCalculator
import au.edu.fireballs.stage4.data.repository.PreDownloadStoragePreflight
import au.edu.fireballs.stage4.data.repository.Stage4Repository
import au.edu.fireballs.stage4.data.tiles.BufferRadiusRepository
import au.edu.fireballs.stage4.data.tiles.GeotiffRadiusRepository
import au.edu.fireballs.stage4.sync.PreDownloadOrchestrator
import au.edu.fireballs.stage4.sync.PreDownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
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
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class PreDownloadViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var claimRepository: ClaimRepository
    private lateinit var stage4Repository: Stage4Repository
    private lateinit var bufferRadiusRepository: BufferRadiusRepository
    private lateinit var geotiffRadiusRepository: GeotiffRadiusRepository
    private lateinit var preflight: PreDownloadStoragePreflight
    private lateinit var workManager: FakePreDownloadWorkManager
    private lateinit var viewModel: PreDownloadViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        claimRepository = mock<ClaimRepository>()
        runBlocking {
            whenever(claimRepository.refreshClaimsToRoom(7L))
                .thenReturn(ClaimResult.Refreshed(count = 1))
            whenever(claimRepository.countActiveClaimedCandidates(7L)).thenReturn(1)
        }
        stage4Repository = mock<Stage4Repository>()
        runBlocking {
            whenever(stage4Repository.getLocalLatestTaskCreated(7L)).thenReturn("local")
            whenever(stage4Repository.fetchLatestTaskCreated(7L)).thenReturn("local")
        }
        bufferRadiusRepository = mock<BufferRadiusRepository>()
        whenever(bufferRadiusRepository.getBufferRadiusMeters()).thenReturn(100.0f)
        geotiffRadiusRepository = mock<GeotiffRadiusRepository>()
        whenever(geotiffRadiusRepository.getRadiusMeters()).thenReturn(15.0f)
        preflight = mock<PreDownloadStoragePreflight>()
        runBlocking {
            whenever(preflight.evaluate(any(), any(), any(), any()))
                .thenReturn(
                    PreDownloadPreflightResult.Allowed(
                        PreDownloadSpaceCalculator.calculate(
                            PreDownloadInventory(0, 2, 0, 1, 0, 0),
                            30_000_000_000L,
                            256_000_000_000L,
                        ),
                    ),
                )
        }
        workManager = FakePreDownloadWorkManager()
        viewModel =
            PreDownloadViewModel(
                claimRepository,
                stage4Repository,
                bufferRadiusRepository,
                geotiffRadiusRepository,
                preflight,
                workManager,
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `openSurvey reports claimed candidates and allowed preflight estimate`() =
        runTest(testDispatcher) {
            viewModel.openSurvey(7L)
            advanceUntilIdle()

            val ready = viewModel.uiState.value as PreDownloadUiState.Ready
            assertEquals(1, ready.claimedCandidateCount)
            assertEquals(0, ready.geotiffPresentCount)
            assertEquals(2, ready.geotiffMissingCount)
            assertEquals(0, ready.cropPresentCount)
            assertEquals(1, ready.cropMissingCount)
            assertEquals(5L * 1024L * 1024L, ready.estimatedIncrementalBytes)
            assertEquals(30_000_000_000L, ready.availableBytes)
            assertTrue(ready.canStart)
            assertFalse(ready.isStale)
        }

    @Test
    fun `openSurvey reports insufficient device space without allowing start`() =
        runTest(testDispatcher) {
            val estimate =
                PreDownloadSpaceCalculator.calculate(
                    PreDownloadInventory(0, 2, 0, 1, 0, 0),
                    4_000_000L,
                    100_000_000L,
                )
            whenever(preflight.evaluate(any(), any(), any(), any()))
                .thenReturn(PreDownloadPreflightResult.InsufficientDeviceSpace(estimate))

            viewModel.openSurvey(7L)
            advanceUntilIdle()

            val ready = viewModel.uiState.value as PreDownloadUiState.Ready
            assertFalse(ready.canStart)
            assertEquals(5L * 1024L * 1024L, ready.estimatedIncrementalBytes)
            assertEquals(4_000_000L, ready.availableBytes)
            assertEquals(estimate.reserveBytes, ready.reserveBytes)
            assertEquals(estimate.expectedRemainingBytes, ready.expectedRemainingBytes)
        }

    @Test
    fun `openSurvey surfaces stale warning when server task is newer`() =
        runTest(testDispatcher) {
            whenever(stage4Repository.fetchLatestTaskCreated(7L)).thenReturn("newer")
            whenever(stage4Repository.getLocalLatestTaskCreated(7L)).thenReturn("older")

            viewModel.openSurvey(7L)
            advanceUntilIdle()

            val ready = viewModel.uiState.value as PreDownloadUiState.Ready
            assertTrue(ready.isStale)
        }

    @Test
    fun `openSurvey does not warn when server value unavailable`() =
        runTest(testDispatcher) {
            whenever(stage4Repository.fetchLatestTaskCreated(7L)).thenReturn(null)

            viewModel.openSurvey(7L)
            advanceUntilIdle()

            val ready = viewModel.uiState.value as PreDownloadUiState.Ready
            assertFalse(ready.isStale)
        }

    @Test
    fun `startDownload enqueues unique work and observes progress`() =
        runTest(testDispatcher) {
            viewModel.openSurvey(7L)
            advanceUntilIdle()

            viewModel.startDownload()
            advanceUntilIdle()

            val enqueued = workManager.enqueued.single()
            assertEquals("pre_download", enqueued.first)
            assertEquals(ExistingWorkPolicy.REPLACE, workManager.lastPolicy)
            assertEquals(
                7L,
                enqueued.second.workSpec.input
                    .getLong(PreDownloadWorker.KEY_SURVEY_ID, -1L),
            )

            val request = enqueued.second
            workManager.emit(
                request.id,
                workInfo(
                    request.id,
                    WorkInfo.State.RUNNING,
                    progress =
                        Data
                            .Builder()
                            .putInt(PreDownloadOrchestrator.KEY_DONE, 3)
                            .putInt(PreDownloadOrchestrator.KEY_TOTAL, 10)
                            .putString(PreDownloadOrchestrator.KEY_PHASE, "tiles")
                            .build(),
                ),
            )
            advanceUntilIdle()

            val running = viewModel.uiState.value as PreDownloadUiState.Running
            assertEquals(3, running.done)
            assertEquals(10, running.total)
            assertEquals("tiles", running.phase)
        }

    @Test
    fun `succeeded work surfaces done state with bundle info`() =
        runTest(testDispatcher) {
            viewModel.openSurvey(7L)
            advanceUntilIdle()

            viewModel.startDownload()
            advanceUntilIdle()

            val request = workManager.enqueued.single().second
            workManager.emit(
                request.id,
                workInfo(
                    request.id,
                    WorkInfo.State.SUCCEEDED,
                    output =
                        Data
                            .Builder()
                            .putLong(PreDownloadOrchestrator.KEY_BUNDLE_ID, 42L)
                            .putInt(PreDownloadOrchestrator.KEY_TILE_COUNT, 5)
                            .putInt(PreDownloadOrchestrator.KEY_CROP_COUNT, 2)
                            .putInt(PreDownloadOrchestrator.KEY_SATELLITE_REGION_COUNT, 1)
                            .putInt(PreDownloadOrchestrator.KEY_CANDIDATE_COUNT, 1)
                            .putBoolean(PreDownloadOrchestrator.KEY_RE_DOWNLOAD_RECOMMENDED, false)
                            .build(),
                ),
            )
            advanceUntilIdle()

            val done = viewModel.uiState.value as PreDownloadUiState.Done
            assertEquals(42L, done.bundleId)
            assertEquals(5, done.tileCount)
            assertEquals(2, done.cropCount)
            assertEquals(1, done.satelliteRegionCount)
            assertEquals(1, done.candidateCount)
            assertFalse(done.reDownloadRecommended)
        }

    @Test
    fun `failed work surfaces error state`() =
        runTest(testDispatcher) {
            viewModel.openSurvey(7L)
            advanceUntilIdle()

            viewModel.startDownload()
            advanceUntilIdle()

            val request = workManager.enqueued.single().second
            workManager.emit(
                request.id,
                workInfo(
                    request.id,
                    WorkInfo.State.FAILED,
                    output =
                        Data
                            .Builder()
                            .putString(PreDownloadOrchestrator.KEY_ERROR, "Insufficient storage")
                            .build(),
                ),
            )
            advanceUntilIdle()

            val error = viewModel.uiState.value as PreDownloadUiState.Error
            assertEquals("Insufficient storage", error.message)
        }

    @Test
    fun `cancel calls cancelUniqueWork`() =
        runTest(testDispatcher) {
            viewModel.openSurvey(7L)
            advanceUntilIdle()

            viewModel.startDownload()
            advanceUntilIdle()

            viewModel.cancel()
            advanceUntilIdle()

            assertEquals(listOf("pre_download"), workManager.cancelled)
            assertTrue(viewModel.uiState.value is PreDownloadUiState.Cancelled)
        }

    @Test
    fun `stale task warning surfaced when re-download recommended`() =
        runTest(testDispatcher) {
            viewModel.openSurvey(7L)
            advanceUntilIdle()

            viewModel.startDownload()
            advanceUntilIdle()

            val request = workManager.enqueued.single().second
            workManager.emit(
                request.id,
                workInfo(
                    request.id,
                    WorkInfo.State.SUCCEEDED,
                    output =
                        Data
                            .Builder()
                            .putLong(PreDownloadOrchestrator.KEY_BUNDLE_ID, 1L)
                            .putBoolean(PreDownloadOrchestrator.KEY_RE_DOWNLOAD_RECOMMENDED, true)
                            .build(),
                ),
            )
            advanceUntilIdle()

            val done = viewModel.uiState.value as PreDownloadUiState.Done
            assertTrue(done.reDownloadRecommended)
        }

    @Test
    fun `openSurvey re-attaches to a running download`() =
        runTest(testDispatcher) {
            workManager.setUniqueWork(
                listOf(
                    workInfo(
                        UUID.randomUUID(),
                        WorkInfo.State.RUNNING,
                        progress =
                            workDataOf(
                                PreDownloadOrchestrator.KEY_DONE to 3,
                                PreDownloadOrchestrator.KEY_TOTAL to 10,
                                PreDownloadOrchestrator.KEY_PHASE to
                                    PreDownloadOrchestrator.PHASE_TILES,
                                PreDownloadOrchestrator.KEY_PHASE_INDEX to 2,
                                PreDownloadOrchestrator.KEY_PHASE_COUNT to 4,
                            ),
                    ),
                ),
            )

            viewModel.openSurvey(7L)
            advanceUntilIdle()

            val running = viewModel.uiState.value as PreDownloadUiState.Running
            assertEquals(PreDownloadOrchestrator.PHASE_TILES, running.phase)
            assertEquals(3, running.done)
            assertEquals(10, running.total)
            assertEquals(2, running.phaseIndex)
            assertEquals(4, running.phaseCount)
        }

    private fun workInfo(
        id: UUID,
        state: WorkInfo.State,
        output: Data = Data.EMPTY,
        progress: Data = Data.EMPTY,
    ): WorkInfo = WorkInfo(id, state, emptySet(), output, progress)

    private class FakePreDownloadWorkManager : PreDownloadWorkManager {
        val enqueued = mutableListOf<Pair<String, OneTimeWorkRequest>>()
        val cancelled = mutableListOf<String>()
        var lastPolicy: ExistingWorkPolicy? = null
        private val flows = mutableMapOf<UUID, MutableSharedFlow<WorkInfo>>()

        override fun enqueueUniqueWork(
            uniqueWorkName: String,
            existingWorkPolicy: ExistingWorkPolicy,
            request: OneTimeWorkRequest,
        ): Operation {
            enqueued.add(uniqueWorkName to request)
            lastPolicy = existingWorkPolicy
            flows[request.id] = MutableSharedFlow(extraBufferCapacity = 16)
            return mock<Operation>()
        }

        override fun getWorkInfoByIdFlow(id: UUID): Flow<WorkInfo> =
            flows[id] ?: MutableSharedFlow()

        private val uniqueInfos = MutableStateFlow<List<WorkInfo>>(emptyList())

        fun setUniqueWork(infos: List<WorkInfo>) {
            uniqueInfos.value = infos
        }

        override fun getWorkInfosForUniqueWorkFlow(uniqueWorkName: String): Flow<List<WorkInfo>> =
            uniqueInfos

        override fun cancelUniqueWork(uniqueWorkName: String): Operation {
            cancelled.add(uniqueWorkName)
            return mock<Operation>()
        }

        fun emit(
            id: UUID,
            info: WorkInfo,
        ) {
            flows[id]?.tryEmit(info)
        }
    }
}
