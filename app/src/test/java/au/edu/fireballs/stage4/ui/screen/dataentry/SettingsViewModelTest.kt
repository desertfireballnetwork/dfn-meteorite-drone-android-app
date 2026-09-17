package au.edu.fireballs.stage4.ui.screen.dataentry

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import au.edu.fireballs.stage4.data.repository.StorageCoordinator
import au.edu.fireballs.stage4.data.repository.StorageMutationState
import au.edu.fireballs.stage4.data.repository.StorageUsageSnapshot
import au.edu.fireballs.stage4.data.tiles.BufferRadiusRepository
import au.edu.fireballs.stage4.ui.util.UiText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val mutationState = MutableStateFlow<StorageMutationState>(StorageMutationState.Idle)
    private lateinit var radiusRepository: BufferRadiusRepository
    private lateinit var storageCoordinator: StorageCoordinator

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("test_settings", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        radiusRepository = BufferRadiusRepository(preferences)
        storageCoordinator = mockk()
        coEvery { storageCoordinator.state } returns mutationState
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial load maps populated snapshot in fixed order`() =
        runTest {
            coEvery { storageCoordinator.snapshot() } returns populatedSnapshot()
            val viewModel = createViewModel()

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
            val viewModel = createViewModel()

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
            val viewModel = createViewModel()

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
            val viewModel = createViewModel()

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
            val viewModel = createViewModel()
            advanceUntilIdle()
            val stale = viewModel.uiState.value.storage.displayModel
            coEvery { storageCoordinator.snapshot() } throws IllegalStateException()

            viewModel.retryStorage()
            advanceUntilIdle()

            assertEquals(stale, viewModel.uiState.value.storage.displayModel)
            assertNotNull(viewModel.uiState.value.storage.error)
            coEvery { storageCoordinator.snapshot() } returns snapshot(geotiffBytes = 9 * ONE_MB)

            viewModel.retryStorage()
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
            createViewModel()
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
            val viewModel = createViewModel()
            dispatcher.scheduler.runCurrent()
            coVerify(exactly = 1) { storageCoordinator.snapshot() }

            viewModel.retryStorage()
            viewModel.retryStorage()
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
            val viewModel = createViewModel()
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

    private fun createViewModel() = SettingsViewModel(radiusRepository, storageCoordinator)

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
