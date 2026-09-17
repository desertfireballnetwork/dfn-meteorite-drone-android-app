package au.edu.fireballs.stage4.data.tiles

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
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

    @Test
    fun validSourceTileRendersComposite() {
        val store = TileStore(Files.createTempDirectory("tiles").toFile())
        val compositor = LowZoomTileCompositor(store)
        val child = TileCoord(20, 887_826, 433_111)
        val parent = parentOf(child, 18)
        store.write(1, 2, 20, child.x, child.y, png(1, 1))

        val result = compositor.compose(1, 2, parent)

        assertFalse(result.contentEquals(LocalFileRasterTileProvider.TRANSPARENT_PNG))
    }

    @Test
    fun oversizedEncodedSourceTileIsSkipped() {
        val store = TileStore(Files.createTempDirectory("tiles").toFile())
        val compositor = LowZoomTileCompositor(store)
        val child = TileCoord(20, 887_826, 433_111)
        val parent = parentOf(child, 18)
        val file =
            File(
                store.surveyTilesDirectory(1),
                "2/20/${child.x}/${child.y}.png",
            )
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(16 * 1024 * 1024 + 1))

        val result = compositor.compose(1, 2, parent)

        assertTrue(result.contentEquals(LocalFileRasterTileProvider.TRANSPARENT_PNG))
    }

    @Test
    fun overLimitDecodedSourceTileIsSkipped() {
        val store = TileStore(Files.createTempDirectory("tiles").toFile())
        val compositor = LowZoomTileCompositor(store)
        val child = TileCoord(20, 887_826, 433_111)
        val parent = parentOf(child, 18)
        store.write(1, 2, 20, child.x, child.y, png(4097, 1))

        val result = compositor.compose(1, 2, parent)

        assertTrue(result.contentEquals(LocalFileRasterTileProvider.TRANSPARENT_PNG))
    }

    private fun png(
        width: Int,
        height: Int,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }
}
