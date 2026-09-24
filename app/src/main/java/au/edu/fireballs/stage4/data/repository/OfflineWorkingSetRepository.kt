package au.edu.fireballs.stage4.data.repository

import androidx.room.withTransaction
import au.edu.fireballs.stage4.data.local.CandidateCropManifestEntity
import au.edu.fireballs.stage4.data.local.OfflineBundleEntity
import au.edu.fireballs.stage4.data.local.SatelliteRegionEntity
import au.edu.fireballs.stage4.data.local.Stage4Database
import au.edu.fireballs.stage4.data.local.TileManifestEntity
import au.edu.fireballs.stage4.data.local.dao.CandidateCropManifestDao
import au.edu.fireballs.stage4.data.local.dao.OfflineBundleDao
import au.edu.fireballs.stage4.data.local.dao.SatelliteRegionDao
import au.edu.fireballs.stage4.data.local.dao.TileManifestDao
import au.edu.fireballs.stage4.data.tiles.OfflineRegionPurgeResult
import au.edu.fireballs.stage4.data.tiles.OfflineRegionRetention
import au.edu.fireballs.stage4.data.tiles.OfflineRegionWrapper
import au.edu.fireballs.stage4.data.tiles.TileStore
import au.edu.fireballs.stage4.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume

enum class PreDownloadPruneCategory {
    GEOTIFF,
    CROP,
}

sealed interface ReplacementBeginResult {
    data class Started(
        val session: ReplacementSession,
    ) : ReplacementBeginResult

    data class ConflictingReplacement(
        val activeManifestId: String,
    ) : ReplacementBeginResult
}

sealed interface ReplacementPruneResult {
    data class Completed(
        val prunedGeotiffs: Int,
        val prunedCrops: Int,
        val purgedSatellites: Int,
        val pendingSatellitePurges: Int,
    ) : ReplacementPruneResult

    data class LocalDeletionFailed(
        val category: PreDownloadPruneCategory,
        val message: String?,
    ) : ReplacementPruneResult

    data object ManifestUnavailable : ReplacementPruneResult
}

sealed interface ReplacementItemResult {
    data class Completed(
        val key: PreDownloadTargetKey,
    ) : ReplacementItemResult

    data class InvalidPayload(
        val key: PreDownloadTargetKey,
    ) : ReplacementItemResult

    data object UnknownItem : ReplacementItemResult
}

sealed interface ReplacementCompletionResult {
    data class Completed(
        val manifestId: String,
    ) : ReplacementCompletionResult

    data class Refused(
        val missingCount: Int,
    ) : ReplacementCompletionResult

    data object ManifestUnavailable : ReplacementCompletionResult
}

