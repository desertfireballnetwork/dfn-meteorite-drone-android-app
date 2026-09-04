package au.edu.fireballs.stage4.data.tiles

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
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

    @Test
    fun accountScopesAreIsolated() {
        val base = Files.createTempDirectory("tiles-scope").toFile()
        val storeA = TileStore(base, scopeProvider = { "account-a" })
        val storeB = TileStore(base, scopeProvider = { "account-b" })

        storeA.write(1, 2, 3, 4, 5, byteArrayOf(1, 2, 3))
        storeB.write(6, 7, 8, 9, 10, byteArrayOf(4, 5, 6))

        assertTrue(storeA.contains(1, 2, 3, 4, 5))
        assertTrue(storeB.contains(6, 7, 8, 9, 10))

        storeA.deleteSurveyTiles(1)

        assertFalse(storeA.contains(1, 2, 3, 4, 5))
        assertTrue(storeB.contains(6, 7, 8, 9, 10))
    }

    @Test
    fun deleteScopeRemovesOnlyThatAccount() {
        val base = Files.createTempDirectory("tiles-delete-scope").toFile()
        val storeA = TileStore(base, scopeProvider = { "account-a" })
        val storeB = TileStore(base, scopeProvider = { "account-b" })

        storeA.write(1, 2, 3, 4, 5, byteArrayOf(1, 2, 3))
        storeB.write(6, 7, 8, 9, 10, byteArrayOf(4, 5, 6))

        storeA.deleteScope("account-a")

        assertFalse(storeA.contains(1, 2, 3, 4, 5))
        assertTrue(storeB.contains(6, 7, 8, 9, 10))
    }

    @Test
    fun aggregateQuotaRejectsExcessWrites() {
        val base = Files.createTempDirectory("tiles-quota").toFile()
        val store = TileStore(base, quotaBytes = 10)

        store.write(1, 2, 3, 4, 5, byteArrayOf(1, 2, 3, 4, 5, 6, 7))

        assertThrows(IllegalStateException::class.java) {
            store.write(1, 2, 3, 4, 6, byteArrayOf(1, 2, 3, 4))
        }
        assertFalse(store.contains(1, 2, 3, 4, 6))

        store.deleteSurveyTiles(1)
        store.write(1, 2, 3, 4, 6, byteArrayOf(1, 2, 3, 4))
        assertTrue(store.contains(1, 2, 3, 4, 6))
    }

    @Test
    fun replacingTileAccountsOnlyForSizeDelta() {
        val base = Files.createTempDirectory("tiles-replace").toFile()
        val store = TileStore(base, quotaBytes = 10)

        store.write(1, 2, 3, 4, 5, byteArrayOf(1, 2, 3, 4, 5, 6, 7))
        store.write(1, 2, 3, 4, 5, byteArrayOf(1, 2, 3, 4))
        store.write(1, 2, 3, 4, 6, byteArrayOf(1, 2, 3, 4))

        assertTrue(store.contains(1, 2, 3, 4, 5))
        assertTrue(store.contains(1, 2, 3, 4, 6))
    }

    @Test
    fun invalidScopeIsRejected() {
        val base = Files.createTempDirectory("tiles-scope-invalid").toFile()
        for (badScope in listOf(".", "..", "a/b", "a\\b", "a b", "a.b", "x".repeat(129))) {
            assertThrows(IllegalArgumentException::class.java) {
                TileStore(base, scopeProvider = { badScope }).write(1, 2, 3, 4, 5, byteArrayOf(1))
            }
        }
    }

    @Test
    fun validScopesStayContained() {
        val base = Files.createTempDirectory("tiles-scope-valid").toFile()
        val store = TileStore(base, scopeProvider = { "session-0123456789abcdef" })
        store.write(1, 2, 3, 4, 5, byteArrayOf(1))
        assertTrue(store.contains(1, 2, 3, 4, 5))
        assertTrue(File(base, "session-0123456789abcdef/1/2/3/4/5.png").isFile)
    }
}
