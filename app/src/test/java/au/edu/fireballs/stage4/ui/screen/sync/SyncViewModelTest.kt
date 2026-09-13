package au.edu.fireballs.stage4.ui.screen.sync

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import au.edu.fireballs.stage4.data.local.LocalDecisionEntity
import au.edu.fireballs.stage4.data.local.PendingPhotoUploadEntity
import au.edu.fireballs.stage4.data.local.dao.LocalDecisionDao
import au.edu.fireballs.stage4.data.local.dao.PendingPhotoUploadDao
import au.edu.fireballs.stage4.sync.SyncOrchestrator
import au.edu.fireballs.stage4.sync.SyncWorker
import au.edu.fireballs.stage4.ui.screen.stage4map.SyncWorkManager
import au.edu.fireballs.stage4.ui.screen.stage4map.WorkManagerSyncWorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class SyncViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val localDecisionDao: LocalDecisionDao = mock()
    private val pendingPhotoUploadDao: PendingPhotoUploadDao = mock()
    private val syncWorkManager: SyncWorkManager = mock()

    private val unsyncedCount = MutableStateFlow(0)
    private val notUploadedCount = MutableStateFlow(0)
    private val failedDecisions = MutableStateFlow<List<LocalDecisionEntity>>(emptyList())
    private val failedPhotos = MutableStateFlow<List<PendingPhotoUploadEntity>>(emptyList())
    private val workInfos = MutableStateFlow<List<WorkInfo>>(emptyList())

    private lateinit var viewModel: SyncViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        whenever(localDecisionDao.getUnsyncedCount()).thenReturn(unsyncedCount)
        whenever(pendingPhotoUploadDao.getNotUploadedCount()).thenReturn(notUploadedCount)
        whenever(localDecisionDao.getUnsyncedFailed()).thenReturn(failedDecisions)
        whenever(pendingPhotoUploadDao.getNotUploadedFailed()).thenReturn(failedPhotos)
        whenever(
            syncWorkManager.getWorkInfosForUniqueWorkFlow(
                WorkManagerSyncWorkManager.UNIQUE_WORK_NAME,
            ),
        ).thenReturn(workInfos)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun workInfo(
        state: WorkInfo.State,
        progress: Data = Data.EMPTY,
        output: Data = Data.EMPTY,
    ): WorkInfo =
        WorkInfo(
            id = UUID.randomUUID(),
            state = state,
            tags = emptySet(),
            progress = progress,
            outputData = output,
        )

    @Test
    fun `idle when no pending rows and no work`() =
        runTest(testDispatcher) {
            viewModel = SyncViewModel(localDecisionDao, pendingPhotoUploadDao, syncWorkManager)
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(SyncUiState.Idle, viewModel.uiState.value)
        }

    @Test
    fun `pending when rows exist and no work running`() =
        runTest(testDispatcher) {
            unsyncedCount.value = 2
            notUploadedCount.value = 1
            viewModel = SyncViewModel(localDecisionDao, pendingPhotoUploadDao, syncWorkManager)
            testDispatcher.scheduler.advanceUntilIdle()
            val state = viewModel.uiState.value
            assertTrue(state is SyncUiState.Pending)
            assertEquals(2, (state as SyncUiState.Pending).summary.pendingDecisions)
            assertEquals(1, state.summary.pendingPhotos)
        }

    @Test
    fun `running when work enqueued with progress`() =
        runTest(testDispatcher) {
            viewModel = SyncViewModel(localDecisionDao, pendingPhotoUploadDao, syncWorkManager)
            testDispatcher.scheduler.advanceUntilIdle()
            workInfos.value =
                listOf(
                    workInfo(
                        WorkInfo.State.RUNNING,
                        progress =
                            workDataOf(
                                SyncOrchestrator.KEY_DONE to 3,
                                SyncOrchestrator.KEY_TOTAL to 5,
                                SyncOrchestrator.KEY_PHASE to SyncOrchestrator.PHASE_VERDICTS,
                            ),
                    ),
                )
            testDispatcher.scheduler.advanceUntilIdle()
            val state = viewModel.uiState.value
            assertTrue(state is SyncUiState.Running)
            assertEquals(3, (state as SyncUiState.Running).done)
            assertEquals(5, state.total)
            assertEquals(SyncOrchestrator.PHASE_VERDICTS, state.phase)
        }

    @Test
    fun `complete when work succeeds with no pending rows`() =
        runTest(testDispatcher) {
            viewModel = SyncViewModel(localDecisionDao, pendingPhotoUploadDao, syncWorkManager)
            testDispatcher.scheduler.advanceUntilIdle()
            workInfos.value = listOf(workInfo(WorkInfo.State.SUCCEEDED))
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(SyncUiState.Complete, viewModel.uiState.value)
        }

    @Test
    fun `pending when work succeeds but rows still pending`() =
        runTest(testDispatcher) {
            unsyncedCount.value = 1
            viewModel = SyncViewModel(localDecisionDao, pendingPhotoUploadDao, syncWorkManager)
            testDispatcher.scheduler.advanceUntilIdle()
            workInfos.value = listOf(workInfo(WorkInfo.State.SUCCEEDED))
            testDispatcher.scheduler.advanceUntilIdle()
            assertTrue(viewModel.uiState.value is SyncUiState.Pending)
        }

    @Test
    fun `session expired when work succeeds with auth expired flag`() =
        runTest(testDispatcher) {
            viewModel = SyncViewModel(localDecisionDao, pendingPhotoUploadDao, syncWorkManager)
            testDispatcher.scheduler.advanceUntilIdle()
            workInfos.value =
                listOf(
                    workInfo(
                        WorkInfo.State.SUCCEEDED,
                        output = workDataOf(SyncWorker.KEY_AUTH_EXPIRED to true),
                    ),
                )
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(SyncUiState.SessionExpired, viewModel.uiState.value)
        }

    @Test
    fun `failed when work fails and failed rows present`() =
        runTest(testDispatcher) {
            val decision =
                LocalDecisionEntity(
                    inferenceResultId = 1L,
                    surveyId = 7L,
                    verdict = true,
                    detectionTagId = null,
                    capturedAt = "2026-09-02T12:00:00Z",
                    evidencePhotoRowId = null,
                    synced = false,
                    syncFailedReason = "Server returned code: 500",
                )
            failedDecisions.value = listOf(decision)
            unsyncedCount.value = 1
            viewModel = SyncViewModel(localDecisionDao, pendingPhotoUploadDao, syncWorkManager)
            testDispatcher.scheduler.advanceUntilIdle()
            workInfos.value = listOf(workInfo(WorkInfo.State.FAILED))
            testDispatcher.scheduler.advanceUntilIdle()
            val state = viewModel.uiState.value
            assertTrue(state is SyncUiState.Failed)
            assertEquals(listOf(decision), (state as SyncUiState.Failed).summary.failedDecisions)
        }

    @Test
    fun `syncNow enqueues via sync work manager`() =
        runTest(testDispatcher) {
            viewModel = SyncViewModel(localDecisionDao, pendingPhotoUploadDao, syncWorkManager)
            viewModel.syncNow()
            verify(syncWorkManager).enqueueSync()
        }

    @Test
    fun `deleteDecision calls dao`() =
        runTest(testDispatcher) {
            viewModel = SyncViewModel(localDecisionDao, pendingPhotoUploadDao, syncWorkManager)
            viewModel.deleteDecision(42L)
            testDispatcher.scheduler.advanceUntilIdle()
            verify(localDecisionDao).deleteByInferenceResultId(42L)
        }

    @Test
    fun `deletePhoto calls dao`() =
        runTest(testDispatcher) {
            viewModel = SyncViewModel(localDecisionDao, pendingPhotoUploadDao, syncWorkManager)
            viewModel.deletePhoto(9L)
            testDispatcher.scheduler.advanceUntilIdle()
            verify(pendingPhotoUploadDao).deleteByRowId(9L)
        }
}
