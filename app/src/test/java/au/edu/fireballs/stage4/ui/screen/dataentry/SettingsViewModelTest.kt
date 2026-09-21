package au.edu.fireballs.stage4.ui.screen.dataentry

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import au.edu.fireballs.stage4.data.remote.AccountManager
import au.edu.fireballs.stage4.data.repository.DurableSyncStatus
import au.edu.fireballs.stage4.data.repository.SelectedSurveyRepository
import au.edu.fireballs.stage4.data.repository.StorageClearCategory
import au.edu.fireballs.stage4.data.repository.StorageClearRepository
import au.edu.fireballs.stage4.data.repository.StorageClearResult
import au.edu.fireballs.stage4.data.repository.StorageCoordinator
import au.edu.fireballs.stage4.data.repository.StorageMutationState
import au.edu.fireballs.stage4.data.repository.StorageUsageSnapshot
import au.edu.fireballs.stage4.data.repository.SyncStatusSource
import au.edu.fireballs.stage4.data.tiles.BufferRadiusRepository
import au.edu.fireballs.stage4.ui.util.UiText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val mutationState = MutableStateFlow<StorageMutationState>(StorageMutationState.Idle)
    private lateinit var radiusRepository: BufferRadiusRepository
    private lateinit var storageCoordinator: StorageCoordinator
    private lateinit var storageClearRepository: StorageClearRepository
    private lateinit var syncStatusSource: SyncStatusSource
    private lateinit var accountManager: AccountManager
    private lateinit var selectedSurveyRepository: SelectedSurveyRepository
    private val syncStatus = MutableStateFlow<DurableSyncStatus>(DurableSyncStatus.Idle)
    private val usernameFlow = MutableStateFlow<String?>(null)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("test_settings", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        radiusRepository = BufferRadiusRepository(preferences)
        storageCoordinator = mockk()
        coEvery { storageCoordinator.state } returns mutationState
        storageClearRepository = mockk()
        syncStatusSource = mockk()
        coEvery { syncStatusSource.status } returns syncStatus
        accountManager = mockk()
        selectedSurveyRepository = mockk()
        coEvery { selectedSurveyRepository.username } returns usernameFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial load maps populated snapshot in fixed order`() =
        runTest {
            coEvery { storageCoordinator.snapshot() } returns populatedSnapshot()
            val viewModel = createEnteredViewModel()

            advanceUntilIdle()

            val storage = viewModel.uiState.value.storage
            assertFalse(storage.isLoading)
            assertNull(storage.error)
            val rows = requireNotNull(storage.displayModel).rows
            assertEquals(
                listOf(
                    "Device free space",
                    "Known cached downloads",
                    "GeoTIFF tiles",
                    "Candidate crops",
                    "Satellite maps",
                    "Evidence photos",
                    "Temporary cache",
                ),
                rows.map { it.category },
            )
            assertEquals("2 GB free of 8 GB", rows[0].value)
            assertEquals("6 MB", rows[1].value)
            assertEquals("1 MB", rows[2].value)
            assertEquals("2 MB", rows[3].value)
            assertEquals("3 MB", rows[4].value)
            assertEquals("2 regions", rows[4].status)
            assertEquals("4 MB", rows[5].value)
            assertEquals("Preserved", rows[5].status)
            assertEquals("5 MB", rows[6].value)
            coVerify(exactly = 1) { storageCoordinator.snapshot() }
        }

    @Test
    fun `unavailable satellite uses count and excludes unknown bytes from known total`() =
        runTest {
            coEvery { storageCoordinator.snapshot() } returns
                snapshot(
                    geotiffBytes = ONE_MB,
                    candidateCropBytes = 2 * ONE_MB,
                    mapboxRegionCount = 1,
                    mapboxBytes = null,
                )
            val viewModel = createEnteredViewModel()

            advanceUntilIdle()

            val rows = requireNotNull(viewModel.uiState.value.storage.displayModel).rows
            assertEquals("3 MB", rows[1].value)
            assertEquals("1 region", rows[4].value)
            assertEquals("Size unavailable", rows[4].status)
        }

    @Test
    fun `zero values remain visible and plural satellite count is shown`() =
        runTest {
            coEvery { storageCoordinator.snapshot() } returns snapshot()
            val viewModel = createEnteredViewModel()

            advanceUntilIdle()

            val rows = requireNotNull(viewModel.uiState.value.storage.displayModel).rows
            assertEquals(7, rows.size)
            assertEquals("0 B free of 0 B", rows[0].value)
            assertEquals("0 B", rows[1].value)
            assertEquals("0 regions", rows[4].value)
            assertEquals("Size unavailable", rows[4].status)
            assertEquals("0 B", rows[5].value)
            assertEquals("Preserved", rows[5].status)
            assertEquals("0 B", rows[6].value)
        }

    @Test
    fun `formatter covers binary boundaries and clamps negative input`() {
        assertEquals("0 B", formatSettingsBytes(-1))
        assertEquals("1023 B", formatSettingsBytes(1023))
        assertEquals("1 KB", formatSettingsBytes(1024))
        assertEquals("1.5 KB", formatSettingsBytes(1536))
        assertEquals("1 MB", formatSettingsBytes(1024L * 1024))
        assertEquals("1 GB", formatSettingsBytes(1024L * 1024 * 1024))
        assertEquals("1 TB", formatSettingsBytes(1024L * 1024 * 1024 * 1024))
    }

    @Test
    fun `initial failure has recoverable error and no invented model`() =
        runTest {
            coEvery { storageCoordinator.snapshot() } throws IllegalStateException()
            val viewModel = createEnteredViewModel()

            advanceUntilIdle()

            val storage = viewModel.uiState.value.storage
            assertFalse(storage.isLoading)
            assertNull(storage.displayModel)
            assertEquals(
                "Could not calculate storage usage",
                (storage.error as UiText.DynamicString).value,
            )
        }

    @Test
    fun `refresh failure retains stale model and retry recovers`() =
        runTest {
            coEvery { storageCoordinator.snapshot() } returns populatedSnapshot()
            val viewModel = createEnteredViewModel()
            advanceUntilIdle()
            val stale = viewModel.uiState.value.storage.displayModel
            coEvery { storageCoordinator.snapshot() } throws IllegalStateException()

            viewModel.refreshStorage()
            advanceUntilIdle()

            assertEquals(stale, viewModel.uiState.value.storage.displayModel)
            assertNotNull(viewModel.uiState.value.storage.error)
            coEvery { storageCoordinator.snapshot() } returns snapshot(geotiffBytes = 9 * ONE_MB)

            viewModel.refreshStorage()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.storage.error)
            assertEquals(
                "9 MB",
                requireNotNull(viewModel.uiState.value.storage.displayModel).rows[2].value,
            )
            coVerify(exactly = 3) { storageCoordinator.snapshot() }
        }

    @Test
    fun `only mutation completion transitions refresh`() =
        runTest {
            coEvery { storageCoordinator.snapshot() } returns snapshot()
            createEnteredViewModel()
            advanceUntilIdle()
            coVerify(exactly = 1) { storageCoordinator.snapshot() }

            mutationState.value = StorageMutationState.Downloading
            advanceUntilIdle()
            coVerify(exactly = 1) { storageCoordinator.snapshot() }
            mutationState.value = StorageMutationState.Idle
            advanceUntilIdle()
            coVerify(exactly = 2) { storageCoordinator.snapshot() }

            mutationState.value = StorageMutationState.Clearing
            advanceUntilIdle()
            mutationState.value = StorageMutationState.Idle
            advanceUntilIdle()
            coVerify(exactly = 3) { storageCoordinator.snapshot() }

            mutationState.value = StorageMutationState.Idle
            advanceUntilIdle()
            coVerify(exactly = 3) { storageCoordinator.snapshot() }
        }

    @Test
    fun `overlapping triggers coalesce to one follow-up`() =
        runTest {
            val first = CompletableDeferred<StorageUsageSnapshot>()
            coEvery { storageCoordinator.snapshot() } coAnswers {
                if (!first.isCompleted) first.await() else snapshot()
            }
            val viewModel = createEnteredViewModel()
            dispatcher.scheduler.runCurrent()
            coVerify(exactly = 1) { storageCoordinator.snapshot() }

            viewModel.refreshStorage()
            viewModel.refreshStorage()
            mutationState.value = StorageMutationState.Downloading
            dispatcher.scheduler.runCurrent()
            mutationState.value = StorageMutationState.Idle
            dispatcher.scheduler.runCurrent()
            coVerify(exactly = 1) { storageCoordinator.snapshot() }

            first.complete(snapshot())
            advanceUntilIdle()

            coVerify(exactly = 2) { storageCoordinator.snapshot() }
        }

    @Test
    fun `radius editing saving and loading remain unchanged`() =
        runTest {
            coEvery { storageCoordinator.snapshot() } returns snapshot()
            val viewModel = createEnteredViewModel()
            assertEquals(100.0f, viewModel.uiState.value.currentRadius)
            assertEquals("100", viewModel.uiState.value.inputText)

            viewModel.onRadiusInput("0")
            viewModel.save()
            assertEquals(100.0f, radiusRepository.getBufferRadiusMeters())
            assertFalse(viewModel.uiState.value.saved)

            viewModel.onRadiusInput("250")
            viewModel.save()
            assertEquals(250.0f, radiusRepository.getBufferRadiusMeters())
            assertTrue(viewModel.uiState.value.saved)

            radiusRepository.setBufferRadiusMeters(300.5f)
            viewModel.load()
            assertEquals("300.5", viewModel.uiState.value.inputText)
            assertEquals(300.5f, viewModel.uiState.value.currentRadius)
        }

    @Test
    fun `construction does not load and screen entry refreshes`() =
        runTest {
            coEvery { storageCoordinator.snapshot() } returns snapshot()
            val viewModel =
                SettingsViewModel(
                    radiusRepository,
                    storageCoordinator,
                    storageClearRepository,
                    syncStatusSource,
                    accountManager,
                    selectedSurveyRepository,
                )
            advanceUntilIdle()
            coVerify(exactly = 0) { storageCoordinator.snapshot() }

            viewModel.refreshStorage()
            advanceUntilIdle()
            coVerify(exactly = 1) { storageCoordinator.snapshot() }
        }

    @Test
    fun `clear gating follows mutations and durable sync status`() =
        runTest {
            coEvery { storageCoordinator.snapshot() } returns snapshot()
            val viewModel = createEnteredViewModel()
            advanceUntilIdle()

            val gatedMutations =
                listOf(
                    StorageMutationState.Downloading,
                    StorageMutationState.Clearing,
                )
            gatedMutations.forEach { state ->
                mutationState.value = state
                advanceUntilIdle()
                assertFalse(viewModel.uiState.value.clear.clearEnabled)
            }

            mutationState.value = StorageMutationState.Idle
            val workId = UUID.randomUUID()
            val gatedStatuses =
                listOf(
                    DurableSyncStatus.Running(workId, null),
                    DurableSyncStatus.Resuming(workId),
                    DurableSyncStatus.WaitingForNetwork(workId, 1, 1),
                )
            gatedStatuses.forEach { status ->
                syncStatus.value = status
                advanceUntilIdle()
                assertFalse(viewModel.uiState.value.clear.clearEnabled)
            }

            val enabledStatuses =
                listOf(
                    DurableSyncStatus.Idle,
                    DurableSyncStatus.Pending(1, 1),
                    DurableSyncStatus.Complete(workId),
                    DurableSyncStatus.Failed(workId, "failed"),
                    DurableSyncStatus.SessionExpired(workId),
                )
            enabledStatuses.forEach { status ->
                syncStatus.value = status
                advanceUntilIdle()
                assertTrue(viewModel.uiState.value.clear.clearEnabled)
            }
        }

    @Test
    fun `temporary cache bypasses confirmation and other categories require it`() =
        runTest {
            coEvery { storageCoordinator.snapshot() } returns snapshot()
            coEvery {
                storageClearRepository.clear(StorageClearCategory.TemporaryCache)
            } returns StorageClearResult.Cleared(StorageClearCategory.TemporaryCache)
            val viewModel = createEnteredViewModel()
            advanceUntilIdle()

            viewModel.onClearRequested(StorageClearCategory.TemporaryCache)
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.clear.pendingConfirmation)
            coVerify(exactly = 1) {
                storageClearRepository.clear(StorageClearCategory.TemporaryCache)
            }

            viewModel.onClearRequested(StorageClearCategory.GeotiffTiles)

            assertEquals(
                StorageClearCategory.GeotiffTiles,
                viewModel.uiState.value.clear.pendingConfirmation,
            )
            coVerify(exactly = 0) {
                storageClearRepository.clear(StorageClearCategory.GeotiffTiles)
            }
        }

    @Test
    fun `cancelling confirmation performs no clear mutation`() =
        runTest {
            coEvery { storageCoordinator.snapshot() } returns snapshot()
            val viewModel = createEnteredViewModel()
            advanceUntilIdle()

            viewModel.onClearRequested(StorageClearCategory.CandidateCrops)
            assertEquals(
                StorageClearCategory.CandidateCrops,
                viewModel.uiState.value.clear.pendingConfirmation,
            )

            viewModel.onClearCancelled()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.clear.pendingConfirmation)
            coVerify(exactly = 0) { storageClearRepository.clear(any()) }
        }

    @Test
    fun `duplicate confirmation launches clear exactly once`() =
        runTest {
            coEvery { storageCoordinator.snapshot() } returns snapshot()
            val result = CompletableDeferred<StorageClearResult>()
            coEvery {
                storageClearRepository.clear(StorageClearCategory.GeotiffTiles)
            } coAnswers { result.await() }
            val viewModel = createEnteredViewModel()
            advanceUntilIdle()

            viewModel.onClearRequested(StorageClearCategory.GeotiffTiles)
            viewModel.onClearConfirmed()
            viewModel.onClearConfirmed()
            dispatcher.scheduler.runCurrent()

            coVerify(exactly = 1) {
                storageClearRepository.clear(StorageClearCategory.GeotiffTiles)
            }
            result.complete(
                StorageClearResult.Cleared(StorageClearCategory.GeotiffTiles),
            )
            advanceUntilIdle()
            coVerify(exactly = 1) {
                storageClearRepository.clear(StorageClearCategory.GeotiffTiles)
            }
        }

    @Test
    fun `storage refreshes after complete partial and failed clear results`() =
        runTest {
            coEvery { storageCoordinator.snapshot() } returns snapshot()
            val results =
                listOf<StorageClearResult>(
                    StorageClearResult.Cleared(StorageClearCategory.GeotiffTiles),
                    StorageClearResult.PartiallyCleared(
                        StorageClearCategory.SatelliteMaps,
                        1,
                    ),
                    StorageClearResult.Failed(
                        StorageClearCategory.CandidateCrops,
                        "failed",
                    ),
                )

            results.forEachIndexed { index, result ->
                coEvery {
                    storageClearRepository.clear(result.category)
                } returns result
                val viewModel = createEnteredViewModel()
                advanceUntilIdle()
                viewModel.onClearRequested(result.category)
                if (result.category != StorageClearCategory.TemporaryCache) {
                    viewModel.onClearConfirmed()
                }
                advanceUntilIdle()

                assertEquals(result, viewModel.uiState.value.clear.lastResult)
                coVerify(exactly = (index + 1) * 2) {
                    storageCoordinator.snapshot()
                }
            }
        }

    @Test
    fun `logout requested updates state and confirmed executes cleanup and event`() =
        runTest {
            coEvery { storageCoordinator.snapshot() } returns snapshot()
            coEvery { selectedSurveyRepository.clear() } just runs
            coEvery { accountManager.logout() } just runs
            val viewModel = createEnteredViewModel()
            advanceUntilIdle()

            viewModel.onLogoutRequested()
            assertTrue(viewModel.uiState.value.showLogoutConfirmation)

            val eventReceived = CompletableDeferred<Unit>()
            val job =
                launch {
                    viewModel.logoutEvent.collect { eventReceived.complete(Unit) }
                }

            viewModel.onLogoutConfirmed()
            assertTrue(viewModel.uiState.value.isLoggingOut)
            assertFalse(viewModel.uiState.value.showLogoutConfirmation)

            advanceUntilIdle()

            coVerify(exactly = 1) { selectedSurveyRepository.clear() }
            coVerify(exactly = 1) { accountManager.logout() }
            assertTrue(eventReceived.isCompleted)
            assertFalse(viewModel.uiState.value.isLoggingOut)

            job.cancel()
        }

    @Test
    fun `logout cancelled resets state`() =
        runTest {
            coEvery { storageCoordinator.snapshot() } returns snapshot()
            val viewModel = createEnteredViewModel()
            advanceUntilIdle()

            viewModel.onLogoutRequested()
            assertTrue(viewModel.uiState.value.showLogoutConfirmation)

            viewModel.onLogoutCancelled()
            assertFalse(viewModel.uiState.value.showLogoutConfirmation)
            coVerify(exactly = 0) { accountManager.logout() }
        }

    @Test
    fun `username is observed from repository and updates UI state`() =
        runTest {
            coEvery { storageCoordinator.snapshot() } returns snapshot()
            usernameFlow.value = "initial_user"
            val viewModel = createEnteredViewModel()
            advanceUntilIdle()

            assertEquals("initial_user", viewModel.uiState.value.username)

            usernameFlow.value = "updated_user"
            advanceUntilIdle()

            assertEquals("updated_user", viewModel.uiState.value.username)
        }

    private fun createEnteredViewModel() =
        SettingsViewModel(
            radiusRepository,
            storageCoordinator,
            storageClearRepository,
            syncStatusSource,
            accountManager,
            selectedSurveyRepository,
        ).also {
            it.refreshStorage()
        }

    private fun populatedSnapshot() =
        snapshot(
            availableVolumeBytes = 2L * 1024 * 1024 * 1024,
            totalVolumeBytes = 8L * 1024 * 1024 * 1024,
            geotiffBytes = ONE_MB,
            candidateCropBytes = 2 * ONE_MB,
            mapboxRegionCount = 2,
            mapboxBytes = 3 * ONE_MB,
            evidenceBytes = 4 * ONE_MB,
            ownedTempCacheBytes = 5 * ONE_MB,
        )

    private fun snapshot(
        availableVolumeBytes: Long = 0,
        totalVolumeBytes: Long = 0,
        geotiffBytes: Long = 0,
        candidateCropBytes: Long = 0,
        evidenceBytes: Long = 0,
        ownedTempCacheBytes: Long = 0,
        mapboxRegionCount: Int = 0,
        mapboxBytes: Long? = null,
    ) = StorageUsageSnapshot(
        availableVolumeBytes = availableVolumeBytes,
        totalVolumeBytes = totalVolumeBytes,
        geotiffBytes = geotiffBytes,
        candidateCropBytes = candidateCropBytes,
        evidenceBytes = evidenceBytes,
        ownedTempCacheBytes = ownedTempCacheBytes,
        mapboxRegionCount = mapboxRegionCount,
        mapboxBytes = mapboxBytes,
    )

    private companion object {
        const val ONE_MB = 1024L * 1024
    }
}
