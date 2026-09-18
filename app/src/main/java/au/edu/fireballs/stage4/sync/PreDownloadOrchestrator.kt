package au.edu.fireballs.stage4.sync

import androidx.work.Data
import androidx.work.workDataOf
import au.edu.fireballs.stage4.data.local.dao.CandidateDao
import au.edu.fireballs.stage4.data.local.dao.ClaimDao
import au.edu.fireballs.stage4.data.local.dao.SurveyDao
import au.edu.fireballs.stage4.data.remote.TileService
import au.edu.fireballs.stage4.data.repository.CandidateImageRepository
import au.edu.fireballs.stage4.data.repository.ClaimRepository
import au.edu.fireballs.stage4.data.repository.ClaimResult
import au.edu.fireballs.stage4.data.repository.CropWriteResult
import au.edu.fireballs.stage4.data.repository.OfflineWorkingSetRepository
import au.edu.fireballs.stage4.data.repository.PreDownloadPreflightResult
import au.edu.fireballs.stage4.data.repository.PreDownloadSpaceEstimate
import au.edu.fireballs.stage4.data.repository.PreDownloadStoragePreflight
import au.edu.fireballs.stage4.data.repository.PreDownloadTargetCandidate
import au.edu.fireballs.stage4.data.repository.PreDownloadTargetKey
import au.edu.fireballs.stage4.data.repository.PreDownloadTargetPlanner
import au.edu.fireballs.stage4.data.repository.PreDownloadTargetSet
import au.edu.fireballs.stage4.data.repository.ReplacementBeginResult
import au.edu.fireballs.stage4.data.repository.ReplacementClassification
import au.edu.fireballs.stage4.data.repository.ReplacementCompletionResult
import au.edu.fireballs.stage4.data.repository.ReplacementPruneResult
import au.edu.fireballs.stage4.data.repository.ReplacementTarget
import au.edu.fireballs.stage4.data.repository.Stage4Repository
import au.edu.fireballs.stage4.data.repository.StorageCoordinator
import au.edu.fireballs.stage4.data.repository.StorageMutationState
import au.edu.fireballs.stage4.data.tiles.Bbox
import au.edu.fireballs.stage4.data.tiles.GeotiffRadiusRepository
import au.edu.fireballs.stage4.data.tiles.LocalFileRasterTileProvider
import au.edu.fireballs.stage4.data.tiles.LowZoomCompositor
import au.edu.fireballs.stage4.data.tiles.LowZoomTileCompositor
import au.edu.fireballs.stage4.data.tiles.OfflineManagerWrapper
import au.edu.fireballs.stage4.data.tiles.SatelliteRegionStore
import au.edu.fireballs.stage4.data.tiles.TileCoord
import au.edu.fireballs.stage4.data.tiles.TileMath
import au.edu.fireballs.stage4.data.tiles.TileStore
import au.edu.fireballs.stage4.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlin.coroutines.resume

sealed interface PreDownloadOutcome {
    data class Success(
        val outputData: Data,
    ) : PreDownloadOutcome

    data class Failure(
        val outputData: Data,
    ) : PreDownloadOutcome
}

