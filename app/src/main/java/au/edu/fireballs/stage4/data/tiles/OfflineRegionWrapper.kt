package au.edu.fireballs.stage4.data.tiles

import android.os.Handler
import android.os.Looper
import au.edu.fireballs.stage4.data.repository.PreDownloadTargetKey
import com.mapbox.geojson.Point
import com.mapbox.maps.CoordinateBounds
import com.mapbox.maps.GlyphsRasterizationMode
import com.mapbox.maps.OfflineRegionDownloadState
import com.mapbox.maps.OfflineRegionError
import com.mapbox.maps.OfflineRegionObserver
import com.mapbox.maps.OfflineRegionStatus
import com.mapbox.maps.OfflineRegionTilePyramidDefinition
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

data class OfflineRegionInventory(
    val regionCount: Int,
    val measuredBytes: Long?,
)

sealed interface OfflineRegionRetention {
    val target: PreDownloadTargetKey.Satellite

    data class Retained(
        override val target: PreDownloadTargetKey.Satellite,
    ) : OfflineRegionRetention

    data class Missing(
        override val target: PreDownloadTargetKey.Satellite,
    ) : OfflineRegionRetention
}

enum class OfflineRegionFailureCategory {
    REGION_LIST_FAILED,
    PURGE_REJECTED,
}

sealed interface OfflineRegionPurgeResult {
    val target: PreDownloadTargetKey.Satellite

    data class Purged(
        override val target: PreDownloadTargetKey.Satellite,
    ) : OfflineRegionPurgeResult

    data class ConfirmedAbsent(
        override val target: PreDownloadTargetKey.Satellite,
    ) : OfflineRegionPurgeResult

    data class RetryableFailure(
        override val target: PreDownloadTargetKey.Satellite,
        val category: OfflineRegionFailureCategory,
        val attemptTimeMillis: Long,
    ) : OfflineRegionPurgeResult
}

class OfflineRegionWrapper(
    private val source: OfflineRegionSource = MapboxOfflineRegionSource(),
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
        target: PreDownloadTargetKey.Satellite? = null,
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
            if (target == null) {
                observeRegion(region, operation)
            } else {
                region.setMetadata(encodeOfflineRegionTarget(target)) { metadataResult ->
                    if (metadataResult.isError) {
                        region.purge {
                            operation.complete(
                                Result.failure(
                                    IllegalStateException("Offline region ownership setup failed"),
                                ),
                            )
                        }
                    } else {
                        mainHandler.post { observeRegion(region, operation) }
                    }
                }
            }
        }
    }

    private fun observeRegion(
        region: OfflineRegionHandle,
        operation: RegionOperation,
    ) {
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

    fun cancelDownload() {
        activeOperation?.cancel()
    }

    fun retainExact(
        targets: List<PreDownloadTargetKey.Satellite>,
        callback: (Result<List<OfflineRegionRetention>>) -> Unit,
    ) {
        if (targets.isEmpty()) {
            mainHandler.post { callback(Result.success(emptyList())) }
            return
        }
        source.getOfflineRegions { result ->
            mainHandler.post {
                callback(
                    result.map { regions ->
                        val owned =
                            regions
                                .mapNotNull { decodeOfflineRegionTarget(it.metadata) }
                                .toSet()
                        targets.map { target ->
                            if (target in owned) {
                                OfflineRegionRetention.Retained(target)
                            } else {
                                OfflineRegionRetention.Missing(target)
                            }
                        }
                    },
                )
            }
        }
    }

    fun purgeExact(
        target: PreDownloadTargetKey.Satellite,
        callback: (OfflineRegionPurgeResult) -> Unit,
    ) {
        source.getOfflineRegions { result ->
            val regions =
                result.getOrElse {
                    mainHandler.post {
                        callback(
                            retryableFailure(
                                target,
                                OfflineRegionFailureCategory.REGION_LIST_FAILED,
                            ),
                        )
                    }
                    return@getOfflineRegions
                }
            val owned = regions.firstOrNull { decodeOfflineRegionTarget(it.metadata) == target }
            if (owned == null) {
                mainHandler.post { callback(OfflineRegionPurgeResult.ConfirmedAbsent(target)) }
                return@getOfflineRegions
            }
            owned.purge { purgeResult ->
                mainHandler.post {
                    callback(
                        if (purgeResult.isError) {
                            retryableFailure(target, OfflineRegionFailureCategory.PURGE_REJECTED)
                        } else {
                            OfflineRegionPurgeResult.Purged(target)
                        },
                    )
                }
            }
        }
    }

    private fun retryableFailure(
        target: PreDownloadTargetKey.Satellite,
        category: OfflineRegionFailureCategory,
    ): OfflineRegionPurgeResult.RetryableFailure =
        OfflineRegionPurgeResult.RetryableFailure(
            target = target,
            category = category,
            attemptTimeMillis = System.currentTimeMillis(),
        )

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

    fun inventory(callback: (Result<OfflineRegionInventory>) -> Unit) {
        source.getOfflineRegions { result ->
            mainHandler.post {
                callback(
                    result.map { regions ->
                        OfflineRegionInventory(
                            regionCount = regions.size,
                            measuredBytes = null,
                        )
                    },
                )
            }
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
                .glyphsRasterizationMode(
                    GlyphsRasterizationMode.IDEOGRAPHS_RASTERIZED_LOCALLY,
                ).build()
    }
}

private const val REGION_METADATA_PREFIX = "stage4-satellite"
private const val REGION_METADATA_SEPARATOR = "\u0000"

internal fun encodeOfflineRegionTarget(target: PreDownloadTargetKey.Satellite): ByteArray =
    listOf(
        REGION_METADATA_PREFIX,
        target.surveyId.toString(),
        target.sourceVersion,
        target.signature,
    ).joinToString(REGION_METADATA_SEPARATOR).toByteArray(Charsets.UTF_8)

internal fun decodeOfflineRegionTarget(bytes: ByteArray?): PreDownloadTargetKey.Satellite? {
    if (bytes == null) {
        return null
    }
    val parts = String(bytes, Charsets.UTF_8).split(REGION_METADATA_SEPARATOR)
    if (parts.size != 4 || parts[0] != REGION_METADATA_PREFIX) {
        return null
    }
    val surveyId = parts[1].toLongOrNull() ?: return null
    return PreDownloadTargetKey.Satellite(
        surveyId = surveyId,
        sourceVersion = parts[2],
        signature = parts[3],
    )
}
