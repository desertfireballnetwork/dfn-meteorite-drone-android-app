package au.edu.fireballs.stage4.data.repository

import au.edu.fireballs.stage4.data.tiles.OfflineRegionInventory
import au.edu.fireballs.stage4.data.tiles.OfflineRegionWrapper
import au.edu.fireballs.stage4.data.tiles.TileStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import java.nio.file.Files

class StorageCoordinatorTest {
    @Test
    fun knownCachedDownloadBytesIncludesGeotiffsAndCrops() {
        val snapshot = storageUsageSnapshot(geotiffBytes = 12L, candidateCropBytes = 8L)

        assertEquals(20L, snapshot.knownCachedDownloadBytes)
    }

    @Test
    fun knownCachedDownloadBytesIncludesMeasuredMapboxBytes() {
        val snapshot = storageUsageSnapshot(mapboxBytes = 7L)

        assertEquals(7L, snapshot.knownCachedDownloadBytes)
    }

    @Test
    fun knownCachedDownloadBytesDoesNotInventUnavailableMapboxBytes() {
        val snapshot = storageUsageSnapshot(mapboxBytes = null)

        assertEquals(0L, snapshot.knownCachedDownloadBytes)
    }

    @Test
    fun knownCachedDownloadBytesExcludesEvidenceAndOwnedTemporaryCache() {
        val snapshot = storageUsageSnapshot(evidenceBytes = 13L, ownedTempCacheBytes = 17L)

        assertEquals(0L, snapshot.knownCachedDownloadBytes)
    }

    private fun storageUsageSnapshot(
        geotiffBytes: Long = 0L,
        candidateCropBytes: Long = 0L,
        evidenceBytes: Long = 0L,
        ownedTempCacheBytes: Long = 0L,
        mapboxBytes: Long? = null,
    ) = StorageUsageSnapshot(
        availableVolumeBytes = 0L,
        totalVolumeBytes = 0L,
        geotiffBytes = geotiffBytes,
        candidateCropBytes = candidateCropBytes,
        evidenceBytes = evidenceBytes,
        ownedTempCacheBytes = ownedTempCacheBytes,
        mapboxRegionCount = 0,
        mapboxBytes = mapboxBytes,
    )

    private val dispatcher = UnconfinedTestDispatcher()
    private val root = Files.createTempDirectory("storage-coordinator").toFile()
    private val tileRoot = root.resolve("tiles")
    private val cropRoot = root.resolve("crops")
    private val evidenceRoot = root.resolve("evidence")
    private val tempRoot = root.resolve("owned-temp")
    private val tileStore = TileStore(tileRoot)
    private val offlineRegions =
        mock<OfflineRegionWrapper> {
            on { inventory(any()) } doAnswer { invocation ->
                val callback =
                    invocation.getArgument<(Result<OfflineRegionInventory>) -> Unit>(0)
                callback(Result.success(OfflineRegionInventory(0, null)))
                Unit
            }
        }

    @Test
    fun initialStateIsIdleAndReadOnly() {
        val coordinator = coordinator()

        assertSame(StorageMutationState.Idle, coordinator.state.value)
    }

    @Test
    fun downloadAndClearLeasesPublishStatesAndRestoreIdle() =
        runTest(dispatcher) {
            val coordinator = coordinator()
            val states = mutableListOf<StorageMutationState>()
            val collector = launch { coordinator.state.toList(states) }

            coordinator.withDownloadLease {
                assertSame(StorageMutationState.Downloading, coordinator.state.value)
            }
            coordinator.withClearLease {
                assertSame(StorageMutationState.Clearing, coordinator.state.value)
            }
            collector.cancelAndJoin()

            assertEquals(
                listOf(
                    StorageMutationState.Idle,
                    StorageMutationState.Downloading,
                    StorageMutationState.Idle,
                    StorageMutationState.Clearing,
                    StorageMutationState.Idle,
                ),
                states,
            )
        }

    @Test
    fun exceptionRestoresIdleAndEscapesUnchanged() =
        runTest(dispatcher) {
            val coordinator = coordinator()
            val expected = IllegalStateException("boom")

            val actual =
                runCatching {
                    coordinator.withClearLease { throw expected }
                }.exceptionOrNull()

            assertSame(expected, actual)
            assertSame(StorageMutationState.Idle, coordinator.state.value)
        }

