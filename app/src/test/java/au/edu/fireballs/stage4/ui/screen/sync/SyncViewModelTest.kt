package au.edu.fireballs.stage4.ui.screen.sync

import androidx.lifecycle.viewModelScope
import au.edu.fireballs.stage4.data.local.LocalDecisionEntity
import au.edu.fireballs.stage4.data.local.PendingPhotoUploadEntity
import au.edu.fireballs.stage4.data.local.dao.LocalDecisionDao
import au.edu.fireballs.stage4.data.local.dao.PendingPhotoUploadDao
import au.edu.fireballs.stage4.data.repository.DurableSyncStatus
import au.edu.fireballs.stage4.data.repository.SelectedSurveyRepository
import au.edu.fireballs.stage4.data.repository.SyncCompletion
import au.edu.fireballs.stage4.data.repository.SyncPhase
import au.edu.fireballs.stage4.data.repository.SyncProgress
import au.edu.fireballs.stage4.data.repository.SyncStatusSource
import au.edu.fireballs.stage4.ui.screen.stage4map.SyncWorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
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
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID

private class FakeSyncStatusSource(
    initial: DurableSyncStatus = DurableSyncStatus.Idle,
) : SyncStatusSource {
    override val status = MutableStateFlow(initial)
    override val completions = MutableSharedFlow<SyncCompletion>()
}

