package au.edu.fireballs.stage4.data.tiles

import android.os.Handler
import android.os.Looper
import com.mapbox.bindgen.ExpectedFactory
import com.mapbox.maps.AsyncOperationResultCallback
import com.mapbox.maps.OfflineRegionDownloadState
import com.mapbox.maps.OfflineRegionError
import com.mapbox.maps.OfflineRegionObserver
import com.mapbox.maps.OfflineRegionStatus
import com.mapbox.maps.OfflineRegionTilePyramidDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
class OfflineRegionWrapperTest {
    private val region = mock(OfflineRegionHandle::class.java)
    private val source = FakeSource()
    private val wrapper =
        OfflineRegionWrapper(
            source = source,
            mainHandler = Handler(Looper.getMainLooper()),
            definitionFactory = { _, _, _ -> mock(OfflineRegionTilePyramidDefinition::class.java) },
        )

    @Test
    fun cancelPurgesActiveRegionBeforeCompleting() {
        val completions = mutableListOf<Result<Unit>>()
        wrapper.downloadSatelliteRegion(
            bbox(),
            0,
            10,
            resourceCountCb = {},
            progressCb = {},
            completionCb = { completions.add(it) },
        )
        source.createCallback(Result.success(region))
        idleMain()

        wrapper.cancelDownload()
        verify(region).setOfflineRegionDownloadState(OfflineRegionDownloadState.INACTIVE)

        val purgeCaptor = argumentCaptor<AsyncOperationResultCallback>()
        verify(region).purge(purgeCaptor.capture())
        purgeCaptor.firstValue.run(ExpectedFactory.createNone())
        idleMain()

        assertEquals(1, completions.size)
        assertTrue(completions.single().exceptionOrNull() is CancellationException)
    }

    @Test
    fun cancelBeforeCreatePurgesLateRegion() {
        val completions = mutableListOf<Result<Unit>>()
        wrapper.downloadSatelliteRegion(
            bbox(),
            0,
            10,
            resourceCountCb = {},
            progressCb = {},
            completionCb = { completions.add(it) },
        )

        wrapper.cancelDownload()
        idleMain()
        assertEquals(1, completions.size)
        assertTrue(completions.single().exceptionOrNull() is CancellationException)

        source.createCallback(Result.success(region))
        idleMain()

        verify(region, never()).setOfflineRegionDownloadState(OfflineRegionDownloadState.ACTIVE)
        verify(region).purge(any())
        assertEquals(1, completions.size)
    }

    @Test
    fun completionThenErrorEmitsExactlyOneTerminalResult() {
        val completions = mutableListOf<Result<Unit>>()
        wrapper.downloadSatelliteRegion(
            bbox(),
            0,
            10,
            resourceCountCb = {},
            progressCb = {},
            completionCb = { completions.add(it) },
        )
        source.createCallback(Result.success(region))
        val observer = captureObserver()

        observer.statusChanged(completeStatus())
        val error = mock(OfflineRegionError::class.java)
        `when`(error.message).thenReturn("boom")
        observer.errorOccurred(error)
        idleMain()

        assertEquals(1, completions.size)
        assertTrue(completions.single().isSuccess)
    }

    @Test
    fun errorThenCompletionEmitsExactlyOneTerminalResult() {
        val completions = mutableListOf<Result<Unit>>()
        wrapper.downloadSatelliteRegion(
            bbox(),
            0,
            10,
            resourceCountCb = {},
            progressCb = {},
            completionCb = { completions.add(it) },
        )
        source.createCallback(Result.success(region))
        val observer = captureObserver()

        val error = mock(OfflineRegionError::class.java)
        `when`(error.message).thenReturn("boom")
        observer.errorOccurred(error)
        observer.statusChanged(completeStatus())
        idleMain()

        assertEquals(1, completions.size)
        assertTrue(completions.single().isFailure)
    }

    @Test
    fun reportsActualResourceCountOnce() {
        val counts = mutableListOf<Long>()
        wrapper.downloadSatelliteRegion(
            bbox(),
            0,
            10,
            resourceCountCb = { counts.add(it) },
            progressCb = {},
            completionCb = {},
        )
        source.createCallback(Result.success(region))
        val observer = captureObserver()

        observer.statusChanged(completeStatus())
        observer.statusChanged(completeStatus())
        idleMain()

        assertEquals(listOf(10L), counts)
    }

    private fun bbox(): Bbox = Bbox(-1.0, -1.0, 1.0, 1.0)

    private fun completeStatus(): OfflineRegionStatus =
        OfflineRegionStatus(
            OfflineRegionDownloadState.ACTIVE,
            10,
            0,
            10,
            10,
            0,
            10,
            true,
        )

    private fun captureObserver(): OfflineRegionObserver {
        val captor = argumentCaptor<OfflineRegionObserver>()
        verify(region).setOfflineRegionObserver(captor.capture())
        return captor.firstValue
    }

    private fun idleMain() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun definitionFactoryFailureCompletesAndAllowsRetry() {
        val throwing =
            OfflineRegionWrapper(
                source = source,
                mainHandler = Handler(Looper.getMainLooper()),
                definitionFactory = { _, _, _ -> throw IllegalStateException("factory boom") },
            )
        val first = mutableListOf<Result<Unit>>()
        throwing.downloadSatelliteRegion(bbox(), 0, 10, {}, {}, { first.add(it) })
        idleMain()
        assertEquals(1, first.size)
        assertTrue(first.single().isFailure)

        val second = mutableListOf<Result<Unit>>()
        throwing.downloadSatelliteRegion(bbox(), 0, 10, {}, {}, { second.add(it) })
        idleMain()
        assertEquals(1, second.size)
        assertTrue(second.single().isFailure)
    }

    private class FakeSource : OfflineRegionSource {
        lateinit var createCallback: (Result<OfflineRegionHandle>) -> Unit

        override fun createOfflineRegion(
            definition: OfflineRegionTilePyramidDefinition,
            callback: (Result<OfflineRegionHandle>) -> Unit,
        ) {
            createCallback = callback
        }

        override fun getOfflineRegions(callback: (Result<List<OfflineRegionHandle>>) -> Unit) {
            callback(Result.success(emptyList()))
        }
    }
}
