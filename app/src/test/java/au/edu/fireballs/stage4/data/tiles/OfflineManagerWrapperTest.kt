package au.edu.fireballs.stage4.data.tiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineManagerWrapperTest {
    private class FakeDownloader : OfflineRegionDownloader {
        val downloadedBboxes = mutableListOf<Bbox>()
        var lastProgress = 0.0

        override fun downloadSatelliteRegion(
            bbox: Bbox,
            minZoom: Int,
            maxZoom: Int,
            progressCb: (Double) -> Unit,
            completionCb: (Result<Unit>) -> Unit,
        ) {
            downloadedBboxes.add(bbox)
            progressCb(1.0)
            completionCb(Result.success(Unit))
        }
    }

    @Test
    fun oversizedBboxIsRecursivelySplitUnderCap() {
        val fake = FakeDownloader()
        val wrapper = OfflineManagerWrapper(fake, maxTilesPerRegion = 100)
        val bbox = Bbox(minLat = -85.0, minLon = -180.0, maxLat = 85.0, maxLon = 180.0)

        wrapper.splitAndDownload(
            clusterBboxes = listOf(bbox),
            minZoom = 5,
            maxZoom = 5,
            progressCb = {},
            completionCb = {},
        )

        assertTrue(fake.downloadedBboxes.size > 1)
        for (sub in fake.downloadedBboxes) {
            assertTrue(estimateTiles(sub, 5, 5) <= 100)
        }
    }

    @Test
    fun smallBboxIsDownloadedDirectly() {
        val fake = FakeDownloader()
        val wrapper = OfflineManagerWrapper(fake, maxTilesPerRegion = 100)
        val bbox = Bbox(minLat = -0.001, minLon = -0.001, maxLat = 0.001, maxLon = 0.001)

        wrapper.splitAndDownload(
            clusterBboxes = listOf(bbox),
            minZoom = 0,
            maxZoom = 2,
            progressCb = {},
            completionCb = {},
        )

        assertEquals(1, fake.downloadedBboxes.size)
        assertEquals(bbox, fake.downloadedBboxes.single())
    }

    private fun estimateTiles(
        bbox: Bbox,
        minZoom: Int,
        maxZoom: Int,
    ): Int {
        var total = 0
        for (z in minZoom..maxZoom) {
            total +=
                TileMath
                    .tilesForBbox(bbox.minLat, bbox.minLon, bbox.maxLat, bbox.maxLon, z)
                    .size
        }
        return total
    }
}
