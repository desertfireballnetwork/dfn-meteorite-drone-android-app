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
        val regions = mutableListOf<Bbox>()
        for (bbox in clusterBboxes) {
            collectRegions(bbox, minZoom, maxZoom, regions)
        }
        if (regions.isEmpty()) {
            completionCb(Result.success(Unit))
            return
        }
        val totalTiles = regions.sumOf { estimateTiles(it, minZoom, maxZoom) }
        var completedTiles = 0L
        var completed = 0
        var failed = false
        for (region in regions) {
            val regionTiles = estimateTiles(region, minZoom, maxZoom)
            regionWrapper.downloadSatelliteRegion(
                region,
                minZoom,
                maxZoom,
                { progress ->
                    val overall =
                        (completedTiles + regionTiles * progress.coerceIn(0.0, 1.0)) /
                            totalTiles.toDouble()
                    progressCb(overall.coerceIn(0.0, 1.0))
                },
                { result ->
                    completed++
                    if (result.isFailure && !failed) {
                        failed = true
                        completionCb(result)
                    } else {
                        completedTiles += regionTiles
                        if (completed == regions.size) {
                            completionCb(Result.success(Unit))
                        }
                    }
                },
            )
        }
    }

    private fun collectRegions(
        bbox: Bbox,
        minZoom: Int,
        maxZoom: Int,
        out: MutableList<Bbox>,
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

    private fun estimateTiles(
        bbox: Bbox,
        minZoom: Int,
        maxZoom: Int,
    ): Long {
        var total = 0L
        for (z in minZoom..maxZoom) {
            total +=
                TileMath
                    .tilesForBbox(bbox.minLat, bbox.minLon, bbox.maxLat, bbox.maxLon, z)
                    .size
        }
        return total
    }
}
