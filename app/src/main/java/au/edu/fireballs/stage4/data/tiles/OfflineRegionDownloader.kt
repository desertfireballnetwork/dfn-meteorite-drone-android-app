package au.edu.fireballs.stage4.data.tiles

interface OfflineRegionDownloader {
    fun downloadSatelliteRegion(
        bbox: Bbox,
        minZoom: Int,
        maxZoom: Int,
        progressCb: (Double) -> Unit,
        completionCb: (Result<Unit>) -> Unit,
    )
}
