package au.edu.fireballs.stage4

import au.edu.fireballs.stage4.data.local.dao.LocalDecisionDao
import au.edu.fireballs.stage4.data.local.dao.PendingPhotoUploadDao
import au.edu.fireballs.stage4.data.remote.AccountManager
import au.edu.fireballs.stage4.data.repository.DurableSyncStatus
import au.edu.fireballs.stage4.data.repository.SelectedSurveyRepository
import au.edu.fireballs.stage4.data.repository.SyncCompletion
import au.edu.fireballs.stage4.data.repository.SyncPhase
import au.edu.fireballs.stage4.data.repository.SyncProgress
import au.edu.fireballs.stage4.data.repository.SyncStatusSource
import au.edu.fireballs.stage4.ui.screen.stage4map.SyncWorkManager
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val accountManager: AccountManager = mock()
    private val accountManagerLazy: Lazy<AccountManager> = mock()
    private val selectedSurveyRepository: SelectedSurveyRepository = mock()
    private val localDecisionDao: LocalDecisionDao = mock()
    private val pendingPhotoUploadDao: PendingPhotoUploadDao = mock()
    private val syncWorkManager: SyncWorkManager = mock()
    private val syncStatusSource = FakeSyncStatusSource()

    private val selectedSurveyId = MutableStateFlow<Long?>(null)

    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        whenever(accountManagerLazy.get()).thenReturn(accountManager)
        whenever(accountManager.isSignedIn()).thenReturn(false)
        whenever(selectedSurveyRepository.selectedSurveyId).thenReturn(selectedSurveyId)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): MainViewModel =
        MainViewModel(
            accountManagerLazy,
            selectedSurveyRepository,
            localDecisionDao,
            pendingPhotoUploadDao,
            syncWorkManager,
            syncStatusSource,
            testDispatcher,
        )

    private fun TestScope.collectGlobalStates(): Pair<MutableList<GlobalSyncState>, Job> {
        val states = mutableListOf<GlobalSyncState>()
        val job = launch { viewModel.globalSyncState.collect { states += it } }
        advanceUntilIdle()
        return states to job
    }

    @Test
    fun `resumeSyncIfNeeded enqueues sync when pending rows exist`() =
        runTest(testDispatcher) {
            selectedSurveyId.value = 7L
            whenever(localDecisionDao.getUnsyncedCount(7L)).thenReturn(MutableStateFlow(2))
            whenever(pendingPhotoUploadDao.getNotUploadedCount(7L)).thenReturn(MutableStateFlow(1))
            viewModel = createViewModel()
            viewModel.resumeSyncIfNeeded()
            advanceUntilIdle()
            verify(syncWorkManager).enqueueSync()
        }

    @Test
    fun `resumeSyncIfNeeded does not enqueue when no pending rows`() =
        runTest(testDispatcher) {
            selectedSurveyId.value = 7L
            whenever(localDecisionDao.getUnsyncedCount(7L)).thenReturn(MutableStateFlow(0))
            whenever(pendingPhotoUploadDao.getNotUploadedCount(7L)).thenReturn(MutableStateFlow(0))
            viewModel = createViewModel()
            viewModel.resumeSyncIfNeeded()
            advanceUntilIdle()
            verify(syncWorkManager, never()).enqueueSync()
        }

    @Test
    fun `resumeSyncIfNeeded does not enqueue when no survey selected`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            viewModel.resumeSyncIfNeeded()
            advanceUntilIdle()
            verify(syncWorkManager, never()).enqueueSync()
        }

    @Test
    fun `idle durable status maps to hidden global surface`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            val (states, job) = collectGlobalStates()
            syncStatusSource.statusFlow.value = DurableSyncStatus.Idle
            advanceUntilIdle()
            assertEquals(GlobalSyncState.Hidden, states.last())
            job.cancel()
        }

    @Test
    fun `pending durable status maps counts to global surface`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            val (states, job) = collectGlobalStates()
            syncStatusSource.statusFlow.value = DurableSyncStatus.Pending(3, 2)
            advanceUntilIdle()
            assertEquals(GlobalSyncState.Pending(3, 2), states.last())
            job.cancel()
        }

    @Test
    fun `waiting durable status maps to waiting global surface`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            val (states, job) = collectGlobalStates()
            syncStatusSource.statusFlow.value =
                DurableSyncStatus.WaitingForNetwork(UUID.randomUUID(), 1, 1)
            advanceUntilIdle()
            assertEquals(GlobalSyncState.WaitingForNetwork, states.last())
            job.cancel()
        }

    @Test
    fun `running durable status maps progress to global surface`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            val (states, job) = collectGlobalStates()
            val progress = SyncProgress(SyncPhase.Photo, done = 3, total = 10)
            syncStatusSource.statusFlow.value =
                DurableSyncStatus.Running(UUID.randomUUID(), progress)
            advanceUntilIdle()
            assertEquals(GlobalSyncState.Running(progress), states.last())
            job.cancel()
        }

    @Test
    fun `resuming durable status maps to resuming global surface`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            val (states, job) = collectGlobalStates()
            syncStatusSource.statusFlow.value = DurableSyncStatus.Resuming(UUID.randomUUID())
            advanceUntilIdle()
            assertEquals(GlobalSyncState.Resuming, states.last())
            job.cancel()
        }

    @Test
    fun `complete durable status hides the persistent global surface`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            val (states, job) = collectGlobalStates()
            syncStatusSource.statusFlow.value = DurableSyncStatus.Complete(UUID.randomUUID())
            advanceUntilIdle()
            assertEquals(GlobalSyncState.Hidden, states.last())
            job.cancel()
        }

    @Test
    fun `failed durable status maps to failed global surface`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            val (states, job) = collectGlobalStates()
            syncStatusSource.statusFlow.value = DurableSyncStatus.Failed(UUID.randomUUID(), "boom")
            advanceUntilIdle()
            assertEquals(GlobalSyncState.Failed, states.last())
            job.cancel()
        }

    @Test
    fun `session expired durable status maps to session expired surface`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            val (states, job) = collectGlobalStates()
            syncStatusSource.statusFlow.value = DurableSyncStatus.SessionExpired(UUID.randomUUID())
            advanceUntilIdle()
            assertEquals(GlobalSyncState.SessionExpired, states.last())
            job.cancel()
        }

    @Test
    fun `completion event is forwarded once and not repeated after recreation`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            val received = mutableListOf<Unit>()
            val first = launch { viewModel.syncCompletionEvents.collect { received += it } }
            advanceUntilIdle()
            syncStatusSource.emitCompletion(SyncCompletion(UUID.randomUUID()))
            advanceUntilIdle()
            assertEquals(1, received.size)
            first.cancel()
            val second = launch { viewModel.syncCompletionEvents.collect { received += it } }
            advanceUntilIdle()
            assertEquals(1, received.size)
            second.cancel()
        }

    @Test
    fun `distinct later completion is forwarded as a new event`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            val received = mutableListOf<Unit>()
            val job = launch { viewModel.syncCompletionEvents.collect { received += it } }
            advanceUntilIdle()
            syncStatusSource.emitCompletion(SyncCompletion(UUID.randomUUID()))
            syncStatusSource.emitCompletion(SyncCompletion(UUID.randomUUID()))
            advanceUntilIdle()
            assertEquals(2, received.size)
            job.cancel()
        }

    private class FakeSyncStatusSource : SyncStatusSource {
        val statusFlow = MutableStateFlow<DurableSyncStatus>(DurableSyncStatus.Idle)
        private val completionFlow = MutableSharedFlow<SyncCompletion>(extraBufferCapacity = 4)

        override val status: StateFlow<DurableSyncStatus> = statusFlow.asStateFlow()

        override val completions: Flow<SyncCompletion> = completionFlow.asSharedFlow()

        suspend fun emitCompletion(completion: SyncCompletion) {
            completionFlow.emit(completion)
        }
    }
}