    @Test
    fun cancellationRestoresIdle() =
        runTest(dispatcher) {
            val coordinator = coordinator()
            val entered = CompletableDeferred<Unit>()
            val job =
                launch {
                    coordinator.withDownloadLease {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
            entered.await()

            job.cancelAndJoin()

            assertSame(StorageMutationState.Idle, coordinator.state.value)
        }

    @Test
    fun clearWaitsForDownloadAndWaitingStateDoesNotLeak() =
        runTest(dispatcher) {
            val coordinator = coordinator()
            val release = CompletableDeferred<Unit>()
            val downloadEntered = CompletableDeferred<Unit>()
            var clearEntered = false
            val download =
                launch {
                    coordinator.withDownloadLease {
                        downloadEntered.complete(Unit)
                        release.await()
                    }
                }
            downloadEntered.await()
            val clear =
                launch {
                    coordinator.withClearLease {
                        clearEntered = true
                    }
                }

            assertFalse(clearEntered)
            assertSame(StorageMutationState.Downloading, coordinator.state.value)
            release.complete(Unit)
            download.join()
            clear.join()

            assertTrue(clearEntered)
            assertSame(StorageMutationState.Idle, coordinator.state.value)
        }

    @Test
    fun sameKindLeasesDoNotOverlap() =
        runTest(dispatcher) {
            val coordinator = coordinator()
            val release = CompletableDeferred<Unit>()
            val firstEntered = CompletableDeferred<Unit>()
            var secondEntered = false
            val first =
                launch {
                    coordinator.withDownloadLease {
                        firstEntered.complete(Unit)
                        release.await()
                    }
                }
            firstEntered.await()
            val second =
                launch {
                    coordinator.withDownloadLease {
                        secondEntered = true
                    }
                }

            assertFalse(secondEntered)
            release.complete(Unit)
            first.join()
            second.join()

            assertTrue(secondEntered)
        }

    @Test
    fun cancelledWaitingLeaseDoesNotRunOrChangeActiveState() =
        runTest(dispatcher) {
            val coordinator = coordinator()
            val release = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val active =
                launch {
                    coordinator.withClearLease {
                        entered.complete(Unit)
                        release.await()
                    }
                }
            entered.await()
            var waitingRan = false
            val waiting =
                launch {
                    coordinator.withDownloadLease {
                        waitingRan = true
                    }
                }

            waiting.cancelAndJoin()

            assertFalse(waitingRan)
            assertSame(StorageMutationState.Clearing, coordinator.state.value)
            release.complete(Unit)
            active.join()
            assertSame(StorageMutationState.Idle, coordinator.state.value)
        }

    @Test
    fun snapshotWaitsForMutationAndDoesNotChangeState() =
        runTest(dispatcher) {
            val measured = CompletableDeferred<Unit>()
            val coordinator =
                coordinator(
                    volumeStatsReader =
                        VolumeStatsReader {
                            measured.complete(Unit)
                            VolumeStats(10, 20)
                        },
                )
            val release = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val mutation =
                launch {
                    coordinator.withDownloadLease {
                        entered.complete(Unit)
                        release.await()
                    }
                }
            entered.await()
            val snapshot = async { coordinator.snapshot() }

            assertFalse(measured.isCompleted)
            assertSame(StorageMutationState.Downloading, coordinator.state.value)
            release.complete(Unit)
            mutation.join()

            assertEquals(10L, snapshot.await().availableVolumeBytes)
            assertSame(StorageMutationState.Idle, coordinator.state.value)
        }

    @Test
    fun mutationWaitsForSnapshotMeasurement() =
        runTest(dispatcher) {
            val measurementEntered = CompletableDeferred<Unit>()
            val releaseMeasurement = CompletableDeferred<Unit>()
            val coordinator =
                coordinator(
                    volumeStatsReader =
                        VolumeStatsReader {
                            measurementEntered.complete(Unit)
                            releaseMeasurement.await()
                            VolumeStats(10, 20)
                        },
                )
            val snapshot = async { coordinator.snapshot() }
            measurementEntered.await()
            var mutationEntered = false
            val mutation =
                launch {
                    coordinator.withClearLease {
                        mutationEntered = true
                    }
                }

            assertFalse(mutationEntered)
            assertSame(StorageMutationState.Idle, coordinator.state.value)
            releaseMeasurement.complete(Unit)
            snapshot.await()
            mutation.join()

            assertTrue(mutationEntered)
            assertSame(StorageMutationState.Idle, coordinator.state.value)
        }

    @Test
    fun snapshotAssemblesNonOverlappingMeasuredCategories() =
        runTest(dispatcher) {
            tileStore.write(1, 2, 3, 4, 5, ByteArray(7))
            tileRoot.resolve("1/2/3/4/orphan.tmp").writeBytes(ByteArray(11))
            cropRoot.mkdirs()
            cropRoot.resolve("crop.jpg").writeBytes(ByteArray(13))
            evidenceRoot.mkdirs()
            evidenceRoot.resolve("photo.jpg").writeBytes(ByteArray(17))
            tempRoot.mkdirs()
            tempRoot.resolve("owned.part").writeBytes(ByteArray(19))
            val regions =
                mock<OfflineRegionWrapper> {
                    on { inventory(any()) } doAnswer { invocation ->
                        val callback =
                            invocation
                                .getArgument<(Result<OfflineRegionInventory>) -> Unit>(0)
                        callback(Result.success(OfflineRegionInventory(3, null)))
                        Unit
                    }
                }
            val coordinator =
                coordinator(
                    regions = regions,
                    volumeStatsReader = VolumeStatsReader { VolumeStats(23, 29) },
                )

            val snapshot = coordinator.snapshot()

            assertEquals(23L, snapshot.availableVolumeBytes)
            assertEquals(29L, snapshot.totalVolumeBytes)
            assertEquals(7L, snapshot.geotiffBytes)
            assertEquals(13L, snapshot.candidateCropBytes)
            assertEquals(17L, snapshot.evidenceBytes)
            assertEquals(30L, snapshot.ownedTempCacheBytes)
            assertEquals(3, snapshot.mapboxRegionCount)
            assertEquals(null, snapshot.mapboxBytes)
        }

    @Test
    fun missingRootsMeasureAsZero() =
        runTest(dispatcher) {
            val missing = root.resolve("missing")
            val coordinator =
                StorageCoordinator(
                    tileStore = TileStore(missing.resolve("tiles")),
                    offlineRegionWrapper = offlineRegions,
                    appStorageRoot = root,
                    candidateCropRoot = missing.resolve("crops"),
                    evidenceRoot = missing.resolve("evidence"),
                    ownedTempCacheRoots = listOf(missing.resolve("temp")),
                    ioDispatcher = dispatcher,
                    volumeStatsReader = VolumeStatsReader { VolumeStats(1, 2) },
                )

            val snapshot = coordinator.snapshot()

            assertEquals(0L, snapshot.geotiffBytes)
            assertEquals(0L, snapshot.candidateCropBytes)
            assertEquals(0L, snapshot.evidenceBytes)
            assertEquals(0L, snapshot.ownedTempCacheBytes)
        }

    @Test
    fun snapshotFailurePropagates() =
        runTest(dispatcher) {
            val expected = IllegalArgumentException("volume failure")
            val coordinator =
                coordinator(
                    volumeStatsReader = VolumeStatsReader { throw expected },
                )

            val actual = runCatching { coordinator.snapshot() }.exceptionOrNull()

            assertTrue(actual is IllegalArgumentException)
            assertEquals(expected.message, actual?.message)
            assertSame(StorageMutationState.Idle, coordinator.state.value)
        }

    private fun coordinator(
        regions: OfflineRegionWrapper = offlineRegions,
        volumeStatsReader: VolumeStatsReader = VolumeStatsReader { VolumeStats(10, 20) },
    ): StorageCoordinator =
        StorageCoordinator(
            tileStore = tileStore,
            offlineRegionWrapper = regions,
            appStorageRoot = root,
            candidateCropRoot = cropRoot,
            evidenceRoot = evidenceRoot,
            ownedTempCacheRoots = listOf(tempRoot),
            ioDispatcher = dispatcher,
            volumeStatsReader = volumeStatsReader,
        )
}
