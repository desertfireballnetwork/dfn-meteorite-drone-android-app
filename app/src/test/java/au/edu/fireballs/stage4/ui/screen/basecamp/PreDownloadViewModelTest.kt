package au.edu.fireballs.stage4.ui.screen.basecamp

import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkInfo
import au.edu.fireballs.stage4.data.local.CandidateEntity
import au.edu.fireballs.stage4.data.local.ClaimEntity
import au.edu.fireballs.stage4.data.local.dao.CandidateDao
import au.edu.fireballs.stage4.data.local.dao.ClaimDao
import au.edu.fireballs.stage4.data.tiles.BufferRadiusRepository
import au.edu.fireballs.stage4.sync.PreDownloadOrchestrator
import au.edu.fireballs.stage4.sync.PreDownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class PreDownloadViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var candidateDao: FakeCandidateDao
    private lateinit var claimDao: FakeClaimDao
    private lateinit var bufferRadiusRepository: BufferRadiusRepository
    private lateinit var workManager: FakePreDownloadWorkManager
    private lateinit var viewModel: PreDownloadViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        candidateDao = FakeCandidateDao()
        claimDao = FakeClaimDao()
        bufferRadiusRepository = mock<BufferRadiusRepository>()
        whenever(bufferRadiusRepository.getBufferRadiusMeters()).thenReturn(100.0f)
        workManager = FakePreDownloadWorkManager()
        viewModel =
            PreDownloadViewModel(
                candidateDao,
                claimDao,
                bufferRadiusRepository,
                workManager,
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `openSurvey computes claimed candidate count and estimated size`() =
        runTest(testDispatcher) {
            candidateDao.candidates =
                listOf(candidate(1L), candidate(2L), candidate(3L))
            claimDao.claims =
                listOf(
                    claim(1L, isActive = true, isMine = true),
                    claim(2L, isActive = true, isMine = false),
                    claim(3L, isActive = false, isMine = true),
                )

            viewModel.openSurvey(7L)
            advanceUntilIdle()

            val ready = viewModel.uiState.value as PreDownloadUiState.Ready
            assertEquals(1, ready.claimedCandidateCount)
            assertEquals(1_600_000L, ready.estimatedSizeBytes)
        }

    @Test
    fun `startDownload enqueues unique work and observes progress`() =
        runTest(testDispatcher) {
            candidateDao.candidates = listOf(candidate(1L))
            viewModel.openSurvey(7L)
            advanceUntilIdle()

            viewModel.startDownload()
            advanceUntilIdle()

            val enqueued = workManager.enqueued.single()
            assertEquals("pre_download_7", enqueued.first)
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
            candidateDao.candidates = listOf(candidate(1L))
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
            candidateDao.candidates = listOf(candidate(1L))
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
            candidateDao.candidates = listOf(candidate(1L))
            viewModel.openSurvey(7L)
            advanceUntilIdle()

            viewModel.startDownload()
            advanceUntilIdle()

            viewModel.cancel()
            advanceUntilIdle()

            assertEquals(listOf("pre_download_7"), workManager.cancelled)
            assertTrue(viewModel.uiState.value is PreDownloadUiState.Cancelled)
        }

    @Test
    fun `stale task warning surfaced when re-download recommended`() =
        runTest(testDispatcher) {
            candidateDao.candidates = listOf(candidate(1L))
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

    private fun candidate(id: Long): CandidateEntity =
        CandidateEntity(
            inferenceResultId = id,
            surveyId = 7L,
            imageId = id,
            imageFilename = "frame_$id.png",
            imageWidth = 1920,
            imageHeight = 1080,
            geoCentroidLat = 0.0,
            geoCentroidLon = 0.0,
            geoAreaJson = "[]",
            boxX = 0,
            boxY = 0,
            boxW = 10,
            boxH = 10,
            confidence = 0.9f,
            sizeMw = null,
            sizeMh = null,
            isClaimedByMe = false,
            claimOwnerUsername = null,
        )

    private fun claim(
        id: Long,
        isActive: Boolean,
        isMine: Boolean,
    ): ClaimEntity =
        ClaimEntity(
            inferenceResultId = id,
            surveyId = 7L,
            userId = 2L,
            username = "jdoe",
            claimedAt = "2026-01-01T00:00:00Z",
            isMine = isMine,
            isActive = isActive,
        )

    private fun workInfo(
        id: UUID,
        state: WorkInfo.State,
        output: Data = Data.EMPTY,
        progress: Data = Data.EMPTY,
    ): WorkInfo = WorkInfo(id, state, emptySet(), output, progress)

    private class FakeCandidateDao : CandidateDao {
        var candidates: List<CandidateEntity> = emptyList()

        override suspend fun upsertAll(candidates: List<CandidateEntity>) = Unit

        override suspend fun upsert(candidate: CandidateEntity) = Unit

        override fun observeCandidatesForSurvey(surveyId: Long): Flow<List<CandidateEntity>> =
            flowOf(candidates.filter { it.surveyId == surveyId })

        override suspend fun getCandidatesForSurvey(surveyId: Long): List<CandidateEntity> =
            candidates.filter { it.surveyId == surveyId }

        override suspend fun getById(inferenceResultId: Long): CandidateEntity? =
            candidates.firstOrNull { it.inferenceResultId == inferenceResultId }

        override suspend fun deleteForSurvey(surveyId: Long) = Unit

        override suspend fun deleteAll() = Unit

        override suspend fun replaceForSurvey(
            surveyId: Long,
            candidates: List<CandidateEntity>,
        ) = Unit
    }

    private class FakeClaimDao : ClaimDao {
        var claims: List<ClaimEntity> = emptyList()

        override suspend fun upsert(claim: ClaimEntity) = Unit

        override suspend fun upsertAll(claims: List<ClaimEntity>) = Unit

        override fun getClaims(
            surveyId: Long,
            onlyActive: Boolean,
        ): Flow<List<ClaimEntity>> =
            flowOf(
                claims.filter {
                    it.surveyId == surveyId && (!onlyActive || it.isActive)
                },
            )

        override suspend fun getByCandidateId(inferenceResultId: Long): ClaimEntity? =
            claims.firstOrNull { it.inferenceResultId == inferenceResultId }

        override suspend fun releaseClaimsForUser(
            userId: Long,
            candidateIds: List<Long>,
        ) = Unit

        override suspend fun deleteForSurvey(surveyId: Long) = Unit

        override suspend fun deleteAll() = Unit
    }

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
