package au.edu.fireballs.stage4.data.tiles

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

private const val MAX_LATITUDE = 85.05112878

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
        val clamped = lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val rad = Math.toRadians(clamped)
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

    fun tileCountForBbox(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
        z: Int,
    ): Long {
        val minX = tileX(minLon, z).toLong()
        val maxX = tileX(maxLon, z).toLong()
        val minY = tileY(maxLat, z).toLong()
        val maxY = tileY(minLat, z).toLong()
        return (maxX - minX + 1L) * (maxY - minY + 1L)
    }

    fun flipTileY(coord: TileCoord): TileCoord =
        TileCoord(coord.z, coord.x, (1 shl coord.z) - 1 - coord.y)

    fun bufferBbox(
        lat: Double,
        lon: Double,
        radiusMeters: Double,
    ): Bbox {
        require(lat.isFinite() && lat in -MAX_LATITUDE..MAX_LATITUDE) {
            "Latitude must be finite and within Web Mercator bounds"
        }
        require(lon.isFinite() && lon in -180.0..180.0) {
            "Longitude must be finite and within geographic bounds"
        }
        require(radiusMeters.isFinite() && radiusMeters >= 0.0) {
            "Radius must be finite and non-negative"
        }
        val latDelta = radiusMeters / 111_000.0
        val lonDelta = radiusMeters / (111_000.0 * cos(Math.toRadians(lat)))
        return Bbox(
            (lat - latDelta).coerceAtLeast(-MAX_LATITUDE),
            (lon - lonDelta).coerceAtLeast(-180.0),
            (lat + latDelta).coerceAtMost(MAX_LATITUDE),
            (lon + lonDelta).coerceAtMost(180.0),
        )
    }
}
