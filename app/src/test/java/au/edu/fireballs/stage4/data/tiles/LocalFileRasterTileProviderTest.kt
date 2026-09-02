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
        assertEquals(67, result.size)
    }
}
