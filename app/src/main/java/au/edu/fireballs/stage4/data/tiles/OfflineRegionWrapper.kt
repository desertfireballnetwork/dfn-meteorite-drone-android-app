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

class OfflineRegionWrapper(
    private val offlineRegionManager: OfflineRegionManager = OfflineRegionManager(),
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) : OfflineRegionDownloader {
    override fun downloadSatelliteRegion(
        bbox: Bbox,
        minZoom: Int,
        maxZoom: Int,
        progressCb: (Double) -> Unit,
        completionCb: (Result<Unit>) -> Unit,
    ) {
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
                        mainHandler.post {
                            completionCb(
                                Result.failure(IllegalStateException(expected.error)),
                            )
                        }
                        return
                    }
                    val region = expected.value ?: return
                    region.setOfflineRegionObserver(
                        object : OfflineRegionObserver {
                            override fun statusChanged(status: OfflineRegionStatus) {
                                val progress =
                                    if (status.requiredResourceCount > 0) {
                                        status.completedResourceCount.toDouble() /
                                            status.requiredResourceCount.toDouble()
                                    } else {
                                        0.0
                                    }
                                mainHandler.post { progressCb(progress) }
                            }

                            override fun errorOccurred(error: OfflineRegionError) {
                                mainHandler.post {
                                    completionCb(
                                        Result.failure(IllegalStateException(error.message)),
                                    )
                                }
                            }
                        },
                    )
                    region.setOfflineRegionDownloadState(OfflineRegionDownloadState.ACTIVE)
                    mainHandler.post { completionCb(Result.success(Unit)) }
                }
            },
        )
    }

    fun cancelDownload(region: OfflineRegion) {
        region.setOfflineRegionDownloadState(OfflineRegionDownloadState.INACTIVE)
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

    companion object {
        private const val SATELLITE_STYLE_URL = "mapbox://styles/mapbox/satellite-v9"
    }
}