class OfflineWorkingSetRepository(
    private val database: Stage4Database,
    private val offlineBundleDao: OfflineBundleDao,
    private val tileManifestDao: TileManifestDao,
    private val candidateCropManifestDao: CandidateCropManifestDao,
    private val satelliteRegionDao: SatelliteRegionDao,
    private val tileStore: TileStore,
    private val candidateImageRepository: CandidateImageRepository,
    private val offlineRegionWrapper: OfflineRegionWrapper,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun inspectTarget(target: PreDownloadTargetSet): ReplacementClassification =
        withContext(ioDispatcher) { classify(target) }

    suspend fun beginReplacement(
        target: ReplacementTarget,
        classification: ReplacementClassification,
        manifestId: String = UUID.randomUUID().toString(),
    ): ReplacementBeginResult =
        withContext(ioDispatcher) {
            database.withTransaction {
                val active =
                    offlineBundleDao
                        .getAll()
                        .firstOrNull { it.state == WorkingSetState.REPLACING.value }
                if (active != null && active.manifestId != manifestId) {
                    val conflict =
                        ReplacementBeginResult.ConflictingReplacement(active.manifestId)
                    return@withTransaction conflict
                }
                val required = classification.retained + classification.missing
                val retained = classification.retained
                val now = System.currentTimeMillis()
                val existing = offlineBundleDao.getByManifestId(manifestId)
                if (existing != null) {
                    tileManifestDao.deleteForManifest(manifestId)
                    candidateCropManifestDao.deleteForManifest(manifestId)
                    satelliteRegionDao.deleteForManifest(manifestId)
                }
                offlineBundleDao.insert(
                    OfflineBundleEntity(
                        rowId = existing?.rowId ?: 0L,
                        manifestId = manifestId,
                        surveyId = target.surveyId,
                        sourceVersion = target.sourceVersion,
                        radiusMetres = target.radiusMetres,
                        minZoom = target.minZoom,
                        maxZoom = target.maxZoom,
                        state = WorkingSetState.REPLACING.value,
                        createdAt = existing?.createdAt ?: now,
                        updatedAt = now,
                        totalBytes = existing?.totalBytes ?: 0L,
                        tileCount = required.count { it is PreDownloadTargetKey.Tile },
                        satelliteRegionCount =
                            required.count {
                                it is PreDownloadTargetKey.Satellite
                            },
                        candidateCount = target.candidateCount,
                    ),
                )
                tileManifestDao.insertAll(
                    required
                        .filterIsInstance<PreDownloadTargetKey.Tile>()
                        .map { it.toEntity(manifestId, it in retained) },
                )
                candidateCropManifestDao.insertAll(
                    required
                        .filterIsInstance<PreDownloadTargetKey.Crop>()
                        .map { it.toEntity(manifestId, it in retained) },
                )
                satelliteRegionDao.upsertAll(
                    required
                        .filterIsInstance<PreDownloadTargetKey.Satellite>()
                        .map { it.toEntity(manifestId, it in retained) },
                )
                ReplacementBeginResult.Started(
                    ReplacementSession(
                        manifestId = manifestId,
                        sourceVersion = target.sourceVersion,
                        state = WorkingSetState.REPLACING,
                        requiredCount = required.size,
                        missing = classification.missing,
                        obsolete = classification.obsolete,
                        clearCommands = classification.clearCommands,
                    ),
                )
            }
        }

    suspend fun pruneObsolete(session: ReplacementSession): ReplacementPruneResult =
        withContext(ioDispatcher) {
            val bundle = offlineBundleDao.getByManifestId(session.manifestId)
            if (bundle == null || bundle.state == WorkingSetState.COMPLETE.value) {
                return@withContext ReplacementPruneResult.ManifestUnavailable
            }
            val transitioned =
                database.withTransaction {
                    val current = offlineBundleDao.getByManifestId(session.manifestId)
                    if (current == null) {
                        false
                    } else {
                        offlineBundleDao.updateState(
                            session.manifestId,
                            WorkingSetState.INCOMPLETE.value,
                            System.currentTimeMillis(),
                        )
                        true
                    }
                }
            if (!transitioned) {
                return@withContext ReplacementPruneResult.ManifestUnavailable
            }

            val oldBundles =
                offlineBundleDao
                    .getAll()
                    .filter { it.manifestId != session.manifestId }
            val previousBundle = oldBundles.firstOrNull { it.surveyId == bundle.surveyId }
            val sourceGenerationChanged =
                previousBundle != null && previousBundle.sourceVersion != bundle.sourceVersion
            val previousReplacementUnfinished =
                previousBundle != null && previousBundle.state != WorkingSetState.COMPLETE.value
            if (sourceGenerationChanged || previousReplacementUnfinished) {
                try {
                    tileStore.deleteDerivedTiles(bundle.surveyId)
                } catch (error: IOException) {
                    return@withContext ReplacementPruneResult.LocalDeletionFailed(
                        PreDownloadPruneCategory.GEOTIFF,
                        error.message,
                    )
                }
            }

            val oldManifestIds = oldBundles.map { it.manifestId }
            val geotiffKeys = session.obsolete.filterIsInstance<PreDownloadPruneKey.Geotiff>()
            val cropKeys = session.obsolete.filterIsInstance<PreDownloadPruneKey.Crop>()
            val satelliteKeys = session.obsolete.filterIsInstance<PreDownloadPruneKey.Satellite>()

            var prunedGeotiffs = 0
            if (geotiffKeys.isNotEmpty()) {
                try {
                    if (PreDownloadClearCommand.ClearGeotiffs in session.clearCommands) {
                        tileStore.deleteAll()
                    } else {
                        tileStore.deleteTiles(geotiffKeys)
                    }
                } catch (error: IOException) {
                    return@withContext ReplacementPruneResult.LocalDeletionFailed(
                        PreDownloadPruneCategory.GEOTIFF,
                        error.message,
                    )
                }
                prunedGeotiffs = geotiffKeys.size
                database.withTransaction {
                    oldManifestIds.forEach { tileManifestDao.deleteForManifest(it) }
                }
            }

            var prunedCrops = 0
            if (cropKeys.isNotEmpty()) {
                try {
                    if (PreDownloadClearCommand.ClearCrops in session.clearCommands) {
                        candidateImageRepository.clearAllCrops()
                    } else {
                        candidateImageRepository.deleteCrops(cropKeys)
                    }
                } catch (error: IOException) {
                    return@withContext ReplacementPruneResult.LocalDeletionFailed(
                        PreDownloadPruneCategory.CROP,
                        error.message,
                    )
                }
                prunedCrops = cropKeys.size
                database.withTransaction {
                    oldManifestIds.forEach { candidateCropManifestDao.deleteForManifest(it) }
                }
            }

            var purgedSatellites = 0
            var pendingSatellites = 0
            if (satelliteKeys.isNotEmpty()) {
                database.withTransaction {
                    oldManifestIds.forEach { satelliteRegionDao.markPendingDeletion(it) }
                }
                val oldRows = oldManifestIds.flatMap { satelliteRegionDao.getForManifest(it) }
                satelliteKeys.forEach { key ->
                    val row =
                        oldRows.firstOrNull {
                            it.surveyId == key.surveyId && it.signature == key.signature
                        }
                    val target =
                        PreDownloadTargetKey.Satellite(
                            surveyId = key.surveyId,
                            sourceVersion = row?.sourceVersion ?: session.sourceVersion,
                            signature = key.signature,
                        )
                    when (val result = purgeTarget(target)) {
                        is OfflineRegionPurgeResult.Purged,
                        is OfflineRegionPurgeResult.ConfirmedAbsent,
                        -> {
                            row?.let { satelliteRegionDao.removePurged(it.rowId) }
                            purgedSatellites++
                        }

                        is OfflineRegionPurgeResult.RetryableFailure -> {
                            row?.let {
                                satelliteRegionDao.recordPurgeAttempt(
                                    it.rowId,
                                    result.category.name,
                                    result.attemptTimeMillis,
                                )
                            }
                            pendingSatellites++
                        }
                    }
                }
            }

            database.withTransaction {
                oldManifestIds.forEach { offlineBundleDao.deleteByManifestId(it) }
                tileManifestDao.deleteOrphans()
                candidateCropManifestDao.deleteOrphans()
                satelliteRegionDao.deleteOrphans()
            }
            ReplacementPruneResult.Completed(
                prunedGeotiffs = prunedGeotiffs,
                prunedCrops = prunedCrops,
                purgedSatellites = purgedSatellites,
                pendingSatellitePurges = pendingSatellites,
            )
        }

    suspend fun markItemComplete(
        manifestId: String,
        key: PreDownloadTargetKey,
    ): ReplacementItemResult =
        withContext(ioDispatcher) {
            when (key) {
                is PreDownloadTargetKey.Tile -> completeTile(manifestId, key)
                is PreDownloadTargetKey.Crop -> completeCrop(manifestId, key)
                is PreDownloadTargetKey.Satellite -> completeSatellite(manifestId, key)
            }
        }

    suspend fun completeReplacement(manifestId: String): ReplacementCompletionResult =
        withContext(ioDispatcher) {
            val bundle = offlineBundleDao.getByManifestId(manifestId)
            if (bundle == null) {
                return@withContext ReplacementCompletionResult.ManifestUnavailable
            }
            if (bundle.state == WorkingSetState.COMPLETE.value) {
                return@withContext ReplacementCompletionResult.Completed(manifestId)
            }
            var missing = 0
            tileManifestDao.getForManifest(manifestId).forEach { row ->
                val key = row.toTargetKey()
                val valid =
                    tileStore.isValidTile(
                        key.surveyId,
                        key.candidateId,
                        key.zoom,
                        key.x,
                        key.y,
                    )
                when {
                    !valid -> {
                        if (row.completed) {
                            database.withTransaction {
                                tileManifestDao.updateCompletion(
                                    manifestId,
                                    row.rowId,
                                    false,
                                    row.bytes,
                                )
                            }
                        }
                        missing++
                    }

                    !row.completed -> {
                        database.withTransaction {
                            tileManifestDao.updateCompletion(
                                manifestId,
                                row.rowId,
                                true,
                                tileStore.fileLength(
                                    key.surveyId,
                                    key.candidateId,
                                    key.zoom,
                                    key.x,
                                    key.y,
                                ) ?: row.bytes,
                            )
                        }
                    }
                }
            }
            candidateCropManifestDao.getForManifest(manifestId).forEach { row ->
                val valid =
                    candidateImageRepository.isValidCrop(row.surveyId, row.candidateId)
                when {
                    !valid -> {
                        if (row.completed) {
                            database.withTransaction {
                                candidateCropManifestDao.updateCompletion(
                                    manifestId,
                                    row.rowId,
                                    false,
                                )
                            }
                        }
                        missing++
                    }

                    !row.completed -> {
                        database.withTransaction {
                            candidateCropManifestDao.updateCompletion(
                                manifestId,
                                row.rowId,
                                true,
                            )
                        }
                    }
                }
            }
            val satelliteRows = satelliteRegionDao.getForManifest(manifestId)
            val present =
                retainTargets(satelliteRows.map { it.toTargetKey() })
                    .filterIsInstance<OfflineRegionRetention.Retained>()
                    .map { it.target }
                    .toSet()
            satelliteRows.forEach { row ->
                if (row.toTargetKey() in present) {
                    if (!row.completed) {
                        database.withTransaction {
                            satelliteRegionDao.updateCompletion(manifestId, row.signature, true)
                        }
                    }
                } else {
                    if (row.completed) {
                        database.withTransaction {
                            satelliteRegionDao.updateCompletion(manifestId, row.signature, false)
                        }
                    }
                    missing++
                }
            }
            if (missing > 0) {
                return@withContext ReplacementCompletionResult.Refused(missing)
            }
            val completed =
                database.withTransaction {
                    val updated =
                        offlineBundleDao.markCompleteIfReady(
                            manifestId,
                            System.currentTimeMillis(),
                        )
                    if (updated == 0) {
                        false
                    } else {
                        val otherManifests =
                            offlineBundleDao
                                .getAll()
                                .map { it.manifestId }
                                .filter { it != manifestId }
                        otherManifests.forEach { offlineBundleDao.deleteByManifestId(it) }
                        tileManifestDao.deleteOrphans()
                        candidateCropManifestDao.deleteOrphans()
                        satelliteRegionDao.deleteOrphans()
                        true
                    }
                }
            if (completed) {
                ReplacementCompletionResult.Completed(manifestId)
            } else {
                ReplacementCompletionResult.Refused(missingCount(manifestId))
            }
        }

    private suspend fun completeTile(
        manifestId: String,
        key: PreDownloadTargetKey.Tile,
    ): ReplacementItemResult {
        val row =
            tileManifestDao.getExact(
                manifestId = manifestId,
                surveyId = key.surveyId,
                candidateId = key.candidateId,
                sourceVersion = key.sourceVersion,
                radiusMetres = key.radiusMetres,
                zoom = key.zoom,
                x = key.x,
                y = key.y,
                kind = key.kind,
                expectedFormat = key.expectedFormat,
            ) ?: return ReplacementItemResult.UnknownItem
        if (!tileStore.isValidTile(key.surveyId, key.candidateId, key.zoom, key.x, key.y)) {
            return ReplacementItemResult.InvalidPayload(key)
        }
        database.withTransaction {
            tileManifestDao.updateCompletion(
                manifestId,
                row.rowId,
                true,
                tileStore.fileLength(key.surveyId, key.candidateId, key.zoom, key.x, key.y) ?: 0L,
            )
        }
        return ReplacementItemResult.Completed(key)
    }

    private suspend fun completeCrop(
        manifestId: String,
        key: PreDownloadTargetKey.Crop,
    ): ReplacementItemResult {
        val row =
            candidateCropManifestDao.getExact(
                manifestId = manifestId,
                surveyId = key.surveyId,
                candidateId = key.candidateId,
                sourceVersion = key.sourceVersion,
                requestSignature = key.requestSignature,
            ) ?: return ReplacementItemResult.UnknownItem
        if (!candidateImageRepository.isValidCrop(key.surveyId, key.candidateId)) {
            return ReplacementItemResult.InvalidPayload(key)
        }
        database.withTransaction {
            candidateCropManifestDao.updateCompletion(manifestId, row.rowId, true)
        }
        return ReplacementItemResult.Completed(key)
    }

    private suspend fun completeSatellite(
        manifestId: String,
        key: PreDownloadTargetKey.Satellite,
    ): ReplacementItemResult {
        val present = retainTargets(listOf(key)).any { it is OfflineRegionRetention.Retained }
        if (!present) {
            return ReplacementItemResult.InvalidPayload(key)
        }
        database.withTransaction {
            satelliteRegionDao.updateCompletion(manifestId, key.signature, true)
        }
        return ReplacementItemResult.Completed(key)
    }

    private suspend fun classify(target: PreDownloadTargetSet): ReplacementClassification {
        val oldManifestIds = offlineBundleDao.getAll().map { it.manifestId }
        val owned = mutableListOf<PreDownloadOwnedPayload>()
        oldManifestIds.forEach { manifestId ->
            tileManifestDao.getForManifest(manifestId).forEach { row ->
                val key = row.toTargetKey()
                owned +=
                    PreDownloadOwnedPayload(
                        key = key,
                        valid =
                            tileStore.isValidTile(
                                key.surveyId,
                                key.candidateId,
                                key.zoom,
                                key.x,
                                key.y,
                            ),
                        measuredDeletableBytes =
                            tileStore.fileLength(
                                key.surveyId,
                                key.candidateId,
                                key.zoom,
                                key.x,
                                key.y,
                            ),
                    )
            }
            candidateCropManifestDao.getForManifest(manifestId).forEach { row ->
                val key = row.toTargetKey()
                owned +=
                    PreDownloadOwnedPayload(
                        key = key,
                        valid =
                            candidateImageRepository.isValidCrop(key.surveyId, key.candidateId),
                        measuredDeletableBytes =
                            candidateImageRepository
                                .getLocalCropImageFile(key.surveyId, key.candidateId)
                                ?.length(),
                    )
            }
        }
        val satelliteKeys =
            (
                oldManifestIds
                    .flatMap { satelliteRegionDao.getForManifest(it) }
                    .map { it.toTargetKey() } + target.satellites
            ).distinct()
        val present =
            retainTargets(satelliteKeys)
                .filterIsInstance<OfflineRegionRetention.Retained>()
                .map { it.target }
                .toSet()
        satelliteKeys.forEach { key ->
            owned +=
                PreDownloadOwnedPayload(
                    key = key,
                    valid = key in present,
                    measuredDeletableBytes = null,
                )
        }
        return ReplacementClassifier.classify(target, owned)
    }

    private suspend fun missingCount(manifestId: String): Int =
        tileManifestDao.countIncomplete(manifestId) +
            candidateCropManifestDao.countIncomplete(manifestId) +
            satelliteRegionDao.countIncomplete(manifestId)

    private suspend fun retainTargets(
        targets: List<PreDownloadTargetKey.Satellite>,
    ): List<OfflineRegionRetention> {
        if (targets.isEmpty()) {
            return emptyList()
        }
        return suspendCancellableCoroutine { continuation ->
            offlineRegionWrapper.retainExact(targets) { result ->
                if (continuation.isActive) {
                    continuation.resume(result.getOrDefault(emptyList()))
                }
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

    suspend fun purgeSatelliteTarget(
        target: PreDownloadTargetKey.Satellite,
    ): OfflineRegionPurgeResult = purgeTarget(target)
}

private fun PreDownloadTargetKey.Tile.toEntity(
    manifestId: String,
    completed: Boolean,
): TileManifestEntity =
    TileManifestEntity(
        manifestId = manifestId,
        surveyId = surveyId,
        candidateId = candidateId,
        sourceVersion = sourceVersion,
        radiusMetres = radiusMetres,
        zoom = zoom,
        x = x,
        y = y,
        kind = kind,
        expectedFormat = expectedFormat,
        completed = completed,
    )

private fun PreDownloadTargetKey.Crop.toEntity(
    manifestId: String,
    completed: Boolean,
): CandidateCropManifestEntity =
    CandidateCropManifestEntity(
        manifestId = manifestId,
        surveyId = surveyId,
        candidateId = candidateId,
        sourceVersion = sourceVersion,
        requestSignature = requestSignature,
        completed = completed,
    )

private fun PreDownloadTargetKey.Satellite.toEntity(
    manifestId: String,
    completed: Boolean,
): SatelliteRegionEntity =
    SatelliteRegionEntity(
        manifestId = manifestId,
        surveyId = surveyId,
        sourceVersion = sourceVersion,
        signature = signature,
        completed = completed,
        pendingDeletion = false,
    )

private fun TileManifestEntity.toTargetKey(): PreDownloadTargetKey.Tile =
    PreDownloadTargetKey.Tile(
        surveyId = surveyId,
        candidateId = candidateId,
        sourceVersion = sourceVersion,
        radiusMetres = radiusMetres,
        zoom = zoom,
        x = x,
        y = y,
        kind = kind,
        expectedFormat = expectedFormat,
    )

private fun CandidateCropManifestEntity.toTargetKey(): PreDownloadTargetKey.Crop =
    PreDownloadTargetKey.Crop(
        surveyId = surveyId,
        candidateId = candidateId,
        sourceVersion = sourceVersion,
        requestSignature = requestSignature,
    )

private fun SatelliteRegionEntity.toTargetKey(): PreDownloadTargetKey.Satellite =
    PreDownloadTargetKey.Satellite(
        surveyId = surveyId,
        sourceVersion = sourceVersion,
        signature = signature,
    )
