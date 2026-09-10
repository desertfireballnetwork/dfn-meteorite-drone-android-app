package au.edu.fireballs.stage4.data.tiles

import com.mapbox.bindgen.Expected
import com.mapbox.maps.AsyncOperationResultCallback
import com.mapbox.maps.OfflineRegion
import com.mapbox.maps.OfflineRegionCreateCallback
import com.mapbox.maps.OfflineRegionDownloadState
import com.mapbox.maps.OfflineRegionManager
import com.mapbox.maps.OfflineRegionObserver
import com.mapbox.maps.OfflineRegionTilePyramidDefinition

interface OfflineRegionHandle {
    fun setOfflineRegionObserver(observer: OfflineRegionObserver)

    fun setOfflineRegionDownloadState(state: OfflineRegionDownloadState)

    fun purge(callback: AsyncOperationResultCallback)

    val identifier: Long
}

class MapboxOfflineRegionHandle(
    private val region: OfflineRegion,
) : OfflineRegionHandle {
    override fun setOfflineRegionObserver(observer: OfflineRegionObserver) {
        region.setOfflineRegionObserver(observer)
    }

    override fun setOfflineRegionDownloadState(state: OfflineRegionDownloadState) {
        region.setOfflineRegionDownloadState(state)
    }

    override fun purge(callback: AsyncOperationResultCallback) {
        region.purge(callback)
    }

    override val identifier: Long
        get() = region.identifier
}

interface OfflineRegionSource {
    fun createOfflineRegion(
        definition: OfflineRegionTilePyramidDefinition,
        callback: (Result<OfflineRegionHandle>) -> Unit,
    )

    fun getOfflineRegions(callback: (Result<List<OfflineRegionHandle>>) -> Unit)
}

class MapboxOfflineRegionSource(
    private val manager: OfflineRegionManager,
) : OfflineRegionSource {
    override fun createOfflineRegion(
        definition: OfflineRegionTilePyramidDefinition,
        callback: (Result<OfflineRegionHandle>) -> Unit,
    ) {
        manager.createOfflineRegion(
            definition,
            object : OfflineRegionCreateCallback {
                override fun run(expected: Expected<String, OfflineRegion>) {
                    callback(
                        when {
                            expected.isError ->
                                Result.failure(IllegalStateException(expected.error))

                            else ->
                                expected.value?.let {
                                    Result.success(MapboxOfflineRegionHandle(it))
                                } ?: Result.failure(
                                    IllegalStateException("Mapbox returned no offline region"),
                                )
                        },
                    )
                }
            },
        )
    }

    override fun getOfflineRegions(callback: (Result<List<OfflineRegionHandle>>) -> Unit) {
        manager.getOfflineRegions { expected: Expected<String, List<OfflineRegion>> ->
            callback(
                when {
                    expected.isError ->
                        Result.failure(IllegalStateException(expected.error))

                    else ->
                        Result.success(
                            expected.value.orEmpty().map { MapboxOfflineRegionHandle(it) },
                        )
                },
            )
        }
    }
}
