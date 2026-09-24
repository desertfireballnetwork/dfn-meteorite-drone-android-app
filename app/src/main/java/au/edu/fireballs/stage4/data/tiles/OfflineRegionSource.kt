package au.edu.fireballs.stage4.data.tiles

import android.os.Handler
import android.os.Looper
import au.edu.fireballs.stage4.BuildConfig
import com.mapbox.bindgen.Expected
import com.mapbox.bindgen.ExpectedFactory
import com.mapbox.maps.AsyncOperationResultCallback
import com.mapbox.maps.OfflineRegion
import com.mapbox.maps.OfflineRegionCreateCallback
import com.mapbox.maps.OfflineRegionDownloadState
import com.mapbox.maps.OfflineRegionManager
import com.mapbox.maps.OfflineRegionObserver
import com.mapbox.maps.OfflineRegionStatus
import com.mapbox.maps.OfflineRegionTilePyramidDefinition

interface OfflineRegionHandle {
    fun setOfflineRegionObserver(observer: OfflineRegionObserver)

    fun setOfflineRegionDownloadState(state: OfflineRegionDownloadState)

    fun purge(callback: AsyncOperationResultCallback)

    fun getStatus(callback: (Result<OfflineRegionStatus>) -> Unit)

    val identifier: Long

    val metadata: ByteArray?
        get() = null

    fun setMetadata(
        metadata: ByteArray,
        callback: AsyncOperationResultCallback,
    ) {
        callback.run(ExpectedFactory.createNone())
    }
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

    override fun getStatus(callback: (Result<OfflineRegionStatus>) -> Unit) {
        region.getStatus { expected ->
            callback(
                when {
                    expected.isError ->
                        Result.failure(IllegalStateException(expected.error))

                    expected.value != null -> Result.success(expected.value!!)
                    else ->
                        Result.failure(
                            IllegalStateException(
                                "Mapbox returned no offline region status",
                            ),
                        )
                },
            )
        }
    }

    override val identifier: Long
        get() = region.identifier

    override val metadata: ByteArray?
        get() = region.metadata

    override fun setMetadata(
        metadata: ByteArray,
        callback: AsyncOperationResultCallback,
    ) {
        region.setMetadata(metadata, callback)
    }
}

interface OfflineRegionSource {
    fun createOfflineRegion(
        definition: OfflineRegionTilePyramidDefinition,
        callback: (Result<OfflineRegionHandle>) -> Unit,
    )

    fun getOfflineRegions(callback: (Result<List<OfflineRegionHandle>>) -> Unit)
}

class MapboxOfflineRegionSource(
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val maxMapboxTiles: Long = BuildConfig.MAPBOX_MAX_TILES_DEVICE,
) : OfflineRegionSource {
    private val manager by lazy {
        OfflineRegionManager().also { it.setOfflineMapboxTileCountLimit(maxMapboxTiles) }
    }

    override fun createOfflineRegion(
        definition: OfflineRegionTilePyramidDefinition,
        callback: (Result<OfflineRegionHandle>) -> Unit,
    ) {
        mainHandler.post {
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
    }

    override fun getOfflineRegions(callback: (Result<List<OfflineRegionHandle>>) -> Unit) {
        mainHandler.post {
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
}
