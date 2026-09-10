package au.edu.fireballs.stage4.data.tiles

import org.junit.Assert.assertEquals
import org.junit.Test

class TileMathTest {
    @Test
    fun tileXOriginAtZoomZero() {
        assertEquals(0, TileMath.tileX(0.0, 0))
    }

    @Test
    fun tileYOriginAtZoomZero() {
        assertEquals(0, TileMath.tileY(0.0, 0))
    }

    @Test
    fun tilesForBboxReturnsExpectedTileSet() {
        val tiles =
            TileMath.tilesForBbox(
                minLat = -10.0,
                minLon = -10.0,
                maxLat = 10.0,
                maxLon = 10.0,
                z = 1,
            )
        val expected =
            listOf(
                TileCoord(1, 0, 0),
                TileCoord(1, 0, 1),
                TileCoord(1, 1, 0),
                TileCoord(1, 1, 1),
            )
        assertEquals(expected, tiles)
    }

    @Test
    fun flipTileYFlipsY() {
        assertEquals(TileCoord(1, 0, 1), TileMath.flipTileY(TileCoord(1, 0, 0)))
    }

    @Test
    fun flipTileYRoundTrip() {
        val coord = TileCoord(5, 12, 20)
        assertEquals(coord, TileMath.flipTileY(TileMath.flipTileY(coord)))
    }

    @Test
    fun tileCountForBboxMatchesTilesForBboxSize() {
        val count =
            TileMath.tileCountForBbox(
                minLat = -10.0,
                minLon = -10.0,
                maxLat = 10.0,
                maxLon = 10.0,
                z = 1,
            )
        assertEquals(
            TileMath.tilesForBbox(-10.0, -10.0, 10.0, 10.0, 1).size.toLong(),
            count,
        )
    }

    @Test
    fun tileYClampsLatitudeToWebMercatorLimits() {
        val north = TileMath.tileY(90.0, 1)
        val clamped = TileMath.tileY(89.0, 1)
        assertEquals(0, north)
        assertEquals(0, clamped)
    }
}
