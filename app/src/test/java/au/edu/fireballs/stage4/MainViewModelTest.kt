package au.edu.fireballs.stage4

import au.edu.fireballs.stage4.data.local.dao.LocalDecisionDao
import au.edu.fireballs.stage4.data.local.dao.PendingPhotoUploadDao
import au.edu.fireballs.stage4.data.remote.AccountManager
import au.edu.fireballs.stage4.data.repository.SelectedSurveyRepository
import au.edu.fireballs.stage4.ui.screen.stage4map.SyncWorkManager
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val accountManager: AccountManager = mock()
    private val accountManagerLazy: Lazy<AccountManager> = mock()
    private val selectedSurveyRepository: SelectedSurveyRepository = mock()
    private val localDecisionDao: LocalDecisionDao = mock()
    private val pendingPhotoUploadDao: PendingPhotoUploadDao = mock()
    private val syncWorkManager: SyncWorkManager = mock()

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
            testDispatcher,
        )

    @Test
    fun `resumeSyncIfNeeded enqueues sync when pending rows exist`() =
        runTest(testDispatcher) {
            selectedSurveyId.value = 7L
            whenever(localDecisionDao.getUnsyncedCount(7L)).thenReturn(MutableStateFlow(2))
            whenever(pendingPhotoUploadDao.getNotUploadedCount(7L)).thenReturn(MutableStateFlow(1))
            viewModel = createViewModel()
            viewModel.resumeSyncIfNeeded()
            testDispatcher.scheduler.advanceUntilIdle()
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
            testDispatcher.scheduler.advanceUntilIdle()
            verify(syncWorkManager, never()).enqueueSync()
        }

    @Test
    fun `resumeSyncIfNeeded does not enqueue when no survey selected`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            viewModel.resumeSyncIfNeeded()
            testDispatcher.scheduler.advanceUntilIdle()
            verify(syncWorkManager, never()).enqueueSync()
        }

    @Test
    fun `pendingDecisions reflect global unsynced count`() =
        runTest(testDispatcher) {
            whenever(localDecisionDao.getAllUnsyncedCount()).thenReturn(MutableStateFlow(3))
            viewModel = createViewModel()
            var value = -1
            val job = launch { viewModel.pendingDecisions.collect { value = it } }
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(3, value)
            verify(localDecisionDao).getAllUnsyncedCount()
            job.cancel()
        }

    @Test
    fun `pendingPhotos reflect global not uploaded count`() =
        runTest(testDispatcher) {
            whenever(pendingPhotoUploadDao.getAllNotUploadedCount()).thenReturn(MutableStateFlow(4))
            viewModel = createViewModel()
            var value = -1
            val job = launch { viewModel.pendingPhotos.collect { value = it } }
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(4, value)
            verify(pendingPhotoUploadDao).getAllNotUploadedCount()
            job.cancel()
        }
}