/**
 * SyncUiState -> test traceability matrix.
 *
 * | SyncUiState | Test |
 * |---|---|
 * | Idle | `idle when no survey selected even with pending rows`, `idle when idle status` |
 * | Pending | `pending maps durable pending` |
 * | WaitingForNetwork | `waiting for network maps durable waiting` |
 * | Running | `running maps durable running with progress` |
 * | Resuming | `resuming maps durable resuming` |
 * | Complete | `complete maps durable complete` |
 * | Failed | `failed maps durable failed` |
 * | SessionExpired | `session expired maps durable session expired` |
 * | Manual gating | `syncNow rejected while gated`, `stale manual callback rejected` |
 * | Delete gating | `delete rejected while gated`, `stale delete callback performs no mutation` |
 * | Survey scoping | `dao flows are queried with the selected survey id` |
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val localDecisionDao: LocalDecisionDao = mock()
    private val pendingPhotoUploadDao: PendingPhotoUploadDao = mock()
    private val selectedSurveyRepository: SelectedSurveyRepository = mock()
    private val syncWorkManager: SyncWorkManager = mock()
    private val syncStatusSource = FakeSyncStatusSource()

    private val selectedSurveyId = MutableStateFlow<Long?>(null)
    private val unsyncedCount = MutableStateFlow(0)
    private val notUploadedCount = MutableStateFlow(0)
    private val failedDecisions = MutableStateFlow<List<LocalDecisionEntity>>(emptyList())
    private val failedPhotos = MutableStateFlow<List<PendingPhotoUploadEntity>>(emptyList())

    private lateinit var viewModel: SyncViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        whenever(selectedSurveyRepository.selectedSurveyId).thenReturn(selectedSurveyId)
        whenever(localDecisionDao.getUnsyncedCount(any())).thenReturn(unsyncedCount)
        whenever(pendingPhotoUploadDao.getNotUploadedCount(any())).thenReturn(notUploadedCount)
        whenever(localDecisionDao.getUnsyncedFailed(any())).thenReturn(failedDecisions)
        whenever(pendingPhotoUploadDao.getNotUploadedFailed(any())).thenReturn(failedPhotos)
    }

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) {
            viewModel.viewModelScope.cancel()
        }
        Dispatchers.resetMain()
    }

    private fun createViewModel(): SyncViewModel =
        SyncViewModel(
            localDecisionDao,
            pendingPhotoUploadDao,
            selectedSurveyRepository,
            syncWorkManager,
            syncStatusSource,
        )

    private fun settle() {
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private fun workId(): UUID = UUID.randomUUID()

    private fun gatedStates(): List<DurableSyncStatus> =
        listOf(
            DurableSyncStatus.Running(workId(), SyncProgress(SyncPhase.Photo, 1, 2)),
            DurableSyncStatus.Resuming(workId()),
            DurableSyncStatus.WaitingForNetwork(workId(), 1, 1),
        )

    @Test
    fun `idle when no survey selected even with pending rows`() =
        runTest(testDispatcher) {
            syncStatusSource.status.value = DurableSyncStatus.Pending(decisions = 5, photos = 3)
            unsyncedCount.value = 5
            notUploadedCount.value = 3
            viewModel = createViewModel()
            settle()
            assertEquals(SyncUiState.Idle, viewModel.uiState.value)
        }

    @Test
    fun `idle when idle status`() =
        runTest(testDispatcher) {
            selectedSurveyId.value = 7L
            viewModel = createViewModel()
            settle()
            assertEquals(SyncUiState.Idle, viewModel.uiState.value)
        }

    @Test
    fun `pending maps durable pending`() =
        runTest(testDispatcher) {
            selectedSurveyId.value = 7L
            unsyncedCount.value = 2
            notUploadedCount.value = 1
            viewModel = createViewModel()
            syncStatusSource.status.value = DurableSyncStatus.Pending(decisions = 2, photos = 1)
            settle()
            val state = viewModel.uiState.value
            assertTrue(state is SyncUiState.Pending)
            assertEquals(2, (state as SyncUiState.Pending).summary.pendingDecisions)
            assertEquals(1, state.summary.pendingPhotos)
        }

    @Test
    fun `waiting for network maps durable waiting`() =
        runTest(testDispatcher) {
            selectedSurveyId.value = 7L
            viewModel = createViewModel()
            syncStatusSource.status.value = DurableSyncStatus.WaitingForNetwork(workId(), 1, 1)
            settle()
            assertTrue(viewModel.uiState.value is SyncUiState.WaitingForNetwork)
        }

    @Test
    fun `running maps durable running with progress`() =
        runTest(testDispatcher) {
            selectedSurveyId.value = 7L
            viewModel = createViewModel()
            val progress = SyncProgress(SyncPhase.Decision, done = 3, total = 5)
            syncStatusSource.status.value = DurableSyncStatus.Running(workId(), progress)
            settle()
            val state = viewModel.uiState.value
            assertTrue(state is SyncUiState.Running)
            assertEquals(progress, (state as SyncUiState.Running).progress)
        }

    @Test
    fun `resuming maps durable resuming`() =
        runTest(testDispatcher) {
            selectedSurveyId.value = 7L
            viewModel = createViewModel()
            syncStatusSource.status.value = DurableSyncStatus.Resuming(workId())
            settle()
            assertTrue(viewModel.uiState.value is SyncUiState.Resuming)
        }

    @Test
    fun `complete maps durable complete`() =
        runTest(testDispatcher) {
            selectedSurveyId.value = 7L
            viewModel = createViewModel()
            syncStatusSource.status.value = DurableSyncStatus.Complete(workId())
            settle()
            assertEquals(SyncUiState.Complete, viewModel.uiState.value)
        }

    @Test
    fun `failed maps durable failed`() =
        runTest(testDispatcher) {
            selectedSurveyId.value = 7L
            viewModel = createViewModel()
            syncStatusSource.status.value = DurableSyncStatus.Failed(workId(), "boom")
            settle()
            assertTrue(viewModel.uiState.value is SyncUiState.Failed)
        }

    @Test
    fun `session expired maps durable session expired`() =
        runTest(testDispatcher) {
            selectedSurveyId.value = 7L
            viewModel = createViewModel()
            syncStatusSource.status.value = DurableSyncStatus.SessionExpired(workId())
            settle()
            assertEquals(SyncUiState.SessionExpired, viewModel.uiState.value)
        }

    @Test
    fun `dao flows are queried with the selected survey id`() =
        runTest(testDispatcher) {
            selectedSurveyId.value = 7L
            viewModel = createViewModel()
            settle()
            verify(localDecisionDao).getUnsyncedCount(7L)
            verify(pendingPhotoUploadDao).getNotUploadedCount(7L)
            verify(localDecisionDao).getUnsyncedFailed(7L)
            verify(pendingPhotoUploadDao).getNotUploadedFailed(7L)
        }

    @Test
    fun `syncNow enqueues when idle`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            viewModel.syncNow()
            verify(syncWorkManager).enqueueSync()
        }

    @Test
    fun `syncNow rejected while gated`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            gatedStates().forEach { status ->
                syncStatusSource.status.value = status
                viewModel.syncNow()
            }
            verify(syncWorkManager, never()).enqueueSync()
        }

    @Test
    fun `stale manual callback rejected after transition into gated state`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            viewModel.syncNow()
            syncStatusSource.status.value = DurableSyncStatus.Running(workId(), null)
            viewModel.syncNow()
            verify(syncWorkManager).enqueueSync()
        }

    @Test
    fun `deleteDecision rejected while gated`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            gatedStates().forEach { status ->
                syncStatusSource.status.value = status
                viewModel.deleteDecision(42L)
            }
            settle()
            verify(localDecisionDao, never()).deleteByInferenceResultId(any())
        }

    @Test
    fun `deletePhoto rejected while gated`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            gatedStates().forEach { status ->
                syncStatusSource.status.value = status
                viewModel.deletePhoto(9L)
            }
            settle()
            verify(pendingPhotoUploadDao, never()).deleteByRowId(any())
        }

    @Test
    fun `stale delete callback performs no repository mutation`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            viewModel.deleteDecision(1L)
            settle()
            syncStatusSource.status.value = DurableSyncStatus.WaitingForNetwork(workId(), 1, 1)
            viewModel.deleteDecision(2L)
            settle()
            verify(localDecisionDao).deleteByInferenceResultId(1L)
            verify(localDecisionDao, never()).deleteByInferenceResultId(2L)
        }

    @Test
    fun `delete and sync succeed when status is idle`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            viewModel.deleteDecision(42L)
            viewModel.deletePhoto(9L)
            settle()
            verify(localDecisionDao).deleteByInferenceResultId(42L)
            verify(pendingPhotoUploadDao).deleteByRowId(9L)
        }
}
