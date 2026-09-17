package au.edu.fireballs.stage4.data.tiles

import org.junit.Assert.assertEquals
import org.junit.Test

class LowZoomTileCompositorTest {
    @Test
    fun childScaleUsesZoomDifference() {
        assertEquals(4, childScaleFor(20, 18))
    }

    @Test
    fun parentUsesPureXyzCoordinateDivision() {
        val child = TileCoord(20, 887_826, 433_111)

        assertEquals(TileCoord(18, 221_956, 108_277), parentOf(child, 18))
        assertEquals(TileCoord(16, 55_489, 27_069), parentOf(child, 16))
    }

    @Test
    fun placementUsesChildOffsetWithinParent() {
        val child = TileCoord(20, 887_826, 433_111)
        val parent = TileCoord(18, 221_956, 108_277)

        val placements = placementsFor(listOf(child), parent, 20, 2048)

        assertEquals(
            listOf(
                ChildPlacement(
                    child = child,
                    left = 1024,
                    top = 1536,
                    size = 512,
                ),
            ),
            placements,
        )
    }

    @Test
    fun placementExcludesChildrenOutsideParent() {
        val parent = TileCoord(18, 221_956, 108_277)
        val inside = TileCoord(20, 887_826, 433_111)
        val outside = TileCoord(20, 887_828, 433_111)

        val placements = placementsFor(listOf(inside, outside), parent, 20, 2048)

        assertEquals(listOf(inside), placements.map { it.child })
    }
}
