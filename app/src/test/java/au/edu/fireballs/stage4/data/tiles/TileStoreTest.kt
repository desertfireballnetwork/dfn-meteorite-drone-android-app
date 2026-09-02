package au.edu.fireballs.stage4.data.tiles

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TileStoreTest {
    private fun tempStore(): TileStore {
        val dir = Files.createTempDirectory("tilestore-test").toFile()
        dir.deleteOnExit()
        return TileStore(dir)
    }

    @Test
    fun writeThenContainsReturnsTrue() {
        val store = tempStore()
        store.write(1, 2, 3, 4, 5, byteArrayOf(1, 2, 3))
        assertTrue(store.contains(1, 2, 3, 4, 5))
    }

    @Test
    fun writeThenReadReturnsSameBytes() {
        val store = tempStore()
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        store.write(1, 2, 3, 4, 5, bytes)
        val read = store.read(1, 2, 3, 4, 5)
        assertArrayEquals(bytes, read!!.readBytes())
    }

    @Test
    fun readReturnsNullWhenMissing() {
        val store = tempStore()
        assertNull(store.read(1, 2, 3, 4, 5))
        assertFalse(store.contains(1, 2, 3, 4, 5))
    }

    @Test
    fun deleteSurveyTilesRemovesSurveyTreeButLeavesOthers() {
        val store = tempStore()
        store.write(1, 2, 3, 4, 5, byteArrayOf(1))
        store.write(6, 7, 8, 9, 10, byteArrayOf(2))
        store.deleteSurveyTiles(1)
        assertFalse(store.contains(1, 2, 3, 4, 5))
        assertTrue(store.contains(6, 7, 8, 9, 10))
    }

    @Test
    fun deleteAllRemovesEverything() {
        val store = tempStore()
        store.write(1, 2, 3, 4, 5, byteArrayOf(1))
        store.write(6, 7, 8, 9, 10, byteArrayOf(2))
        store.deleteAll()
        assertFalse(store.contains(1, 2, 3, 4, 5))
        assertFalse(store.contains(6, 7, 8, 9, 10))
    }

    @Test
    fun oversizedTileIsRejected() {
        val store = tempStore()
        val oversized = ByteArray(TileStore.MAX_TILE_BYTES + 1)
        assertThrows(IllegalArgumentException::class.java) {
            store.write(1, 2, 3, 4, 5, oversized)
        }
        assertFalse(store.contains(1, 2, 3, 4, 5))
    }

    @Test
    fun outOfRangeTileCoordinateIsRejected() {
        val store = tempStore()
        assertThrows(IllegalArgumentException::class.java) {
            store.write(1, 2, 3, 8, 0, byteArrayOf(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.write(1, 2, 31, 0, 0, byteArrayOf(1))
        }
    }

    @Test
    fun negativeIdentifierIsRejected() {
        val store = tempStore()
        assertThrows(IllegalArgumentException::class.java) {
            store.write(-1, 2, 3, 4, 5, byteArrayOf(1))
        }
    }
}
