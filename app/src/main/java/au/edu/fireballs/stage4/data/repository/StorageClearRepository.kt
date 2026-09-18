package au.edu.fireballs.stage4.data.repository

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import au.edu.fireballs.stage4.data.local.SatelliteRegionEntity
import au.edu.fireballs.stage4.data.local.Stage4Database
import au.edu.fireballs.stage4.data.local.dao.CandidateCropManifestDao
import au.edu.fireballs.stage4.data.local.dao.OfflineBundleDao
import au.edu.fireballs.stage4.data.local.dao.SatelliteRegionDao
import au.edu.fireballs.stage4.data.local.dao.TileManifestDao
import au.edu.fireballs.stage4.data.tiles.OfflineRegionPurgeResult
import au.edu.fireballs.stage4.data.tiles.OfflineRegionWrapper
import au.edu.fireballs.stage4.data.tiles.TileStore
import au.edu.fireballs.stage4.data.tiles.decodeOfflineRegionTarget
import au.edu.fireballs.stage4.di.CandidateCropRoot
import au.edu.fireballs.stage4.di.IoDispatcher
import au.edu.fireballs.stage4.di.OwnedTempCacheRoots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.LinkOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class StorageClearCategory {
    GeotiffTiles,
    CandidateCrops,
    SatelliteMaps,
    TemporaryCache,
    AllCachedDownloads,
}

sealed interface StorageClearResult {
    val category: StorageClearCategory

    data class Cleared(
        override val category: StorageClearCategory,
    ) : StorageClearResult

    data class PartiallyCleared(
        override val category: StorageClearCategory,
        val pendingSatellitePurges: Int,
    ) : StorageClearResult

    data class Failed(
        override val category: StorageClearCategory,
        val message: String?,
    ) : StorageClearResult
}

