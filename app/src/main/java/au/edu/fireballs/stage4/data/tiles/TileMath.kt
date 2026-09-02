package au.edu.fireballs.stage4.data.tiles

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

data class Bbox(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
)

object TileMath {
    fun tileX(
        lon: Double,
        z: Int,
    ): Int = floor((lon + 180.0) / 360.0 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)

    fun tileY(
        lat: Double,
        z: Int,
    ): Int {
        val rad = Math.toRadians(lat)
        val merc = ln(tan(rad) + 1.0 / cos(rad))
        return floor((1.0 - merc / PI) / 2.0 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
    }

    fun tilesForBbox(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
        z: Int,
    ): List<TileCoord> {
        val minX = tileX(minLon, z)
        val maxX = tileX(maxLon, z)
        val minY = tileY(maxLat, z)
        val maxY = tileY(minLat, z)
        return buildList {
            for (x in minX..maxX) {
                for (y in minY..maxY) {
                    add(TileCoord(z, x, y))
                }
            }
        }
    }

    fun xyzToTms(coord: TileCoord): TileCoord =
        TileCoord(coord.z, coord.x, (1 shl coord.z) - 1 - coord.y)

    fun bufferBbox(
        lat: Double,
        lon: Double,
        radiusMeters: Double,
    ): Bbox {
        val latDelta = radiusMeters / 111_000.0
        val lonDelta = radiusMeters / (111_000.0 * cos(Math.toRadians(lat)))
        return Bbox(lat - latDelta, lon - lonDelta, lat + latDelta, lon + lonDelta)
    }
}
