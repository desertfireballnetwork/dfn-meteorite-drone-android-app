package au.edu.fireballs.stage4.ui.screen.stage4map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.toArgb
import au.edu.fireballs.stage4.ui.theme.Stage4Theme
import com.mapbox.bindgen.Value
import com.mapbox.geojson.Point
import com.mapbox.geojson.Polygon
import com.mapbox.maps.extension.compose.style.ColorValue
import com.mapbox.maps.extension.compose.style.DoubleValue
import com.mapbox.maps.extension.compose.style.StringValue
import com.mapbox.maps.extension.compose.style.layers.generated.LineLayer
import com.mapbox.maps.extension.compose.style.layers.generated.RasterLayer
import com.mapbox.maps.extension.compose.style.sources.GeoJSONData
import com.mapbox.maps.extension.compose.style.sources.generated.rememberGeoJsonSourceState
import com.mapbox.maps.extension.compose.style.sources.generated.rememberRasterSourceState

private const val SURVEYED_AREA_SOURCE_PREFIX = "surveyed_areas"
private const val SURVEYED_AREA_OUTLINE_LAYER_PREFIX = "outline"
private const val CUSTOM_TILESET_SOURCE_ID = "custom_tileset"
private const val CUSTOM_DATA_LAYER_ID = "custom_data"
private const val TILESET_URI_PREFIX = "mapbox://"
private const val OUTLINE_LINE_WIDTH = 2.0
private const val MIN_RING_VERTICES = 3

@Composable
fun SurveyedAreaOverlay(
    polygons: List<List<List<Double>>>,
    tilesetId: String?,
) {
    key(polygons, tilesetId) {
        val outlineColorHex = outlinedColorHex()
        polygons.forEachIndexed { index, ring ->
            SurveyedAreaOutline(
                sourceId = "$SURVEYED_AREA_SOURCE_PREFIX$index",
                layerId = "$SURVEYED_AREA_OUTLINE_LAYER_PREFIX$index",
                ring = ring,
                outlineColorHex = outlineColorHex,
            )
        }
        tilesetId?.let { CustomTilesetRaster(tilesetId = it) }
    }
}

@Composable
private fun SurveyedAreaOutline(
    sourceId: String,
    layerId: String,
    ring: List<List<Double>>,
    outlineColorHex: String,
) {
    val ringPoints =
        ring.mapNotNull { vertex ->
            if (vertex.size < 2) {
                null
            } else {
                Point.fromLngLat(vertex[0], vertex[1])
            }
        }
    if (ringPoints.size < MIN_RING_VERTICES) {
        return
    }

    val sourceState =
        rememberGeoJsonSourceState(key = sourceId) {
            data = GeoJSONData(Polygon.fromLngLats(listOf(ringPoints)))
        }

    LineLayer(sourceState, layerId) {
        lineColor = ColorValue(Value("#$outlineColorHex"))
        lineWidth = DoubleValue(OUTLINE_LINE_WIDTH)
    }
}

@Composable
private fun CustomTilesetRaster(tilesetId: String) {
    val sourceState =
        rememberRasterSourceState(key = CUSTOM_TILESET_SOURCE_ID) {
            url = StringValue("$TILESET_URI_PREFIX$tilesetId")
        }

    RasterLayer(sourceState, CUSTOM_DATA_LAYER_ID) {
    }
}

@Composable
private fun outlinedColorHex(): String {
    val argb = Stage4Theme.colors.surveyedAreaPolygon.toArgb()
    return "%06X".format(argb and 0xFFFFFF)
}