@OptIn(ExperimentalCoroutinesApi::class)
class PreDownloadOrchestrator(
    private val claimRepository: ClaimRepository,
    private val stage4Repository: Stage4Repository,
    private val candidateDao: CandidateDao,
    private val claimDao: ClaimDao,
    private val surveyDao: SurveyDao,
    private val tileStore: TileStore,
    private val lowZoomCompositor: LowZoomCompositor,
    private val tileService: TileService,
    private val offlineManagerWrapper: OfflineManagerWrapper,
    private val workingSetRepository: OfflineWorkingSetRepository,
    private val candidateImageRepository: CandidateImageRepository,
    private val geotiffRadiusRepository: GeotiffRadiusRepository,
    private val satelliteRegionStore: SatelliteRegionStore,
    private val storageCoordinator: StorageCoordinator? = null,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val preflight: PreDownloadStoragePreflight? = null,
) {
    private data class CandidateTiles(
        val candidate: PreDownloadTargetCandidate,
        val tiles: List<TileCoord>,
    )

    private data class SatelliteWork(
        val bbox: Bbox,
        val signature: String,
    )

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    suspend fun run(
        surveyId: Long,
        bufferMeters: Float,
        manifestId: String? = null,
        progress: suspend (Data) -> Unit,
    ): PreDownloadOutcome {
        val locallyCachedTask = surveyDao.getById(surveyId)?.latestTaskCreated
        val refresh = claimRepository.refreshClaimsToRoom(surveyId)
        if (refresh !is ClaimResult.Refreshed) {
            return failure("Failed to refresh claims")
        }
        val serverTask = stage4Repository.fetchLatestTaskCreated(surveyId)
        val sourceVersion = serverTask.orEmpty()
        val replacementRequired =
            locallyCachedTask != null &&
                serverTask != null &&
                locallyCachedTask != serverTask

        val admission =
            preflight?.evaluate(
                surveyId,
                bufferMeters.toDouble(),
                geotiffRadiusRepository.getRadiusMeters().toDouble(),
                replacementRequired,
            )
        when (admission) {
            is PreDownloadPreflightResult.InsufficientDeviceSpace ->
                return failureWithEstimate(
                    CODE_INSUFFICIENT_DEVICE_SPACE,
                    "Not enough device space to prepare this survey offline",
                    admission.estimate,
                )

            PreDownloadPreflightResult.StorageOperationActive ->
                return failureWithCode(
                    CODE_STORAGE_OPERATION_ACTIVE,
                    "Another storage operation is already running",
                )

            else -> Unit
        }
        val coordinator = storageCoordinator
        if (coordinator != null &&
            coordinator.state.value != StorageMutationState.Idle
        ) {
            return failureWithCode(
                CODE_STORAGE_OPERATION_ACTIVE,
                "Another storage operation is already running",
            )
        }
        return try {
            if (coordinator == null) {
                runDownload(
                    surveyId,
                    bufferMeters,
                    replacementRequired,
                    sourceVersion,
                    manifestId,
                    progress,
                )
            } else {
                coordinator.withDownloadLease {
                    runDownload(
                        surveyId,
                        bufferMeters,
                        replacementRequired,
                        sourceVersion,
                        manifestId,
                        progress,
                    )
                }
            }
        } catch (e: StorageFullException) {
            failureWithCode(
                CODE_STORAGE_FULL_WHILE_WRITING,
                "Device storage became full during download",
            )
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun runDownload(
        surveyId: Long,
        bufferMeters: Float,
        forceRefresh: Boolean,
        sourceVersion: String,
        manifestId: String?,
        progress: suspend (Data) -> Unit,
    ): PreDownloadOutcome {
        val candidates = buildCandidates(surveyId)
        val satelliteRadius = bufferMeters.toDouble()
        val geotiffRadius = geotiffRadiusRepository.getRadiusMeters().toDouble()
        val clusters = PreDownloadTargetPlanner.cluster(candidates, satelliteRadius)
        val satelliteTargets =
            PreDownloadTargetPlanner.satelliteTargets(clusters, satelliteRadius)
        val targetSet =
            PreDownloadTargetSet(
                geotiffTiles =
                    candidates.flatMap { candidate ->
                        PreDownloadTargetPlanner
                            .tilesForCandidate(candidate, geotiffRadius)
                            .map { tile ->
                                tileKey(
                                    surveyId,
                                    candidate.inferenceResultId,
                                    sourceVersion,
                                    geotiffRadius,
                                    tile,
                                )
                            }
                    },
                crops =
                    candidates.map { candidate ->
                        cropKey(surveyId, candidate.inferenceResultId, sourceVersion)
                    },
                satellites =
                    satelliteTargets.map { target ->
                        PreDownloadTargetKey.Satellite(
                            surveyId,
                            sourceVersion,
                            target.signature,
                        )
                    },
            )

        val activeManifestId = manifestId ?: UUID.randomUUID().toString()
        val classification =
            if (manifestId == null) {
                val inspected = workingSetRepository.inspectTarget(targetSet)
                val target =
                    ReplacementTarget(
                        surveyId = surveyId,
                        sourceVersion = sourceVersion,
                        radiusMetres = satelliteRadius,
                        minZoom = PreDownloadTargetPlanner.SATELLITE_MIN_ZOOM,
                        maxZoom = PreDownloadTargetPlanner.SATELLITE_MAX_ZOOM,
                        candidateCount = candidates.size,
                    )
                when (
                    val begin =
                        workingSetRepository.beginReplacement(target, inspected, activeManifestId)
                ) {
                    is ReplacementBeginResult.ConflictingReplacement ->
                        return failureWithCode(
                            CODE_REPLACEMENT_CONFLICT,
                            "Another replacement is in progress",
                        )

                    is ReplacementBeginResult.Started -> {
                        when (workingSetRepository.pruneObsolete(begin.session)) {
                            is ReplacementPruneResult.LocalDeletionFailed ->
                                return failureWithCode(
                                    CODE_REPLACEMENT_FAILED,
                                    "Failed to remove obsolete content",
                                )

                            ReplacementPruneResult.ManifestUnavailable ->
                                return failureWithCode(
                                    CODE_REPLACEMENT_FAILED,
                                    "Replacement manifest unavailable",
                                )

                            is ReplacementPruneResult.Completed -> Unit
                        }
                        inspected
                    }
                }
            } else {
                val resumed =
                    workingSetRepository.resume(manifestId)
                        ?: return failureWithCode(
                            CODE_REPLACEMENT_FAILED,
                            "Replacement not found",
                        )
                ReplacementClassification(
                    retained = emptySet(),
                    missing = resumed.session.missing,
                    obsolete = emptySet(),
                    clearCommands = emptyList(),
                    confidentlyDeletableBytes = 0L,
                )
            }

        val missing = classification.missing
        val candidateTiles =
            candidates.mapNotNull { candidate ->
                val tiles =
                    PreDownloadTargetPlanner
                        .tilesForCandidate(candidate, geotiffRadius)
                        .filter { tile ->
                            tileKey(
                                surveyId,
                                candidate.inferenceResultId,
                                sourceVersion,
                                geotiffRadius,
                                tile,
                            ) in missing
                        }
                tiles.takeIf { it.isNotEmpty() }?.let { CandidateTiles(candidate, it) }
            }
        val missingCrops =
            candidates.filter { candidate ->
                cropKey(surveyId, candidate.inferenceResultId, sourceVersion) in missing
            }
        val satelliteWork =
            satelliteTargets
                .filter { target ->
                    PreDownloadTargetKey.Satellite(
                        surveyId,
                        sourceVersion,
                        target.signature,
                    ) in missing
                }.map { SatelliteWork(it.bbox, it.signature) }
        val satelliteTotal = satelliteWork.size
        val tileTotal = candidateTiles.sumOf { it.tiles.size }
        val cropTotal = missingCrops.size
        val total = satelliteTotal + tileTotal + cropTotal

        val outcome =
            acquireSurvey(
                surveyId = surveyId,
                candidates = candidates,
                satelliteWork = satelliteWork,
                candidateTiles = candidateTiles,
                missingCrops = missingCrops,
                satelliteTotal = satelliteTotal,
                tileTotal = tileTotal,
                total = total,
                reDownloadRecommended = forceRefresh,
                progress = progress,
                manifestId = activeManifestId,
            )
        if (outcome !is PreDownloadOutcome.Success) {
            return outcome
        }
        missing.forEach { key -> workingSetRepository.markItemComplete(activeManifestId, key) }
        return when (val completed = workingSetRepository.completeReplacement(activeManifestId)) {
            is ReplacementCompletionResult.Completed -> outcome

            is ReplacementCompletionResult.Refused ->
                failureWithCode(
                    CODE_REPLACEMENT_FAILED,
                    "Replacement incomplete: ${completed.missingCount} missing",
                )

            ReplacementCompletionResult.ManifestUnavailable ->
                failureWithCode(CODE_REPLACEMENT_FAILED, "Replacement manifest unavailable")
        }
    }

    private fun tileKey(
        surveyId: Long,
        candidateId: Long,
        sourceVersion: String,
        radiusMetres: Double,
        tile: TileCoord,
    ): PreDownloadTargetKey.Tile =
        PreDownloadTargetKey.Tile(
            surveyId = surveyId,
            candidateId = candidateId,
            sourceVersion = sourceVersion,
            radiusMetres = radiusMetres,
            zoom = tile.z,
            x = tile.x,
            y = tile.y,
            kind = TILE_KIND_SOURCE,
            expectedFormat = TILE_FORMAT_GEOTIFF,
        )

    private fun cropKey(
        surveyId: Long,
        candidateId: Long,
        sourceVersion: String,
    ): PreDownloadTargetKey.Crop =
        PreDownloadTargetKey.Crop(
            surveyId = surveyId,
            candidateId = candidateId,
            sourceVersion = sourceVersion,
            requestSignature = candidateId.toString(),
        )

    private suspend fun acquireSurvey(
        surveyId: Long,
        candidates: List<PreDownloadTargetCandidate>,
        satelliteWork: List<SatelliteWork>,
        candidateTiles: List<CandidateTiles>,
        missingCrops: List<PreDownloadTargetCandidate>,
        satelliteTotal: Int,
        tileTotal: Int,
        total: Int,
        reDownloadRecommended: Boolean,
        progress: suspend (Data) -> Unit,
        manifestId: String?,
    ): PreDownloadOutcome {
        if (satelliteWork.isNotEmpty()) {
            val satelliteOk =
                downloadSatellite(
                    surveyId = surveyId,
                    regions = satelliteWork,
                    total = total,
                    progress = progress,
                )
            if (!satelliteOk) {
                return failure("Satellite download failed")
            }
        }

        val tileCount =
            downloadTiles(
                surveyId = surveyId,
                candidateTiles = candidateTiles,
                offset = satelliteTotal,
                total = total,
                progress = progress,
            )
        if (tileCount != tileTotal) {
            return failure("Tile download failed")
        }
        val derivedTileCount = precomputeLowZoomTiles(surveyId, candidates)
        val cropCount =
            downloadCrops(
                surveyId = surveyId,
                candidates = missingCrops,
                offset = satelliteTotal + tileTotal,
                total = total,
                progress = progress,
            )
        if (cropCount != missingCrops.size) {
            return failure("Crop download failed")
        }

        val builder =
            Data
                .Builder()
                .putString(KEY_MANIFEST_ID, manifestId)
                .putLong(KEY_BUNDLE_ID, 0L)
                .putInt(KEY_TILE_COUNT, tileCount + derivedTileCount)
                .putInt(KEY_CROP_COUNT, cropCount)
                .putInt(KEY_SATELLITE_REGION_COUNT, satelliteTotal)
                .putInt(KEY_CANDIDATE_COUNT, candidates.size)
                .putBoolean(KEY_RE_DOWNLOAD_RECOMMENDED, reDownloadRecommended)
        return PreDownloadOutcome.Success(builder.build())
    }

    private suspend fun buildCandidates(surveyId: Long): List<PreDownloadTargetCandidate> {
        val candidates = candidateDao.getCandidatesForSurvey(surveyId)
        val mineActive =
            claimDao
                .getClaims(surveyId, onlyActive = true)
                .first()
                .filter { it.isMine && it.isActive }
                .map { it.inferenceResultId }
                .toSet()
        return candidates
            .filter { it.inferenceResultId in mineActive }
            .mapNotNull { candidate ->
                val lat = candidate.geoCentroidLat
                val lon = candidate.geoCentroidLon
                if (lat == null || lon == null) {
                    null
                } else {
                    PreDownloadTargetCandidate(candidate.inferenceResultId, lat, lon)
                }
            }
    }

    private suspend fun downloadSatellite(
        surveyId: Long,
        regions: List<SatelliteWork>,
        total: Int,
        progress: suspend (Data) -> Unit,
    ): Boolean {
        var completed = 0
        for (region in regions) {
            val result =
                suspendCancellableCoroutine<Result<Unit>> { cont ->
                    val scope = CoroutineScope(cont.context)
                    offlineManagerWrapper.splitAndDownload(
                        clusterBboxes = listOf(region.bbox),
                        minZoom = PreDownloadTargetPlanner.SATELLITE_MIN_ZOOM,
                        maxZoom = PreDownloadTargetPlanner.SATELLITE_MAX_ZOOM,
                        progressCb = { p ->
                            scope.launch {
                                progress(
                                    workDataOf(
                                        KEY_DONE to completed + p.toInt(),
                                        KEY_TOTAL to total,
                                        KEY_PHASE to PHASE_SATELLITE,
                                    ),
                                )
                            }
                        },
                        completionCb = { result ->
                            if (cont.isActive) {
                                cont.resume(result)
                            }
                        },
                    )
                }
            if (result.isFailure) {
                val error = result.exceptionOrNull()
                if (error != null && error.isStorageFull()) {
                    throw StorageFullException(error)
                }
                return false
            }
            satelliteRegionStore.markCompleted(surveyId, region.signature)
            completed++
            progress(
                workDataOf(
                    KEY_DONE to completed,
                    KEY_TOTAL to total,
                    KEY_PHASE to PHASE_SATELLITE,
                ),
            )
        }
        return true
    }

    private suspend fun downloadTiles(
        surveyId: Long,
        candidateTiles: List<CandidateTiles>,
        offset: Int,
        total: Int,
        progress: suspend (Data) -> Unit,
    ): Int {
        val limiter = ioDispatcher.limitedParallelism(TILE_CONCURRENCY)
        var completed = 0
        var written = 0
        val lock = Mutex()
        coroutineScope {
            candidateTiles.forEach { work ->
                launch {
                    work.tiles.forEach { tile ->
                        val ok =
                            withContext(limiter) {
                                fetchTileWithRetry(
                                    surveyId,
                                    work.candidate.inferenceResultId,
                                    tile,
                                )
                            }
                        lock.withLock {
                            completed++
                            if (ok) {
                                written++
                            }
                            progress(
                                workDataOf(
                                    KEY_DONE to offset + completed,
                                    KEY_TOTAL to total,
                                    KEY_PHASE to PHASE_TILES,
                                ),
                            )
                        }
                    }
                }
            }
        }
        return written
    }

    private suspend fun precomputeLowZoomTiles(
        surveyId: Long,
        candidates: List<PreDownloadTargetCandidate>,
    ): Int =
        withContext(ioDispatcher) {
            val minZoom = LowZoomTileCompositor.MIN_ZOOM
            val sourceZoom = LowZoomTileCompositor.SOURCE_ZOOM
            var written = 0
            candidates.forEach { candidate ->
                for (zoom in minZoom until sourceZoom) {
                    lowZoomCompositor
                        .parentTiles(
                            surveyId,
                            candidate.inferenceResultId,
                            zoom,
                        ).forEach { parent ->
                            val bytes =
                                lowZoomCompositor.compose(
                                    surveyId,
                                    candidate.inferenceResultId,
                                    parent,
                                )
                            if (
                                !bytes.contentEquals(
                                    LocalFileRasterTileProvider.TRANSPARENT_PNG,
                                )
                            ) {
                                tileStore.write(
                                    surveyId,
                                    candidate.inferenceResultId,
                                    parent.z,
                                    parent.x,
                                    parent.y,
                                    bytes,
                                )
                                written++
                            }
                        }
                }
            }
            written
        }

    private suspend fun fetchTileWithRetry(
        surveyId: Long,
        candidateId: Long,
        tile: TileCoord,
    ): Boolean {
        var failures = 0
        while (failures < MAX_TILE_ATTEMPTS) {
            if (fetchTileOnce(surveyId, candidateId, tile)) {
                return true
            }
            failures++
        }
        return false
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun fetchTileOnce(
        surveyId: Long,
        candidateId: Long,
        tile: TileCoord,
    ): Boolean =
        try {
            val tms = TileMath.flipTileY(tile)
            val response =
                tileService.getCandidateTile(surveyId, candidateId, tile.z, tile.x, tms.y)
            val body = response.body()
            val bytes =
                when {
                    response.code() == HttpURLConnection.HTTP_NO_CONTENT ->
                        LocalFileRasterTileProvider.TRANSPARENT_PNG

                    response.isSuccessful &&
                        body != null &&
                        body.contentLength() <= MAX_TILE_BYTES ->
                        readBoundedBody(body, MAX_TILE_BYTES)

                    else -> null
                }
            if (bytes != null) {
                tileStore.write(surveyId, candidateId, tile.z, tile.x, tile.y, bytes)
                true
            } else {
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isStorageFull()) {
                throw StorageFullException(e)
            }
            false
        }

    private fun readBoundedBody(
        body: ResponseBody,
        maxBytes: Int,
    ): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        body.byteStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                if (output.size() + read > maxBytes) {
                    return null
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private suspend fun downloadCrops(
        surveyId: Long,
        candidates: List<PreDownloadTargetCandidate>,
        offset: Int,
        total: Int,
        progress: suspend (Data) -> Unit,
    ): Int {
        val limiter = ioDispatcher.limitedParallelism(TILE_CONCURRENCY)
        var completed = 0
        var written = 0
        val lock = Mutex()
        coroutineScope {
            candidates.forEach { candidate ->
                launch {
                    val ok =
                        withContext(limiter) {
                            fetchCrop(surveyId, candidate.inferenceResultId)
                        }
                    lock.withLock {
                        completed++
                        if (ok) {
                            written++
                        }
                        progress(
                            workDataOf(
                                KEY_DONE to offset + completed,
                                KEY_TOTAL to total,
                                KEY_PHASE to PHASE_CROPS,
                            ),
                        )
                    }
                }
            }
        }
        return written
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun fetchCrop(
        surveyId: Long,
        candidateId: Long,
    ): Boolean =
        try {
            val response = tileService.getCandidateCrop(candidateId)
            val body = response.body()
            if (response.isSuccessful && body != null && body.contentLength() <= MAX_CROP_BYTES) {
                val bytes = readBoundedBody(body, MAX_CROP_BYTES)
                if (bytes != null) {
                    val result =
                        candidateImageRepository.writeCrop(surveyId, candidateId, bytes)
                    result is CropWriteResult.Success
                } else {
                    false
                }
            } else {
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isStorageFull()) {
                throw StorageFullException(e)
            }
            false
        }

    private fun failureWithCode(
        code: String,
        message: String,
    ): PreDownloadOutcome.Failure =
        PreDownloadOutcome.Failure(
            Data
                .Builder()
                .putString(KEY_ERROR, message)
                .putString(KEY_ERROR_CODE, code)
                .build(),
        )

    private fun failureWithEstimate(
        code: String,
        message: String,
        estimate: PreDownloadSpaceEstimate,
    ): PreDownloadOutcome.Failure =
        PreDownloadOutcome.Failure(
            Data
                .Builder()
                .putString(KEY_ERROR, message)
                .putString(KEY_ERROR_CODE, code)
                .putLong(KEY_REQUIRED_BYTES, estimate.incrementalRequiredBytes)
                .putLong(KEY_AVAILABLE_BYTES, estimate.availableBytes)
                .putLong(KEY_RESERVE_BYTES, estimate.reserveBytes)
                .build(),
        )

    private fun failure(message: String): PreDownloadOutcome.Failure =
        PreDownloadOutcome.Failure(Data.Builder().putString(KEY_ERROR, message).build())

    companion object {
        const val KEY_ERROR_CODE = "errorCode"
        const val KEY_REQUIRED_BYTES = "requiredBytes"
        const val KEY_AVAILABLE_BYTES = "availableBytes"
        const val KEY_RESERVE_BYTES = "reserveBytes"
        const val CODE_INSUFFICIENT_DEVICE_SPACE = "INSUFFICIENT_DEVICE_SPACE"
        const val CODE_STORAGE_OPERATION_ACTIVE = "STORAGE_OPERATION_ACTIVE"
        const val CODE_STORAGE_FULL_WHILE_WRITING = "STORAGE_FULL_WHILE_WRITING"
        const val CODE_REPLACEMENT_CONFLICT = "REPLACEMENT_CONFLICT"
        const val CODE_REPLACEMENT_FAILED = "REPLACEMENT_FAILED"
        const val KEY_MANIFEST_ID = "manifestId"
        const val TILE_KIND_SOURCE = "SOURCE"
        const val TILE_FORMAT_GEOTIFF = "geotiff"
        const val KEY_BUNDLE_ID = "bundleId"
        const val KEY_TILE_COUNT = "tileCount"
        const val KEY_CROP_COUNT = "cropCount"
        const val KEY_SATELLITE_REGION_COUNT = "satelliteRegionCount"
        const val KEY_CANDIDATE_COUNT = "candidateCount"
        const val KEY_RE_DOWNLOAD_RECOMMENDED = "reDownloadRecommended"
        const val KEY_ERROR = "error"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_PHASE = "phase"
        const val PHASE_SATELLITE = "satellite"
        const val PHASE_TILES = "tiles"
        const val PHASE_CROPS = "crops"

        private const val TILE_CONCURRENCY = 6
        private const val MAX_TILE_ATTEMPTS = 3
        private const val READ_BUFFER_BYTES = 8 * 1024
        private const val MAX_TILE_BYTES = 16 * 1024 * 1024
        private const val MAX_CROP_BYTES = 2_097_152
    }
}

private class StorageFullException(
    cause: Throwable,
) : Exception(cause)

private fun Throwable.isStorageFull(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        val message = current.message?.lowercase()
        if (message != null &&
            (message.contains("enospc") || message.contains("no space left"))
        ) {
            return true
        }
        current = current.cause
    }
    return false
}
