package au.edu.fireballs.stage4.data.tiles

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import org.junit.Assert.assertArrayEquals
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

        val result = compositor.compose(1, 2, listOf(child), parent)

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

        val result = compositor.compose(1, 2, listOf(child), parent)

        assertTrue(result.contentEquals(LocalFileRasterTileProvider.TRANSPARENT_PNG))
    }

    @Test
    fun overLimitDecodedSourceTileIsSkipped() {
        val store = TileStore(Files.createTempDirectory("tiles").toFile())
        val compositor = LowZoomTileCompositor(store)
        val child = TileCoord(20, 887_826, 433_111)
        val parent = parentOf(child, 18)
        store.write(1, 2, 20, child.x, child.y, png(4097, 1))

        val result = compositor.compose(1, 2, listOf(child), parent)

        assertTrue(result.contentEquals(LocalFileRasterTileProvider.TRANSPARENT_PNG))
    }

    @Test
    fun snapshotOutputIsByteIdenticalToLegacyPipeline() {
        val store = TileStore(Files.createTempDirectory("tiles").toFile())
        val compositor = LowZoomTileCompositor(store)
        val parent = TileCoord(18, 10, 20)
        val children =
            listOf(
                TileCoord(20, 40, 80),
                TileCoord(20, 41, 80),
                TileCoord(20, 40, 81),
                TileCoord(20, 41, 81),
            )
        val colors =
            listOf(
                Color.argb(255, 220, 10, 20),
                Color.argb(128, 20, 210, 30),
                Color.argb(64, 30, 40, 220),
                Color.argb(255, 230, 220, 40),
            )
        children.zip(colors).forEach { (child, color) ->
            store.write(1, 2, child.z, child.x, child.y, patternedPng(color))
        }

        val expected = legacyCompose(store, 1, 2, parent)
        val actual = compositor.compose(1, 2, children, parent)

        assertArrayEquals(expected, actual)
    }

    @Test
    fun compositorUsesOnlySuppliedSnapshot() {
        val store = TileStore(Files.createTempDirectory("tiles").toFile())
        val compositor = LowZoomTileCompositor(store)
        val parent = TileCoord(19, 200, 400)
        val included = TileCoord(20, 400, 800)
        val excluded = TileCoord(20, 401, 800)
        store.write(1, 2, included.z, included.x, included.y, patternedPng(Color.MAGENTA))
        store.write(1, 2, excluded.z, excluded.x, excluded.y, patternedPng(Color.CYAN))

        val expected = legacyCompose(store, 1, 2, listOf(included), parent)
        val actual = compositor.compose(1, 2, listOf(included), parent)

        assertArrayEquals(expected, actual)
    }

    private fun legacyCompose(
        store: TileStore,
        surveyId: Long,
        candidateId: Long,
        parent: TileCoord,
    ): ByteArray =
        legacyCompose(
            store,
            surveyId,
            candidateId,
            store.candidateTiles(
                surveyId,
                candidateId,
                LowZoomTileCompositor.SOURCE_ZOOM,
            ),
            parent,
        )

    private fun legacyCompose(
        store: TileStore,
        surveyId: Long,
        candidateId: Long,
        sourceTiles: List<TileCoord>,
        parent: TileCoord,
    ): ByteArray {
        val placements = placementsFor(sourceTiles, parent, 20, 2048)
        if (placements.isEmpty()) {
            return LocalFileRasterTileProvider.TRANSPARENT_PNG
        }
        val output = Bitmap.createBitmap(2048, 2048, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        var rendered = false
        placements.forEach { placement ->
            val raw =
                store.read(surveyId, candidateId, placement.child)?.use {
                    it.readNBytes(16 * 1024 * 1024 + 1)
                }
            val bytes =
                if (raw == null || raw.size > 16 * 1024 * 1024) {
                    return@forEach
                } else {
                    raw
                }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096) {
                return@forEach
            }
            val bitmap =
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return@forEach
            val destination =
                Rect(
                    placement.left,
                    placement.top,
                    placement.left + placement.size,
                    placement.top + placement.size,
                )
            canvas.drawBitmap(bitmap, null, destination, paint)
            bitmap.recycle()
            rendered = true
        }
        if (!rendered) {
            output.recycle()
            return LocalFileRasterTileProvider.TRANSPARENT_PNG
        }
        val stream = ByteArrayOutputStream()
        output.compress(Bitmap.CompressFormat.PNG, 100, stream)
        output.recycle()
        return stream.toByteArray()
    }

    private fun patternedPng(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        bitmap.setPixel(0, 0, Color.TRANSPARENT)
        bitmap.setPixel(
            3,
            3,
            Color.argb(192, Color.red(color), 0, Color.blue(color)),
        )
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        bitmap.recycle()
        return stream.toByteArray()
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
