package au.edu.fireballs.stage4.data.tiles

import android.os.Handler
import android.os.Looper
import com.mapbox.bindgen.Expected
import com.mapbox.geojson.Point
import com.mapbox.maps.CoordinateBounds
import com.mapbox.maps.OfflineRegion
import com.mapbox.maps.OfflineRegionCreateCallback
import com.mapbox.maps.OfflineRegionDownloadState
import com.mapbox.maps.OfflineRegionError
import com.mapbox.maps.OfflineRegionManager
import com.mapbox.maps.OfflineRegionObserver
import com.mapbox.maps.OfflineRegionStatus
import com.mapbox.maps.OfflineRegionTilePyramidDefinition
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

class OfflineRegionWrapper(
    private val offlineRegionManager: OfflineRegionManager = OfflineRegionManager(),
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) : OfflineRegionDownloader {
    private var activeOperation: RegionOperation? = null

    override fun downloadSatelliteRegion(
        bbox: Bbox,
        minZoom: Int,
        maxZoom: Int,
        progressCb: (Double) -> Unit,
        completionCb: (Result<Unit>) -> Unit,
    ) {
        check(activeOperation?.isTerminal != false) {
            "An offline region download is already active"
        }
        val operation = RegionOperation(mainHandler, progressCb, completionCb)
        activeOperation = operation
        val definition =
            OfflineRegionTilePyramidDefinition
                .Builder()
                .styleURL(SATELLITE_STYLE_URL)
                .bounds(
                    CoordinateBounds(
                        Point.fromLngLat(bbox.minLon, bbox.minLat),
                        Point.fromLngLat(bbox.maxLon, bbox.maxLat),
                    ),
                ).minZoom(minZoom.toDouble())
                .maxZoom(maxZoom.toDouble())
                .build()

        offlineRegionManager.createOfflineRegion(
            definition,
            object : OfflineRegionCreateCallback {
                override fun run(expected: Expected<String, OfflineRegion>) {
                    if (expected.isError) {
                        operation.complete(Result.failure(IllegalStateException(expected.error)))
                        return
                    }
                    val region =
                        expected.value
                            ?: run {
                                operation.complete(
                                    Result.failure(
                                        IllegalStateException(
                                            "Mapbox returned no offline region",
                                        ),
                                    ),
                                )
                                return
                            }
                    operation.region = region
                    region.setOfflineRegionObserver(
                        object : OfflineRegionObserver {
                            override fun statusChanged(status: OfflineRegionStatus) {
                                if (operation.isTerminal) {
                                    return
                                }
                                val progress =
                                    if (status.requiredResourceCount > 0) {
                                        status.completedResourceCount.toDouble() /
                                            status.requiredResourceCount.toDouble()
                                    } else {
                                        0.0
                                    }
                                operation.postProgress(progress.coerceIn(0.0, 1.0))
                                if (status.requiredResourceCount > 0 &&
                                    status.completedResourceCount >= status.requiredResourceCount
                                ) {
                                    operation.complete(Result.success(Unit))
                                }
                            }

                            override fun errorOccurred(error: OfflineRegionError) {
                                operation.complete(
                                    Result.failure(
                                        IllegalStateException(error.message),
                                    ),
                                )
                            }
                        },
                    )
                    operation.activateIfActive()
                }
            },
        )
    }

    fun cancelDownload() {
        activeOperation?.cancel()
    }

    fun deleteRegion(regionId: String) {
        offlineRegionManager.getOfflineRegions { expected ->
            if (!expected.isError) {
                expected.value?.firstOrNull { it.identifier.toString() == regionId }?.purge { }
            }
        }
    }

    fun listRegions(callback: (Result<List<OfflineRegion>>) -> Unit) {
        offlineRegionManager.getOfflineRegions { expected ->
            if (expected.isError) {
                mainHandler.post { callback(Result.failure(IllegalStateException(expected.error))) }
            } else {
                mainHandler.post { callback(Result.success(expected.value.orEmpty())) }
            }
        }
    }

    fun purgeAllRegions() {
        offlineRegionManager.getOfflineRegions { expected ->
            if (!expected.isError) {
                expected.value?.forEach { region ->
                    region.purge { }
                }
            }
        }
    }

    private class RegionOperation(
        private val mainHandler: Handler,
        private val progressCb: (Double) -> Unit,
        private val completionCb: (Result<Unit>) -> Unit,
    ) {
        @Volatile
        var region: OfflineRegion? = null

        private val terminal = AtomicBoolean(false)

        val isTerminal: Boolean
            get() = terminal.get()

        @Volatile
        private var cancelled = false

        fun postProgress(progress: Double) {
            if (terminal.get() || cancelled) {
                return
            }
            mainHandler.post {
                if (!terminal.get() && !cancelled) {
                    progressCb(progress)
                }
            }
        }

        fun complete(result: Result<Unit>) {
            if (!terminal.compareAndSet(false, true)) {
                return
            }
            mainHandler.post { completionCb(result) }
        }

        fun cancel() {
            if (isTerminal) {
                return
            }
            cancelled = true
            region?.setOfflineRegionDownloadState(OfflineRegionDownloadState.INACTIVE)
            complete(Result.failure(CancellationException("Offline region download cancelled")))
        }

        fun activateIfActive() {
            if (cancelled || isTerminal) {
                region?.setOfflineRegionDownloadState(OfflineRegionDownloadState.INACTIVE)
                return
            }
            region?.setOfflineRegionDownloadState(OfflineRegionDownloadState.ACTIVE)
        }
    }

    companion object {
        private const val SATELLITE_STYLE_URL = "mapbox://styles/mapbox/satellite-v9"
    }
}
