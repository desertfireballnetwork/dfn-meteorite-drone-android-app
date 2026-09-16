package au.edu.fireballs.stage4.ui.screen.stage4map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.mapbox.maps.extension.compose.style.LongValue
import com.mapbox.maps.extension.compose.style.StringListValue
import com.mapbox.maps.extension.compose.style.layers.generated.RasterLayer
import com.mapbox.maps.extension.compose.style.sources.generated.SchemeValue
import com.mapbox.maps.extension.compose.style.sources.generated.rememberRasterSourceState

private const val TILE_SIZE = 2048L
private const val MIN_ZOOM = 18L
private const val MAX_ZOOM = 22L

@Composable
fun CustomRasterOverlay(
    surveyId: Long,
    candidateId: Long?,
    tileUrlPattern: String?,
) {
    if (candidateId == null || tileUrlPattern == null) {
        return
    }

    key(surveyId, candidateId) {
        val sourceId = "custom_raster_${surveyId}_$candidateId"
        val layerId = "custom_raster_layer_${surveyId}_$candidateId"

        val sourceState =
            rememberRasterSourceState(key = sourceId) {
                tiles = StringListValue(listOf(tileUrlPattern))
                tileSize = LongValue(TILE_SIZE)
                scheme = SchemeValue.TMS
                minZoom = LongValue(MIN_ZOOM)
                maxZoom = LongValue(MAX_ZOOM)
            }

        RasterLayer(sourceState, layerId) {
        }
    }
}
