package au.edu.fireballs.stage4.sync

import android.os.StatFs
import androidx.work.Data
import androidx.work.workDataOf
import au.edu.fireballs.stage4.data.local.OfflineBundleEntity
import au.edu.fireballs.stage4.data.local.dao.CandidateDao
import au.edu.fireballs.stage4.data.local.dao.ClaimDao
import au.edu.fireballs.stage4.data.local.dao.SurveyDao
import au.edu.fireballs.stage4.data.remote.TileService
import au.edu.fireballs.stage4.data.repository.CandidateImageRepository
import au.edu.fireballs.stage4.data.repository.ClaimRepository
import au.edu.fireballs.stage4.data.repository.ClaimResult
import au.edu.fireballs.stage4.data.repository.Stage4Repository
import au.edu.fireballs.stage4.data.repository.StorageCoordinator
import au.edu.fireballs.stage4.data.tiles.Bbox
import au.edu.fireballs.stage4.data.tiles.GeotiffRadiusRepository
import au.edu.fireballs.stage4.data.tiles.LocalFileRasterTileProvider
import au.edu.fireballs.stage4.data.tiles.LowZoomCompositor
import au.edu.fireballs.stage4.data.tiles.LowZoomTileCompositor
import au.edu.fireballs.stage4.data.tiles.OfflineBundleRepository
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
import java.io.File
import java.net.HttpURLConnection
import java.time.Instant
import java.util.concurrent.CancellationException
import kotlin.coroutines.resume
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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
    private val offlineBundleRepository: OfflineBundleRepository,
    private val candidateImageRepository: CandidateImageRepository,
    private val geotiffRadiusRepository: GeotiffRadiusRepository,
    private val satelliteRegionStore: SatelliteRegionStore,
    private val filesDir: File,
    private val storageCoordinator: StorageCoordinator? = null,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val freeBytes: () -> Long = { StatFs(filesDir.absolutePath).availableBytes },
) {
    private data class CandidatePoint(
        val inferenceResultId: Long,
        val lat: Double,
        val lon: Double,
    )

    private data class CandidateTiles(
        val candidate: CandidatePoint,
        val tiles: List<TileCoord>,
    )

    private data class SatelliteWork(
        val bbox: Bbox,
        val signature: String,
    )

    @Suppress("TooGenericExceptionCaught")
    suspend fun run(
        surveyId: Long,
        bufferMeters: Float,
        progress: suspend (Data) -> Unit,
    ): PreDownloadOutcome {
        val coordinator = storageCoordinator
        return if (coordinator == null) {
            runDownload(surveyId, bufferMeters, progress)
        } else {
            coordinator.withDownloadLease {
                runDownload(surveyId, bufferMeters, progress)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun runDownload(
        surveyId: Long,
        bufferMeters: Float,
        progress: suspend (Data) -> Unit,
    ): PreDownloadOutcome {
        val locallyCachedTask = surveyDao.getById(surveyId)?.latestTaskCreated

        val refresh = claimRepository.refreshClaimsToRoom(surveyId)
        if (refresh !is ClaimResult.Refreshed) {
            return failure("Failed to refresh claims")
        }

        val serverTask = stage4Repository.fetchLatestTaskCreated(surveyId)
        val forceRefresh =
            locallyCachedTask != null &&
                serverTask != null &&
                locallyCachedTask != serverTask
        if (forceRefresh) {
            cleanupSurvey(surveyId)
        }

        val candidates = buildCandidates(surveyId)
        val satelliteRadius = bufferMeters.toDouble()
        val geotiffRadius = geotiffRadiusRepository.getRadiusMeters().toDouble()
        val clusters = cluster(candidates, satelliteRadius)
        val satelliteWork =
            buildSatelliteWork(
                surveyId = surveyId,
                clusters = clusters,
                bufferRadius = satelliteRadius,
                forceRefresh = forceRefresh,
            )
        val candidateTiles =
            candidates.mapNotNull { candidate ->
                val allTiles = tilesForCandidate(candidate, geotiffRadius)
                val missingTiles =
                    if (
                        forceRefresh ||
                        !tileStore.hasCandidate(surveyId, candidate.inferenceResultId)
                    ) {
                        allTiles
                    } else {
                        allTiles.filterNot { tile ->
                            tileStore.contains(
                                surveyId,
                                candidate.inferenceResultId,
                                tile.z,
                                tile.x,
                                tile.y,
                            )
                        }
                    }
                missingTiles
                    .takeIf { it.isNotEmpty() }
                    ?.let { CandidateTiles(candidate, it) }
            }
        val missingCrops =
            candidates.filter { candidate ->
                forceRefresh ||
                    candidateImageRepository.getLocalCropImageFile(
                        surveyId,
                        candidate.inferenceResultId,
                    ) == null
            }
        val satelliteTotal = satelliteWork.size
        val tileTotal = candidateTiles.sumOf { it.tiles.size }
        val cropTotal = missingCrops.size
        val total = satelliteTotal + tileTotal + cropTotal

        if (estimateRequiredBytes(tileTotal, cropTotal, satelliteTotal) > freeBytes()) {
            return failure("Insufficient storage")
        }
        if (estimatedTileBytes(tileTotal) > tileStore.remainingQuotaBytes()) {
            return failure("Insufficient storage")
        }

        return try {
            acquireSurvey(
                surveyId = surveyId,
                candidates = candidates,
                satelliteWork = satelliteWork,
                candidateTiles = candidateTiles,
                missingCrops = missingCrops,
                satelliteTotal = satelliteTotal,
                tileTotal = tileTotal,
                total = total,
                bufferMeters = bufferMeters,
                reDownloadRecommended = forceRefresh,
                progress = progress,
            )
        } catch (e: CancellationException) {
            cleanupSurvey(surveyId)
            throw e
        } catch (e: Exception) {
            cleanupSurvey(surveyId)
            throw e
        }
    }

    private suspend fun acquireSurvey(
        surveyId: Long,
        candidates: List<CandidatePoint>,
        satelliteWork: List<SatelliteWork>,
        candidateTiles: List<CandidateTiles>,
        missingCrops: List<CandidatePoint>,
        satelliteTotal: Int,
        tileTotal: Int,
        total: Int,
        bufferMeters: Float,
        reDownloadRecommended: Boolean,
        progress: suspend (Data) -> Unit,
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

        val totalBytes = computeTotalBytes(surveyId)
        val bundleId =
            offlineBundleRepository.insertBundle(
                OfflineBundleEntity(
                    surveyId = surveyId,
                    created = Instant.now().toString(),
                    totalBytes = totalBytes,
                    tileCount = tileCount + derivedTileCount,
                    satelliteRegionCount = satelliteTotal,
                    candidateCount = candidates.size,
                    bufferMeters = bufferMeters,
                ),
            )

        val builder =
            Data
                .Builder()
                .putLong(KEY_BUNDLE_ID, bundleId)
                .putInt(KEY_TILE_COUNT, tileCount + derivedTileCount)
                .putInt(KEY_CROP_COUNT, cropCount)
                .putInt(KEY_SATELLITE_REGION_COUNT, satelliteTotal)
                .putInt(KEY_CANDIDATE_COUNT, candidates.size)
                .putBoolean(KEY_RE_DOWNLOAD_RECOMMENDED, reDownloadRecommended)
        return PreDownloadOutcome.Success(builder.build())
    }

    private suspend fun cleanupSurvey(surveyId: Long) {
        tileStore.deleteSurveyTiles(surveyId)
        File(filesDir, "$CROP_DIR/$surveyId").deleteRecursively()
        satelliteRegionStore.deleteForSurvey(surveyId)
    }

    private suspend fun buildCandidates(surveyId: Long): List<CandidatePoint> {
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
                    CandidatePoint(candidate.inferenceResultId, lat, lon)
                }
            }
    }

    private fun cluster(
        candidates: List<CandidatePoint>,
        bufferRadius: Double,
    ): List<List<CandidatePoint>> {
        val parent = IntArray(candidates.size) { it }

        fun find(index: Int): Int {
            var root = index
            while (parent[root] != root) {
                root = parent[root]
            }
            var current = index
            while (parent[current] != current) {
                val next = parent[current]
                parent[current] = root
                current = next
            }
            return root
        }

        fun union(
            a: Int,
            b: Int,
        ) {
            val rootA = find(a)
            val rootB = find(b)
            if (rootA != rootB) {
                parent[rootB] = rootA
            }
        }

        for (i in candidates.indices) {
            for (j in i + 1 until candidates.size) {
                if (distanceMeters(candidates[i], candidates[j]) < 2.0 * bufferRadius) {
                    union(i, j)
                }
            }
        }
        val groups = mutableMapOf<Int, MutableList<CandidatePoint>>()
        candidates.forEachIndexed { index, point ->
            groups.getOrPut(find(index)) { mutableListOf() }.add(point)
        }
        return groups.values.toList()
    }

    private fun distanceMeters(
        a: CandidatePoint,
        b: CandidatePoint,
    ): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.lon - a.lon)
        val h =
            sin(dLat / 2.0) * sin(dLat / 2.0) +
                cos(lat1) * cos(lat2) * sin(dLon / 2.0) * sin(dLon / 2.0)
        return 2.0 * EARTH_RADIUS_METERS * asin(sqrt(h))
    }

    private fun unionBbox(
        cluster: List<CandidatePoint>,
        bufferRadius: Double,
    ): Bbox {
        var minLat = Double.POSITIVE_INFINITY
        var minLon = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY
        var maxLon = Double.NEGATIVE_INFINITY
        cluster.forEach { candidate ->
            val bbox = TileMath.bufferBbox(candidate.lat, candidate.lon, bufferRadius)
            minLat = minOf(minLat, bbox.minLat)
            minLon = minOf(minLon, bbox.minLon)
            maxLat = maxOf(maxLat, bbox.maxLat)
            maxLon = maxOf(maxLon, bbox.maxLon)
        }
        return Bbox(minLat, minLon, maxLat, maxLon)
    }

    private suspend fun buildSatelliteWork(
        surveyId: Long,
        clusters: List<List<CandidatePoint>>,
        bufferRadius: Double,
        forceRefresh: Boolean,
    ): List<SatelliteWork> {
        val work = mutableListOf<SatelliteWork>()
        for (cluster in clusters) {
            val bbox = unionBbox(cluster, bufferRadius)
            val signature = satelliteSignature(cluster, bbox)
            if (forceRefresh || !satelliteRegionStore.contains(surveyId, signature)) {
                work += SatelliteWork(bbox, signature)
            }
        }
        return work
    }

    private fun satelliteSignature(
        cluster: List<CandidatePoint>,
        bbox: Bbox,
    ): String =
        buildString {
            append("sat:")
            append(cluster.map { it.inferenceResultId }.sorted().joinToString(","))
            append(':')
            append(bbox.minLat.toBits())
            append(',')
            append(bbox.minLon.toBits())
            append(',')
            append(bbox.maxLat.toBits())
            append(',')
            append(bbox.maxLon.toBits())
            append(':')
            append(SATELLITE_MIN_ZOOM)
            append('-')
            append(SATELLITE_MAX_ZOOM)
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
                        minZoom = SATELLITE_MIN_ZOOM,
                        maxZoom = SATELLITE_MAX_ZOOM,
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
        candidates: List<CandidatePoint>,
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
        candidates: List<CandidatePoint>,
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
                    val file = File(filesDir, "$CROP_DIR/$surveyId/$candidateId.jpg")
                    file.parentFile?.mkdirs()
                    file.writeBytes(bytes)
                    true
                } else {
                    false
                }
            } else {
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }

    private fun tilesForCandidate(
        candidate: CandidatePoint,
        bufferRadius: Double,
    ): List<TileCoord> {
        val bbox = TileMath.bufferBbox(candidate.lat, candidate.lon, bufferRadius)
        return buildList {
            for (z in TILE_MIN_ZOOM..TILE_MAX_ZOOM) {
                addAll(
                    TileMath.tilesForBbox(
                        bbox.minLat,
                        bbox.minLon,
                        bbox.maxLat,
                        bbox.maxLon,
                        z,
                    ),
                )
            }
        }
    }

    private fun estimateRequiredBytes(
        tileCount: Int,
        cropCount: Int,
        satelliteRegionCount: Int,
    ): Long {
        val requiredMb =
            tileCount * MB_PER_GEOTIFF_TILE +
                cropCount * MB_PER_CROP +
                satelliteRegionCount * MB_PER_SATELLITE_REGION
        return (requiredMb * BYTES_PER_MB).toLong()
    }

    private fun estimatedTileBytes(tileCount: Int): Long =
        (tileCount * MB_PER_GEOTIFF_TILE * BYTES_PER_MB).toLong()

    private fun computeTotalBytes(surveyId: Long): Long {
        val tilesDir = tileStore.surveyTilesDirectory(surveyId)
        val cropsDir = File(filesDir, "$CROP_DIR/$surveyId")
        return dirSize(tilesDir) + dirSize(cropsDir)
    }

    private fun dirSize(dir: File): Long =
        if (dir.isDirectory) {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else {
            0L
        }

    private fun failure(message: String): PreDownloadOutcome.Failure =
        PreDownloadOutcome.Failure(Data.Builder().putString(KEY_ERROR, message).build())

    companion object {
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

        private const val SATELLITE_MIN_ZOOM = 18
        private const val SATELLITE_MAX_ZOOM = 22
        private const val TILE_MIN_ZOOM = 20
        private const val TILE_MAX_ZOOM = 22
        private const val TILE_CONCURRENCY = 6
        private const val MAX_TILE_ATTEMPTS = 3
        private const val READ_BUFFER_BYTES = 8 * 1024
        private const val MAX_TILE_BYTES = 16 * 1024 * 1024
        private const val MAX_CROP_BYTES = 2_097_152
        private const val EARTH_RADIUS_METERS = 6_371_000.0
        private const val MB_PER_GEOTIFF_TILE = 2.0
        private const val MB_PER_CROP = 1.0
        private const val MB_PER_SATELLITE_REGION = 50.0
        private const val BYTES_PER_MB = 1024.0 * 1024.0
        private const val CROP_DIR = "crops"
    }
}
