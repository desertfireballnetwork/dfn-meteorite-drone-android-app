package au.edu.fireballs.stage4.data.tiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineManagerWrapperTest {
    private class FakeDownloader(
        private val failFirst: Boolean = false,
        private val duplicateCompletions: Boolean = false,
        private val progressAfterCompletion: Boolean = false,
    ) : OfflineRegionDownloader {
        val downloadedBboxes = mutableListOf<Bbox>()
        var lastProgress = 0.0
        private var callCount = 0

        override fun downloadSatelliteRegion(
            bbox: Bbox,
            minZoom: Int,
            maxZoom: Int,
            progressCb: (Double) -> Unit,
            completionCb: (Result<Unit>) -> Unit,
        ) {
            downloadedBboxes.add(bbox)
            progressCb(1.0)
            callCount++
            if (failFirst && callCount == 1) {
                completionCb(Result.failure(IllegalStateException("boom")))
            } else {
                completionCb(Result.success(Unit))
            }
            if (duplicateCompletions) {
                completionCb(Result.success(Unit))
            }
            if (progressAfterCompletion) {
                progressCb(0.5)
            }
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

    @Test
    fun failureThenLaterSuccessEmitsExactlyOneFailure() {
        val failing = FakeDownloader(failFirst = true)
        val wrapper = OfflineManagerWrapper(failing, maxTilesPerRegion = 100)
        val bbox = Bbox(minLat = -85.0, minLon = -180.0, maxLat = 85.0, maxLon = 180.0)
        val completions = mutableListOf<Result<Unit>>()

        wrapper.splitAndDownload(
            clusterBboxes = listOf(bbox),
            minZoom = 5,
            maxZoom = 5,
            progressCb = {},
            completionCb = { completions.add(it) },
        )

        assertEquals(1, completions.size)
        assertTrue(completions.single().isFailure)
    }

    @Test
    fun nonFiniteBboxIsRejected() {
        val wrapper = OfflineManagerWrapper(FakeDownloader(), maxTilesPerRegion = 100)
        assertThrows(IllegalArgumentException::class.java) {
            wrapper.splitAndDownload(
                clusterBboxes = listOf(Bbox(Double.NaN, 0.0, 1.0, 1.0)),
                minZoom = 0,
                maxZoom = 5,
                progressCb = {},
                completionCb = {},
            )
        }
    }

    @Test
    fun unorderedBboxIsRejected() {
        val wrapper = OfflineManagerWrapper(FakeDownloader(), maxTilesPerRegion = 100)
        assertThrows(IllegalArgumentException::class.java) {
            wrapper.splitAndDownload(
                clusterBboxes =
                    listOf(
                        Bbox(minLat = 10.0, minLon = 0.0, maxLat = -10.0, maxLon = 1.0),
                    ),
                minZoom = 0,
                maxZoom = 5,
                progressCb = {},
                completionCb = {},
            )
        }
    }

    @Test
    fun invertedZoomsAreRejected() {
        val wrapper = OfflineManagerWrapper(FakeDownloader(), maxTilesPerRegion = 100)
        assertThrows(IllegalArgumentException::class.java) {
            wrapper.splitAndDownload(
                clusterBboxes = listOf(Bbox(-1.0, -1.0, 1.0, 1.0)),
                minZoom = 10,
                maxZoom = 5,
                progressCb = {},
                completionCb = {},
            )
        }
    }

    @Test
    fun tooManyClustersAreRejected() {
        val wrapper = OfflineManagerWrapper(FakeDownloader(), maxTilesPerRegion = 100)
        val clusters = List(101) { Bbox(-1.0, -1.0, 1.0, 1.0) }
        assertThrows(IllegalArgumentException::class.java) {
            wrapper.splitAndDownload(
                clusterBboxes = clusters,
                minZoom = 0,
                maxZoom = 5,
                progressCb = {},
                completionCb = {},
            )
        }
    }

    @Test
    fun degenerateOversizedRegionFailsInsteadOfViolatingCap() {
        val fake = FakeDownloader()
        val wrapper = OfflineManagerWrapper(fake, maxTilesPerRegion = 10)
        val completions = mutableListOf<Result<Unit>>()

        wrapper.splitAndDownload(
            clusterBboxes =
                listOf(
                    Bbox(minLat = 0.0, minLon = -180.0, maxLat = 0.0, maxLon = 180.0),
                ),
            minZoom = 5,
            maxZoom = 5,
            progressCb = {},
            completionCb = { completions.add(it) },
        )

        assertEquals(1, completions.size)
        assertTrue(completions.single().isFailure)
        assertTrue(fake.downloadedBboxes.isEmpty())
    }

    @Test
    fun duplicateCompletionEmitsExactlyOneOverallCompletion() {
        val fake = FakeDownloader(duplicateCompletions = true)
        val wrapper = OfflineManagerWrapper(fake, maxTilesPerRegion = 100)
        val bbox = Bbox(minLat = -0.001, minLon = -0.001, maxLat = 0.001, maxLon = 0.001)
        val completions = mutableListOf<Result<Unit>>()

        wrapper.splitAndDownload(
            clusterBboxes = listOf(bbox),
            minZoom = 0,
            maxZoom = 2,
            progressCb = {},
            completionCb = { completions.add(it) },
        )

        assertEquals(1, completions.size)
        assertTrue(completions.single().isSuccess)
    }

    @Test
    fun progressAfterCompletionIsSuppressed() {
        val fake = FakeDownloader(progressAfterCompletion = true)
        val wrapper = OfflineManagerWrapper(fake, maxTilesPerRegion = 100)
        val bbox = Bbox(minLat = -0.001, minLon = -0.001, maxLat = 0.001, maxLon = 0.001)
        val events = mutableListOf<String>()

        wrapper.splitAndDownload(
            clusterBboxes = listOf(bbox),
            minZoom = 0,
            maxZoom = 2,
            progressCb = { events.add("progress:$it") },
            completionCb = { events.add("complete:${it.isSuccess}") },
        )

        assertEquals(1, events.count { it.startsWith("complete") })
        assertEquals(events.last(), events.first { it.startsWith("complete") })
    }

    @Test
    fun progressAfterFailureIsSuppressed() {
        val fake = FakeDownloader(failFirst = true, progressAfterCompletion = true)
        val wrapper = OfflineManagerWrapper(fake, maxTilesPerRegion = 100)
        val bbox = Bbox(minLat = -85.0, minLon = -180.0, maxLat = 85.0, maxLon = 180.0)
        val events = mutableListOf<String>()

        wrapper.splitAndDownload(
            clusterBboxes = listOf(bbox),
            minZoom = 5,
            maxZoom = 5,
            progressCb = { events.add("progress:$it") },
            completionCb = { events.add("complete:${it.isSuccess}") },
        )

        assertEquals(1, events.count { it.startsWith("complete") })
        assertTrue(events.last().startsWith("complete:false"))
    }

    private fun estimateTiles(
        bbox: Bbox,
        minZoom: Int,
        maxZoom: Int,
    ): Long =
        (minZoom..maxZoom).sumOf { zoom ->
            TileMath.tileCountForBbox(
                bbox.minLat,
                bbox.minLon,
                bbox.maxLat,
                bbox.maxLon,
                zoom,
            )
        }
}