@Singleton
class StorageClearRepository
    @Inject
    constructor(
        private val storageCoordinator: StorageCoordinator,
        private val database: Stage4Database,
        private val offlineBundleDao: OfflineBundleDao,
        private val tileManifestDao: TileManifestDao,
        private val candidateCropManifestDao: CandidateCropManifestDao,
        private val satelliteRegionDao: SatelliteRegionDao,
        private val tileStore: TileStore,
        private val candidateImageRepository: CandidateImageRepository,
        private val offlineRegionWrapper: OfflineRegionWrapper,
        @CandidateCropRoot private val candidateCropRoot: File,
        @OwnedTempCacheRoots
        private val ownedTempCacheRoots: List<@JvmSuppressWildcards File>,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        suspend fun clear(category: StorageClearCategory): StorageClearResult =
            try {
                storageCoordinator.withClearLease {
                    withContext(ioDispatcher) {
                        when (category) {
                            StorageClearCategory.GeotiffTiles -> clearGeotiffs(category)
                            StorageClearCategory.CandidateCrops -> clearCrops(category)
                            StorageClearCategory.SatelliteMaps -> clearSatellites(category)
                            StorageClearCategory.TemporaryCache -> clearTemporaryCache(category)
                            StorageClearCategory.AllCachedDownloads -> clearAll(category)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                StorageClearResult.Failed(category, error.message)
            } catch (error: UncheckedIOException) {
                StorageClearResult.Failed(category, error.message)
            } catch (error: SQLiteException) {
                StorageClearResult.Failed(category, error.message)
            } catch (error: SecurityException) {
                StorageClearResult.Failed(category, error.message)
            } catch (error: IllegalStateException) {
                StorageClearResult.Failed(category, error.message)
            }

        private suspend fun clearGeotiffs(category: StorageClearCategory): StorageClearResult {
            val affected = manifestsWithTiles()
            tileStore.deleteAll()
            val remaining = tileStore.measuredUsage()
            if (remaining.geotiffBytes > 0L || remaining.ownedTempCacheBytes > 0L) {
                return StorageClearResult.Failed(category, "Tile files remain after clear")
            }
            database.withTransaction {
                tileManifestDao.deleteAll()
                invalidate(affected)
            }
            return StorageClearResult.Cleared(category)
        }

        private suspend fun clearCrops(category: StorageClearCategory): StorageClearResult {
            val affected = manifestsWithCrops()
            candidateImageRepository.clearAllCrops()
            if (regularFileBytes(candidateCropRoot) > 0L) {
                return StorageClearResult.Failed(category, "Crop files remain after clear")
            }
            database.withTransaction {
                candidateCropManifestDao.deleteAll()
                invalidate(affected)
            }
            return StorageClearResult.Cleared(category)
        }

        private suspend fun clearSatellites(category: StorageClearCategory): StorageClearResult {
            val bundles = offlineBundleDao.getAll()
            val affected =
                bundles
                    .map { it.manifestId }
                    .filter { satelliteRegionDao.getForManifest(it).isNotEmpty() }
                    .toSet()
            database.withTransaction {
                affected.forEach { satelliteRegionDao.markPendingDeletion(it) }
            }
            val rows = satelliteRegionDao.getPendingDeletion()
            val targets =
                buildSet {
                    rows.forEach { add(it.toTarget()) }
                    listOwnedRegionTargets().forEach(::add)
                }
            targets.forEach { target ->
                reconcilePurge(
                    target = target,
                    rows = rows.filter { it.matches(target) },
                    category = category,
                )
            }
            database.withTransaction {
                invalidate(affected)
            }
            return satelliteResult(category)
        }

        private suspend fun clearTemporaryCache(
            category: StorageClearCategory,
        ): StorageClearResult {
            ownedTempCacheRoots.forEach(::deleteOwnedRegularFiles)
            return StorageClearResult.Cleared(category)
        }

        private suspend fun clearAll(category: StorageClearCategory): StorageClearResult {
            val geotiffResult = clearGeotiffs(category)
            if (geotiffResult is StorageClearResult.Failed) {
                return geotiffResult
            }
            val cropResult = clearCrops(category)
            if (cropResult is StorageClearResult.Failed) {
                return cropResult
            }
            clearTemporaryCache(category)
            clearSatellites(category)
            database.withTransaction {
                offlineBundleDao.deleteAll()
                tileManifestDao.deleteAll()
                candidateCropManifestDao.deleteAll()
                satelliteRegionDao.deleteOrphans()
            }
            return satelliteResult(category)
        }

        private suspend fun manifestsWithTiles(): Set<String> =
            offlineBundleDao
                .getAll()
                .map { it.manifestId }
                .filter { tileManifestDao.getForManifest(it).isNotEmpty() }
                .toSet()

        private suspend fun manifestsWithCrops(): Set<String> =
            offlineBundleDao
                .getAll()
                .map { it.manifestId }
                .filter { candidateCropManifestDao.getForManifest(it).isNotEmpty() }
                .toSet()

        private suspend fun invalidate(manifestIds: Set<String>) {
            val now = System.currentTimeMillis()
            manifestIds.forEach {
                offlineBundleDao.updateState(it, WorkingSetState.INCOMPLETE.value, now)
            }
        }

        private suspend fun listOwnedRegionTargets(): Set<PreDownloadTargetKey.Satellite> =
            suspendCancellableCoroutine { continuation ->
                offlineRegionWrapper.listRegions { result ->
                    if (!continuation.isActive) {
                        return@listRegions
                    }
                    result.fold(
                        onSuccess = { regions ->
                            continuation.resume(
                                regions
                                    .mapNotNull {
                                        decodeOfflineRegionTarget(it.metadata)
                                    }.toSet(),
                            )
                        },
                        onFailure = continuation::resumeWithException,
                    )
                }
            }

        private suspend fun reconcilePurge(
            target: PreDownloadTargetKey.Satellite,
            rows: List<SatelliteRegionEntity>,
            category: StorageClearCategory,
        ) {
            when (val result = purgeTarget(target)) {
                is OfflineRegionPurgeResult.Purged,
                is OfflineRegionPurgeResult.ConfirmedAbsent,
                -> rows.forEach { satelliteRegionDao.removePurged(it.rowId) }

                is OfflineRegionPurgeResult.RetryableFailure ->
                    rows.forEach {
                        satelliteRegionDao.recordPurgeAttempt(
                            it.rowId,
                            category.name,
                            result.attemptTimeMillis,
                        )
                    }
            }
        }

        private suspend fun purgeTarget(
            target: PreDownloadTargetKey.Satellite,
        ): OfflineRegionPurgeResult =
            suspendCancellableCoroutine { continuation ->
                offlineRegionWrapper.purgeExact(target) { result ->
                    if (continuation.isActive) {
                        continuation.resume(result)
                    }
                }
            }

        private suspend fun satelliteResult(category: StorageClearCategory): StorageClearResult {
            val pending = satelliteRegionDao.getPendingDeletion().size
            return if (pending == 0) {
                StorageClearResult.Cleared(category)
            } else {
                StorageClearResult.PartiallyCleared(category, pending)
            }
        }

        private fun deleteOwnedRegularFiles(root: File) {
            val rootPath = root.toPath()
            if (!Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) {
                return
            }
            Files.walk(rootPath).use { paths ->
                paths
                    .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                    .forEach { path ->
                        val file = path.toFile()
                        val canonicalPath = file.canonicalPath
                        if (!ActiveEvidenceCapture.isActive(canonicalPath) &&
                            !ActiveEvidenceCapture.isActive(file.absolutePath)
                        ) {
                            Files.delete(path)
                        }
                    }
            }
        }

        private fun SatelliteRegionEntity.toTarget(): PreDownloadTargetKey.Satellite =
            PreDownloadTargetKey.Satellite(
                surveyId = surveyId,
                sourceVersion = sourceVersion,
                signature = signature,
            )

        private fun SatelliteRegionEntity.matches(
            target: PreDownloadTargetKey.Satellite,
        ): Boolean =
            surveyId == target.surveyId &&
                sourceVersion == target.sourceVersion &&
                signature == target.signature
    }
