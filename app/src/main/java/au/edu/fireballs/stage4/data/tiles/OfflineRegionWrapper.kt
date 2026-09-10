package au.edu.fireballs.stage4.data.tiles

import android.os.Handler
import android.os.Looper
import com.mapbox.geojson.Point
import com.mapbox.maps.CoordinateBounds
import com.mapbox.maps.OfflineRegionDownloadState
import com.mapbox.maps.OfflineRegionError
import com.mapbox.maps.OfflineRegionManager
import com.mapbox.maps.OfflineRegionObserver
import com.mapbox.maps.OfflineRegionStatus
import com.mapbox.maps.OfflineRegionTilePyramidDefinition
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

class OfflineRegionWrapper(
    private val source: OfflineRegionSource = MapboxOfflineRegionSource(OfflineRegionManager()),
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val definitionFactory: (Bbox, Int, Int) -> OfflineRegionTilePyramidDefinition =
        ::defaultDefinition,
) {
    private var activeOperation: RegionOperation? = null
    private val operationLock = Any()

    fun downloadSatelliteRegion(
        bbox: Bbox,
        minZoom: Int,
        maxZoom: Int,
        resourceCountCb: (Long) -> Unit,
        progressCb: (Double) -> Unit,
        completionCb: (Result<Unit>) -> Unit,
    ) {
        val operation =
            synchronized(operationLock) {
                check(activeOperation?.isTerminal != false) {
                    "An offline region download is already active"
                }
                RegionOperation(mainHandler, resourceCountCb, progressCb, completionCb).also {
                    activeOperation = it
                }
            }
        val definition =
            runCatching { definitionFactory(bbox, minZoom, maxZoom) }
                .getOrElse { error ->
                    operation.complete(Result.failure(error))
                    return
                }

        source.createOfflineRegion(definition) { result ->
            val region =
                result.getOrElse { error ->
                    operation.complete(Result.failure(error))
                    return@createOfflineRegion
                }
            operation.region = region
            region.setOfflineRegionObserver(
                object : OfflineRegionObserver {
                    override fun statusChanged(status: OfflineRegionStatus) {
                        if (operation.isTerminal) {
                            return
                        }
                        operation.reportResourceCount(status.requiredResourceCount)
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
    }

    fun cancelDownload() {
        activeOperation?.cancel()
    }

    fun deleteRegion(
        regionId: String,
        callback: (Result<Unit>) -> Unit = {},
    ) {
        source.getOfflineRegions { result ->
            val regions =
                result.getOrElse { error ->
                    mainHandler.post { callback(Result.failure(error)) }
                    return@getOfflineRegions
                }
            val region = regions.firstOrNull { it.identifier.toString() == regionId }
            if (region == null) {
                mainHandler.post {
                    callback(Result.failure(IllegalStateException("Region not found: $regionId")))
                }
                return@getOfflineRegions
            }
            region.purge {
                mainHandler.post {
                    callback(
                        if (it.isError) {
                            Result.failure(IllegalStateException(it.error))
                        } else {
                            Result.success(Unit)
                        },
                    )
                }
            }
        }
    }

    fun purgeAllRegions(callback: (Result<Unit>) -> Unit = {}) {
        source.getOfflineRegions { result ->
            val regions =
                result.getOrElse { error ->
                    mainHandler.post { callback(Result.failure(error)) }
                    return@getOfflineRegions
                }
            var remaining = regions.size
            var failed: Throwable? = null
            if (remaining == 0) {
                mainHandler.post { callback(Result.success(Unit)) }
                return@getOfflineRegions
            }
            regions.forEach { region ->
                region.purge {
                    mainHandler.post {
                        if (it.isError && failed == null) {
                            failed = IllegalStateException(it.error)
                        }
                        remaining--
                        if (remaining == 0) {
                            callback(
                                if (failed != null) {
                                    Result.failure(failed)
                                } else {
                                    Result.success(Unit)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    fun listRegions(callback: (Result<List<OfflineRegionHandle>>) -> Unit) {
        source.getOfflineRegions { result ->
            mainHandler.post { callback(result) }
        }
    }

    private class RegionOperation(
        private val mainHandler: Handler,
        private val resourceCountCb: (Long) -> Unit,
        private val progressCb: (Double) -> Unit,
        private val completionCb: (Result<Unit>) -> Unit,
    ) {
        @Volatile
        var region: OfflineRegionHandle? = null

        private val terminal = AtomicBoolean(false)

        val isTerminal: Boolean
            get() = terminal.get()

        @Volatile
        private var cancelled = false

        @Volatile
        private var resourceCountReported = false

        fun reportResourceCount(count: Long) {
            if (count > 0 && !resourceCountReported) {
                resourceCountReported = true
                mainHandler.post { resourceCountCb(count) }
            }
        }

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
            val activeRegion = region
            activeRegion?.setOfflineRegionDownloadState(OfflineRegionDownloadState.INACTIVE)
            if (activeRegion == null) {
                complete(Result.failure(CancellationException("Offline region download cancelled")))
                return
            }
            activeRegion.purge {
                complete(Result.failure(CancellationException("Offline region download cancelled")))
            }
        }

        fun activateIfActive() {
            if (cancelled) {
                region?.purge { }
                return
            }
            if (isTerminal) {
                region?.setOfflineRegionDownloadState(OfflineRegionDownloadState.INACTIVE)
                return
            }
            region?.setOfflineRegionDownloadState(OfflineRegionDownloadState.ACTIVE)
        }
    }

    companion object {
        private const val SATELLITE_STYLE_URL = "mapbox://styles/mapbox/satellite-v9"

        private fun defaultDefinition(
            bbox: Bbox,
            minZoom: Int,
            maxZoom: Int,
        ): OfflineRegionTilePyramidDefinition =
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
    }
}
