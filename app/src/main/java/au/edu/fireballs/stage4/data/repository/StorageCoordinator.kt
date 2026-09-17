package au.edu.fireballs.stage4.data.repository

import android.os.StatFs
import au.edu.fireballs.stage4.data.tiles.OfflineRegionInventory
import au.edu.fireballs.stage4.data.tiles.OfflineRegionWrapper
import au.edu.fireballs.stage4.data.tiles.TileStore
import au.edu.fireballs.stage4.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface StorageMutationState {
    data object Idle : StorageMutationState

    data object Downloading : StorageMutationState

    data object Clearing : StorageMutationState
}

data class StorageUsageSnapshot(
    val availableVolumeBytes: Long,
    val totalVolumeBytes: Long,
    val geotiffBytes: Long,
    val candidateCropBytes: Long,
    val evidenceBytes: Long,
    val ownedTempCacheBytes: Long,
    val mapboxRegionCount: Int,
    val mapboxBytes: Long?,
) {
    val knownCachedDownloadBytes: Long
        get() =
            Math.addExact(
                Math.addExact(geotiffBytes, candidateCropBytes),
                mapboxBytes ?: 0L,
            )
}

internal data class VolumeStats(
    val availableBytes: Long,
    val totalBytes: Long,
)

internal fun interface VolumeStatsReader {
    suspend fun read(root: File): VolumeStats
}

internal object StatFsVolumeStatsReader : VolumeStatsReader {
    override suspend fun read(root: File): VolumeStats {
        val stats = StatFs(root.absolutePath)
        return VolumeStats(
            availableBytes = stats.availableBytes.coerceAtLeast(0L),
            totalBytes = stats.totalBytes.coerceAtLeast(0L),
        )
    }
}

@Singleton
class StorageCoordinator internal constructor(
    private val tileStore: TileStore,
    private val offlineRegionWrapper: OfflineRegionWrapper,
    private val appStorageRoot: File,
    private val candidateCropRoot: File,
    private val evidenceRoot: File,
    private val ownedTempCacheRoots: List<File>,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val volumeStatsReader: VolumeStatsReader = StatFsVolumeStatsReader,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<StorageMutationState>(StorageMutationState.Idle)

    val state: StateFlow<StorageMutationState> = mutableState.asStateFlow()

    suspend fun <T> withDownloadLease(block: suspend () -> T): T =
        withMutationLease(StorageMutationState.Downloading, block)

    suspend fun <T> withClearLease(block: suspend () -> T): T =
        withMutationLease(StorageMutationState.Clearing, block)

    suspend fun snapshot(): StorageUsageSnapshot =
        mutex.withLock {
            withContext(ioDispatcher) {
                val volume = volumeStatsReader.read(appStorageRoot)
                val tileUsage = tileStore.measuredUsage()
                val candidateCropBytes = regularFileBytes(candidateCropRoot)
                val evidenceBytes = regularFileBytes(evidenceRoot)
                val rootTempBytes =
                    ownedTempCacheRoots.fold(0L) { total, root ->
                        Math.addExact(total, regularFileBytes(root))
                    }
                val ownedTempCacheBytes =
                    Math.addExact(tileUsage.ownedTempCacheBytes, rootTempBytes)
                val mapbox = offlineRegionInventory()

                StorageUsageSnapshot(
                    availableVolumeBytes = volume.availableBytes,
                    totalVolumeBytes = volume.totalBytes,
                    geotiffBytes = tileUsage.geotiffBytes,
                    candidateCropBytes = candidateCropBytes,
                    evidenceBytes = evidenceBytes,
                    ownedTempCacheBytes = ownedTempCacheBytes,
                    mapboxRegionCount = mapbox.regionCount.coerceAtLeast(0),
                    mapboxBytes = mapbox.measuredBytes?.coerceAtLeast(0L),
                )
            }
        }

    private suspend fun <T> withMutationLease(
        mutationState: StorageMutationState,
        block: suspend () -> T,
    ): T =
        mutex.withLock {
            mutableState.value = mutationState
            try {
                block()
            } finally {
                mutableState.value = StorageMutationState.Idle
            }
        }

    private suspend fun offlineRegionInventory(): OfflineRegionInventory =
        suspendCancellableCoroutine { continuation ->
            offlineRegionWrapper.inventory { result ->
                if (!continuation.isActive) {
                    return@inventory
                }
                result.fold(
                    onSuccess = continuation::resume,
                    onFailure = continuation::resumeWithException,
                )
            }
        }
}
