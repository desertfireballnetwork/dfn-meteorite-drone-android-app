package au.edu.fireballs.stage4.data.tiles

import au.edu.fireballs.stage4.BuildConfig

class OfflineManagerWrapper(
    private val regionWrapper: OfflineRegionDownloader,
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
        require(clusterBboxes.size <= MAX_CLUSTERS) { "Too many clusters" }
        clusterBboxes.forEach(::validateBbox)

        val regions = mutableListOf<Bbox>()
        try {
            for (bbox in clusterBboxes) {
                collectRegions(bbox, minZoom, maxZoom, regions, depth = 0)
            }
        } catch (error: IllegalStateException) {
            completionCb(Result.failure(error))
            return
        }
        if (regions.isEmpty()) {
            completionCb(Result.success(Unit))
            return
        }
        val totalTiles = regions.sumOf { estimateTiles(it, minZoom, maxZoom) }
        if (regions.size > MAX_REGIONS || totalTiles > MAX_TOTAL_TILES) {
            completionCb(
                Result.failure(
                    IllegalStateException("Offline plan exceeds configured limits"),
                ),
            )
            return
        }

        var completed = 0
        var completedTiles = 0L
        var terminal = false
        var lastProgress = 0.0

        fun report(progress: Double) {
            if (progress > lastProgress) {
                lastProgress = progress
                progressCb(progress.coerceIn(0.0, 1.0))
            }
        }

        fun startNext() {
            if (terminal || completed >= regions.size) {
                return
            }
            val region = regions[completed]
            val regionTiles = estimateTiles(region, minZoom, maxZoom)
            regionWrapper.downloadSatelliteRegion(
                region,
                minZoom,
                maxZoom,
                { progress ->
                    report(
                        (completedTiles + regionTiles * progress.coerceIn(0.0, 1.0)) /
                            totalTiles.toDouble(),
                    )
                },
                { result ->
                    if (terminal) {
                        return@downloadSatelliteRegion
                    }
                    if (result.isFailure) {
                        terminal = true
                        completionCb(result)
                        return@downloadSatelliteRegion
                    }
                    completedTiles += regionTiles
                    completed++
                    report(completedTiles.toDouble() / totalTiles.toDouble())
                    if (completed == regions.size) {
                        terminal = true
                        completionCb(Result.success(Unit))
                    } else {
                        startNext()
                    }
                },
            )
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
        out: MutableList<Bbox>,
        depth: Int,
    ) {
        if (bbox.minLat == bbox.maxLat || bbox.minLon == bbox.maxLon) {
            check(estimateTiles(bbox, minZoom, maxZoom) <= maxTilesPerRegion) {
                "Unable to split degenerate offline region below the configured tile cap"
            }
            out.add(bbox)
            return
        }
        if (depth > MAX_SPLIT_DEPTH || out.size >= MAX_REGIONS) {
            check(estimateTiles(bbox, minZoom, maxZoom) <= maxTilesPerRegion) {
                "Unable to split offline region below the configured tile cap"
            }
            out.add(bbox)
            return
        }
        if (estimateTiles(bbox, minZoom, maxZoom) <= maxTilesPerRegion) {
            out.add(bbox)
            return
        }
        val midLat = (bbox.minLat + bbox.maxLat) / 2.0
        val midLon = (bbox.minLon + bbox.maxLon) / 2.0
        collectRegions(
            Bbox(bbox.minLat, bbox.minLon, midLat, midLon),
            minZoom,
            maxZoom,
            out,
            depth + 1,
        )
        collectRegions(
            Bbox(bbox.minLat, midLon, midLat, bbox.maxLon),
            minZoom,
            maxZoom,
            out,
            depth + 1,
        )
        collectRegions(
            Bbox(midLat, bbox.minLon, bbox.maxLat, midLon),
            minZoom,
            maxZoom,
            out,
            depth + 1,
        )
        collectRegions(
            Bbox(midLat, midLon, bbox.maxLat, bbox.maxLon),
            minZoom,
            maxZoom,
            out,
            depth + 1,
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
        private const val MAX_CLUSTERS = 100
        private const val MAX_REGIONS = 256
        private const val MAX_SPLIT_DEPTH = 12
        private const val MAX_TOTAL_TILES = 2_000_000L
    }
}
