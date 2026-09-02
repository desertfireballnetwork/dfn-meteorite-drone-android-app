package au.edu.fireballs.stage4.ui.screen.stage4map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import au.edu.fireballs.stage4.data.tiles.TileStore
import com.mapbox.maps.extension.compose.style.LongValue
import com.mapbox.maps.extension.compose.style.StringListValue
import com.mapbox.maps.extension.compose.style.layers.generated.RasterLayer
import com.mapbox.maps.extension.compose.style.sources.generated.rememberRasterSourceState

private const val CUSTOM_RASTER_SOURCE_ID = "custom_raster"
private const val CUSTOM_RASTER_LAYER_ID = "custom_raster_layer"
private const val TILE_SIZE = 128L

@Composable
fun CustomRasterOverlay(
    surveyId: Long,
    candidateId: Long?,
    tileStore: TileStore?,
) {
    if (tileStore == null || candidateId == null || !tileStore.hasSurvey(surveyId)) {
        return
    }

    key(surveyId, candidateId) {
        val baseUrl = tileStore.surveyTilesDirectory(surveyId).toURI().toString()
        val tilesUrl = "$baseUrl$candidateId/{z}/{x}/{y}.png"

        val sourceState =
            rememberRasterSourceState(key = CUSTOM_RASTER_SOURCE_ID) {
                tiles = StringListValue(listOf(tilesUrl))
                tileSize = LongValue(TILE_SIZE)
            }

        RasterLayer(sourceState, CUSTOM_RASTER_LAYER_ID) {
        }
    }
}
