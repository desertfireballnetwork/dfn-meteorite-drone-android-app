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
import com.mapbox.maps.extension.compose.DisposableMapEffect
import com.mapbox.maps.extension.style.layers.generated.RasterLayer
import com.mapbox.maps.extension.style.sources.CustomRasterSource
import com.mapbox.maps.extension.style.sources.customRasterSource
import java.nio.ByteBuffer

private const val CUSTOM_RASTER_SOURCE_ID = "custom_raster"
private const val CUSTOM_RASTER_LAYER_ID = "custom_raster_layer"
private const val TILE_SIZE: Short = 128

@Composable
fun CustomRasterOverlay(
    surveyId: Long,
    candidateId: Long?,
    tileStore: TileStore?,
) {
    if (tileStore == null || candidateId == null || !tileStore.hasSurvey(surveyId)) {
        return
    }
    val tileProvider =
        remember(surveyId, candidateId, tileStore) {
            LocalFileRasterTileProvider(tileStore)
        }
    DisposableMapEffect(surveyId, candidateId, tileProvider) { mapView ->
        val style = mapView.mapboxMap.style ?: return@DisposableMapEffect onDispose {}
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
                                if (status != CustomRasterSourceTileStatus.REQUIRED) {
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
                                val image = decodeToImage(bytes) ?: return
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
        onDispose {
            style.removeStyleSource(CUSTOM_RASTER_SOURCE_ID)
        }
    }
}

private fun decodeToImage(bytes: ByteArray): Image? {
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val width = bitmap.width
    val height = bitmap.height
    val argb = IntArray(width * height)
    bitmap.getPixels(argb, 0, width, 0, 0, width, height)
    val rgba = ByteBuffer.allocate(width * height * 4)
    for (pixel in argb) {
        rgba.put(((pixel shr 16) and 0xFF).toByte())
        rgba.put(((pixel shr 8) and 0xFF).toByte())
        rgba.put((pixel and 0xFF).toByte())
        rgba.put(((pixel shr 24) and 0xFF).toByte())
    }
    rgba.flip()
    return Image(width, height, DataRef(rgba))
}
