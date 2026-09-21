package au.edu.fireballs.stage4.data.tiles

import android.os.Handler
import android.os.Looper
import au.edu.fireballs.stage4.data.repository.PreDownloadTargetKey
import com.mapbox.bindgen.ExpectedFactory
import com.mapbox.maps.AsyncOperationResultCallback
import com.mapbox.maps.OfflineRegionDownloadState
import com.mapbox.maps.OfflineRegionError
import com.mapbox.maps.OfflineRegionObserver
import com.mapbox.maps.OfflineRegionStatus
import com.mapbox.maps.OfflineRegionTilePyramidDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class OfflineManagerWrapperTest {
    private class FakeRegionHandle : OfflineRegionHandle {
        var observer: OfflineRegionObserver? = null
        var purgeCount = 0

        override var metadata: ByteArray? = null

        override fun setOfflineRegionObserver(observer: OfflineRegionObserver) {
            this.observer = observer
        }

        override fun setOfflineRegionDownloadState(state: OfflineRegionDownloadState) = Unit

        override fun purge(callback: AsyncOperationResultCallback) {
            purgeCount++
            callback.run(ExpectedFactory.createNone())
        }

        override fun setMetadata(
            metadata: ByteArray,
            callback: AsyncOperationResultCallback,
        ) {
            this.metadata = metadata
            callback.run(ExpectedFactory.createNone())
        }

        override val identifier: Long = 1L
    }

    private class FakeSource : OfflineRegionSource {
        val handles = mutableListOf<FakeRegionHandle>()

        override fun createOfflineRegion(
            definition: OfflineRegionTilePyramidDefinition,
            callback: (Result<OfflineRegionHandle>) -> Unit,
        ) {
            val handle = FakeRegionHandle()
            handles.add(handle)
            callback(Result.success(handle))
        }

        override fun getOfflineRegions(callback: (Result<List<OfflineRegionHandle>>) -> Unit) {
            callback(Result.success(handles))
        }
    }

    private fun build(maxTilesPerRegion: Int): Pair<OfflineManagerWrapper, FakeSource> {
        val source = FakeSource()
        val regionWrapper =
            OfflineRegionWrapper(
                source = source,
                mainHandler = Handler(Looper.getMainLooper()),
                definitionFactory = { _, _, _ ->
                    mock(OfflineRegionTilePyramidDefinition::class.java)
                },
            )
        return OfflineManagerWrapper(regionWrapper, maxTilesPerRegion) to source
    }

    private fun statusWith(
        required: Long,
        completed: Long,
    ): OfflineRegionStatus =
        OfflineRegionStatus(
            OfflineRegionDownloadState.ACTIVE,
            completed,
            0,
            completed,
            required,
            0,
            required,
            true,
        )

    private fun idleMain() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun satelliteTarget(): PreDownloadTargetKey.Satellite =
        PreDownloadTargetKey.Satellite(
            surveyId = 1L,
            sourceVersion = "v1",
            signature = "sig",
        )

    @Test
    fun regionWithinCapDownloadsOnce() {
        val (wrapper, source) = build(maxTilesPerRegion = 100)
        val completions = mutableListOf<Result<Unit>>()

        wrapper.splitAndDownload(
            clusterBboxes = listOf(Bbox(-0.001, -0.001, 0.001, 0.001)),
            minZoom = 0,
            maxZoom = 2,
            progressCb = {},
            completionCb = { completions.add(it) },
            target = satelliteTarget(),
        )
        idleMain()
        assertEquals(1, source.handles.size)
        source.handles[0].observer?.statusChanged(statusWith(10, 10))
        idleMain()

        assertEquals(1, completions.size)
        assertTrue(completions.single().isSuccess)
        assertEquals(1, source.handles.size)
    }

    @Test
    fun regionOverCapIsSplitIntoQuadrants() {
        val (wrapper, source) = build(maxTilesPerRegion = 100)
        val completions = mutableListOf<Result<Unit>>()

        wrapper.splitAndDownload(
            clusterBboxes = listOf(Bbox(-85.0, -180.0, 85.0, 180.0)),
            minZoom = 5,
            maxZoom = 5,
            progressCb = {},
            completionCb = { completions.add(it) },
            target = satelliteTarget(),
        )
        idleMain()
        assertEquals(1, source.handles.size)
        source.handles[0].observer?.statusChanged(statusWith(200, 0))
        idleMain()

        assertTrue(source.handles.size > 1)
        assertEquals(1, source.handles[0].purgeCount)
        var index = 1
        while (index < source.handles.size) {
            source.handles[index].observer?.statusChanged(statusWith(10, 10))
            idleMain()
            index++
        }

        assertEquals(1, completions.size)
        assertTrue(completions.single().isSuccess)
    }

    @Test
    fun failureEmitsExactlyOneFailure() {
        val (wrapper, source) = build(maxTilesPerRegion = 100)
        val completions = mutableListOf<Result<Unit>>()

        wrapper.splitAndDownload(
            clusterBboxes = listOf(Bbox(-0.001, -0.001, 0.001, 0.001)),
            minZoom = 0,
            maxZoom = 2,
            progressCb = {},
            completionCb = { completions.add(it) },
            target = satelliteTarget(),
        )
        idleMain()
        val error = mock(OfflineRegionError::class.java)
        `when`(error.message).thenReturn("boom")
        source.handles[0].observer?.errorOccurred(error)
        idleMain()

        assertEquals(1, completions.size)
        assertTrue(completions.single().isFailure)
    }

    @Test
    fun splitChildrenCarryOwnershipMetadata() {
        val (wrapper, source) = build(maxTilesPerRegion = 100)
        wrapper.splitAndDownload(
            clusterBboxes = listOf(Bbox(-85.0, -180.0, 85.0, 180.0)),
            minZoom = 5,
            maxZoom = 5,
            progressCb = {},
            completionCb = {},
            target = satelliteTarget(),
        )
        idleMain()

        assertTrue(source.handles.isNotEmpty())
        assertEquals(
            satelliteTarget(),
            decodeOfflineRegionTarget(source.handles[0].metadata),
        )
    }

    @Test
    fun nonFiniteBboxIsRejected() {
        val (wrapper, _) = build(maxTilesPerRegion = 100)
        assertThrows(IllegalArgumentException::class.java) {
            wrapper.splitAndDownload(
                clusterBboxes = listOf(Bbox(Double.NaN, 0.0, 1.0, 1.0)),
                minZoom = 0,
                maxZoom = 5,
                progressCb = {},
                completionCb = {},
                target = satelliteTarget(),
            )
        }
    }

    @Test
    fun unorderedBboxIsRejected() {
        val (wrapper, _) = build(maxTilesPerRegion = 100)
        assertThrows(IllegalArgumentException::class.java) {
            wrapper.splitAndDownload(
                clusterBboxes = listOf(Bbox(10.0, 0.0, -10.0, 1.0)),
                minZoom = 0,
                maxZoom = 5,
                progressCb = {},
                completionCb = {},
                target = satelliteTarget(),
            )
        }
    }

    @Test
    fun invertedZoomsAreRejected() {
        val (wrapper, _) = build(maxTilesPerRegion = 100)
        assertThrows(IllegalArgumentException::class.java) {
            wrapper.splitAndDownload(
                clusterBboxes = listOf(Bbox(-1.0, -1.0, 1.0, 1.0)),
                minZoom = 10,
                maxZoom = 5,
                progressCb = {},
                completionCb = {},
                target = satelliteTarget(),
            )
        }
    }
}
