package au.edu.fireballs.stage4.data.tiles

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LocalFileRasterTileProviderTest {
    private fun tempStore(): TileStore {
        val dir = Files.createTempDirectory("raster-provider-test").toFile()
        dir.deleteOnExit()
        return TileStore(dir)
    }

    @Test
    fun returnsFileBytesWhenPresent() {
        val store = tempStore()
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        store.write(1, 2, 3, 4, 5, bytes)
        val provider = LocalFileRasterTileProvider(store)
        assertArrayEquals(bytes, provider.tile(1, 2, 3, 4, 5))
    }

    @Test
    fun returnsTransparentPngWhenMissing() {
        val provider = LocalFileRasterTileProvider(tempStore())
        val result = provider.tile(1, 2, 3, 4, 5)
        assertArrayEquals(LocalFileRasterTileProvider.TRANSPARENT_PNG, result)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun noExceptionThrownWhenMissing() {
        val provider = LocalFileRasterTileProvider(tempStore())
        val result = provider.tile(1, 2, 3, 4, 5)
        assertEquals(68, result.size)
    }

    @Test
    fun exactMaxSizeTileIsReturned() {
        val store = tempStore()
        val bytes = ByteArray(TileStore.MAX_TILE_BYTES) { 1 }
        store.write(1, 2, 3, 4, 5, bytes)
        val provider = LocalFileRasterTileProvider(store)
        assertArrayEquals(bytes, provider.tile(1, 2, 3, 4, 5))
    }

    @Test
    fun largeTileWithinBoundsIsReturned() {
        val store = tempStore()
        val bytes = ByteArray(100 * 1024) { 7 }
        store.write(1, 2, 3, 4, 5, bytes)
        val provider = LocalFileRasterTileProvider(store)
        assertArrayEquals(bytes, provider.tile(1, 2, 3, 4, 5))
    }

    @Test
    fun oversizedTileFallsBackToTransparent() {
        val store = tempStore()
        val file = java.io.File(store.surveyTilesDirectory(1), "2/3/4/5.png")
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(TileStore.MAX_TILE_BYTES + 1) { 1 })
        val provider = LocalFileRasterTileProvider(store)
        assertArrayEquals(LocalFileRasterTileProvider.TRANSPARENT_PNG, provider.tile(1, 2, 3, 4, 5))
    }
}
