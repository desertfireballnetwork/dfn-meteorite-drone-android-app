package au.edu.fireballs.stage4.data.tiles

import au.edu.fireballs.stage4.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean

class OfflineManagerWrapper(
    private val regionWrapper: OfflineRegionWrapper,
    private val maxTilesPerRegion: Int = BuildConfig.MAPBOX_MAX_TILES_PER_REGION,
) {
    fun splitAndDownload(
        clusterBboxes: List<Bbox>,
        minZoom: Int,
        maxZoom: Int,
        progressCb: (Double) -> Unit,
        completionCb: (Result<Unit>) -> Unit,
    ) {
        validateZooms(minZoom, maxZoom)
        require(maxTilesPerRegion > 0) { "maxTilesPerRegion must be positive" }
        clusterBboxes.forEach(::validateBbox)

        val queue = ArrayDeque<Bbox>()
        for (bbox in clusterBboxes) {
            collectRegions(bbox, minZoom, maxZoom, queue)
        }
        if (queue.isEmpty()) {
            completionCb(Result.success(Unit))
            return
        }
        val totalTiles = queue.sumOf { estimateTiles(it, minZoom, maxZoom) }

        val terminal = AtomicBoolean(false)
        val stateLock = Any()
        var completedTiles = 0L
        var lastProgress = 0.0

        fun report(progress: Double) {
            val clamped = progress.coerceIn(0.0, 1.0)
            if (clamped > lastProgress) {
                lastProgress = clamped
                progressCb(clamped)
            }
        }

        fun startNext() {
            synchronized(stateLock) {
                if (terminal.get()) {
                    return
                }
                val bbox = queue.removeFirstOrNull()
                if (bbox == null) {
                    terminal.set(true)
                    completionCb(Result.success(Unit))
                    return
                }
                val regionTiles = estimateTiles(bbox, minZoom, maxZoom)
                var needsSplit = false
                regionWrapper.downloadSatelliteRegion(
                    bbox,
                    minZoom,
                    maxZoom,
                    { actualCount ->
                        synchronized(stateLock) {
                            if (!terminal.get() && actualCount > maxTilesPerRegion && !needsSplit) {
                                needsSplit = true
                                regionWrapper.cancelDownload()
                            }
                        }
                    },
                    { progress ->
                        synchronized(stateLock) {
                            if (!terminal.get()) {
                                report(
                                    (
                                        completedTiles +
                                            regionTiles * progress.coerceIn(0.0, 1.0)
                                    ) / totalTiles.toDouble(),
                                )
                            }
                        }
                    },
                    { result ->
                        synchronized(stateLock) {
                            if (terminal.get()) {
                                return@downloadSatelliteRegion
                            }
                            if (needsSplit) {
                                val quadrants = splitQuadrants(bbox)
                                if (quadrants.isEmpty()) {
                                    completedTiles += regionTiles
                                    report(completedTiles.toDouble() / totalTiles.toDouble())
                                } else {
                                    quadrants.forEach { queue.addFirst(it) }
                                }
                                startNext()
                            } else if (result.isFailure) {
                                terminal.set(true)
                                completionCb(result)
                            } else {
                                completedTiles += regionTiles
                                report(completedTiles.toDouble() / totalTiles.toDouble())
                                startNext()
                            }
                        }
                    },
                )
            }
        }
        startNext()
    }

    private fun validateZooms(
        minZoom: Int,
        maxZoom: Int,
    ) {
        require(minZoom in 0..MAX_ZOOM && maxZoom in 0..MAX_ZOOM) { "Zoom out of range" }
        require(minZoom <= maxZoom) { "minZoom must not exceed maxZoom" }
    }

    private fun validateBbox(bbox: Bbox) {
        require(
            bbox.minLat.isFinite() &&
                bbox.minLon.isFinite() &&
                bbox.maxLat.isFinite() &&
                bbox.maxLon.isFinite(),
        ) {
            "Bbox coordinates must be finite"
        }
        require(
            bbox.minLat in -90.0..90.0 &&
                bbox.maxLat in -90.0..90.0,
        ) {
            "Latitude out of range"
        }
        require(
            bbox.minLon in -180.0..180.0 &&
                bbox.maxLon in -180.0..180.0,
        ) {
            "Longitude out of range"
        }
        require(
            bbox.minLat <= bbox.maxLat &&
                bbox.minLon <= bbox.maxLon,
        ) {
            "Bbox must be ordered and must not cross the antimeridian"
        }
    }

    private fun collectRegions(
        bbox: Bbox,
        minZoom: Int,
        maxZoom: Int,
        out: ArrayDeque<Bbox>,
    ) {
        if (estimateTiles(bbox, minZoom, maxZoom) <= maxTilesPerRegion ||
            bbox.minLat == bbox.maxLat ||
            bbox.minLon == bbox.maxLon
        ) {
            out.add(bbox)
            return
        }
        val midLat = (bbox.minLat + bbox.maxLat) / 2.0
        val midLon = (bbox.minLon + bbox.maxLon) / 2.0
        collectRegions(Bbox(bbox.minLat, bbox.minLon, midLat, midLon), minZoom, maxZoom, out)
        collectRegions(Bbox(bbox.minLat, midLon, midLat, bbox.maxLon), minZoom, maxZoom, out)
        collectRegions(Bbox(midLat, bbox.minLon, bbox.maxLat, midLon), minZoom, maxZoom, out)
        collectRegions(Bbox(midLat, midLon, bbox.maxLat, bbox.maxLon), minZoom, maxZoom, out)
    }

    private fun splitQuadrants(bbox: Bbox): List<Bbox> {
        if (bbox.minLat == bbox.maxLat || bbox.minLon == bbox.maxLon) {
            return emptyList()
        }
        val midLat = (bbox.minLat + bbox.maxLat) / 2.0
        val midLon = (bbox.minLon + bbox.maxLon) / 2.0
        return listOf(
            Bbox(bbox.minLat, bbox.minLon, midLat, midLon),
            Bbox(bbox.minLat, midLon, midLat, bbox.maxLon),
            Bbox(midLat, bbox.minLon, bbox.maxLat, midLon),
            Bbox(midLat, midLon, bbox.maxLat, bbox.maxLon),
        )
    }

    private fun estimateTiles(
        bbox: Bbox,
        minZoom: Int,
        maxZoom: Int,
    ): Long {
        var total = 0L
        for (z in minZoom..maxZoom) {
            total +=
                TileMath
                    .tileCountForBbox(bbox.minLat, bbox.minLon, bbox.maxLat, bbox.maxLon, z)
        }
        return total
    }

    companion object {
        private const val MAX_ZOOM = 22
    }
}
