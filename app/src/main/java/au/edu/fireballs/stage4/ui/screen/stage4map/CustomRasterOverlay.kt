package au.edu.fireballs.stage4.ui.screen.stage4map

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import au.edu.fireballs.stage4.data.tiles.LocalFileRasterTileProvider
import au.edu.fireballs.stage4.data.tiles.TileStore
import com.mapbox.bindgen.DataRef
import com.mapbox.maps.CanonicalTileID
import com.mapbox.maps.CustomRasterSourceClient
import com.mapbox.maps.CustomRasterSourceTileData
import com.mapbox.maps.CustomRasterSourceTileStatus
import com.mapbox.maps.CustomRasterSourceTileStatusChangedCallback
import com.mapbox.maps.Image
import com.mapbox.maps.MapboxStyleManager
import com.mapbox.maps.extension.compose.DisposableMapEffect
import com.mapbox.maps.extension.style.layers.generated.RasterLayer
import com.mapbox.maps.extension.style.sources.CustomRasterSource
import com.mapbox.maps.extension.style.sources.customRasterSource
import com.mapbox.maps.plugin.delegates.listeners.OnMapLoadedListener
import java.nio.ByteBuffer

private const val CUSTOM_RASTER_SOURCE_ID = "custom_raster"
private const val CUSTOM_RASTER_LAYER_ID = "custom_raster_layer"
private const val TILE_SIZE: Short = 128
private const val MAX_TILE_BYTES = TileStore.MAX_TILE_BYTES
private const val MAX_TILE_PIXELS = 128L * 128L

@Composable
fun CustomRasterOverlay(
    surveyId: Long,
    candidateId: Long?,
    tileStore: TileStore?,
) {
    if (
        tileStore == null ||
        candidateId == null ||
        !tileStore.hasCandidate(surveyId, candidateId)
    ) {
        return
    }
    val tileProvider =
        remember(surveyId, candidateId, tileStore) {
            LocalFileRasterTileProvider(tileStore)
        }
    DisposableMapEffect(surveyId, candidateId, tileProvider) { mapView ->
        val map = mapView.mapboxMap
        var styleRef: MapboxStyleManager? = null
        var disposed = false

        fun install(style: MapboxStyleManager) {
            if (disposed || styleRef === style) {
                return
            }
            styleRef?.let { previousStyle ->
                previousStyle.removeStyleLayer(CUSTOM_RASTER_LAYER_ID)
                previousStyle.removeStyleSource(CUSTOM_RASTER_SOURCE_ID)
            }
            lateinit var source: CustomRasterSource
            source =
                customRasterSource(CUSTOM_RASTER_SOURCE_ID) {
                    clientCallback(
                        CustomRasterSourceClient.valueOf(
                            object : CustomRasterSourceTileStatusChangedCallback {
                                override fun run(
                                    tileId: CanonicalTileID,
                                    status: CustomRasterSourceTileStatus,
                                ) {
                                    if (
                                        disposed ||
                                        styleRef !== style ||
                                        status != CustomRasterSourceTileStatus.REQUIRED
                                    ) {
                                        return
                                    }
                                    val bytes =
                                        tileProvider.tile(
                                            surveyId,
                                            candidateId,
                                            tileId.z.toInt(),
                                            tileId.x,
                                            tileId.y,
                                        )
                                    val image =
                                        decodeToImage(bytes)
                                            ?: decodeToImage(
                                                LocalFileRasterTileProvider.TRANSPARENT_PNG,
                                            )
                                            ?: return
                                    source.setTileData(
                                        listOf(
                                            CustomRasterSourceTileData(tileId, image),
                                        ),
                                    )
                                }
                            },
                        ),
                    )
                    tileSize(TILE_SIZE)
                }
            source.bindTo(style)
            RasterLayer(CUSTOM_RASTER_LAYER_ID, CUSTOM_RASTER_SOURCE_ID).bindTo(style)
            styleRef = style
        }

        map.style?.let { install(it) }
        val styleLoadedListener = OnMapLoadedListener { map.style?.let { install(it) } }
        map.addOnMapLoadedListener(styleLoadedListener)

        onDispose {
            disposed = true
            map.removeOnMapLoadedListener(styleLoadedListener)
            styleRef?.let { style ->
                style.removeStyleLayer(CUSTOM_RASTER_LAYER_ID)
                style.removeStyleSource(CUSTOM_RASTER_SOURCE_ID)
            }
        }
    }
}

private fun decodeToImage(bytes: ByteArray): Image? {
    if (bytes.size > MAX_TILE_BYTES) {
        return null
    }
    val bounds =
        BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return null
    }
    val pixelCount = bounds.outWidth.toLong() * bounds.outHeight.toLong()
    if (pixelCount > MAX_TILE_PIXELS) {
        return null
    }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val width = bitmap.width
    val height = bitmap.height
    val argb = IntArray(width * height)
    bitmap.getPixels(argb, 0, width, 0, 0, width, height)
    bitmap.recycle()
    val bufferSize = width.toLong() * height * 4L
    if (bufferSize > Int.MAX_VALUE) {
        return null
    }
    val rgba = ByteBuffer.allocate(bufferSize.toInt())
    for (pixel in argb) {
        rgba.put(((pixel shr 16) and 0xFF).toByte())
        rgba.put(((pixel shr 8) and 0xFF).toByte())
        rgba.put((pixel and 0xFF).toByte())
        rgba.put(((pixel shr 24) and 0xFF).toByte())
    }
    rgba.flip()
    return Image(width, height, DataRef(rgba))
}
