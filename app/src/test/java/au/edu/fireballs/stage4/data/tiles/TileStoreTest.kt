package au.edu.fireballs.stage4.data.tiles

import au.edu.fireballs.stage4.data.repository.PreDownloadPruneKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
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
    fun candidateTilesEnumeratesOnlyRequestedCandidateAndZoom() {
        val store = tempStore()
        store.write(1, 2, 20, 100, 200, byteArrayOf(1))
        store.write(1, 2, 20, 101, 201, byteArrayOf(2))
        store.write(1, 2, 21, 200, 400, byteArrayOf(3))
        store.write(1, 3, 20, 100, 200, byteArrayOf(4))

        val tiles = store.candidateTiles(1, 2, 20).toSet()

        assertTrue(TileCoord(20, 100, 200) in tiles)
        assertTrue(TileCoord(20, 101, 201) in tiles)
        assertTrue(tiles.size == 2)
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
    fun writesAreNotRejectedByAggregateUsage() {
        val base = Files.createTempDirectory("tiles-usage-limit").toFile()
        val store = TileStore(base)

        store.write(1, 2, 3, 4, 5, ByteArray(1_000_000))
        store.write(1, 2, 3, 4, 6, ByteArray(1_000_000))
        store.write(1, 2, 3, 4, 7, ByteArray(1_000_000))

        assertTrue(store.contains(1, 2, 3, 4, 5))
        assertTrue(store.contains(1, 2, 3, 4, 6))
        assertTrue(store.contains(1, 2, 3, 4, 7))

        store.deleteSurveyTiles(1)
        assertFalse(store.contains(1, 2, 3, 4, 5))
    }

    @Test
    fun measuredUsageKeepsPersistedAndTemporaryFilesNonOverlapping() =
        runTest {
            val base = Files.createTempDirectory("tiles-usage").toFile()
            val store = TileStore(base)
            store.write(1, 2, 3, 4, 5, ByteArray(7))
            val temporary = base.resolve("1/2/3/4/orphan.png.tmp")
            temporary.writeBytes(ByteArray(11))

            val usage = store.measuredUsage()

            assertTrue(usage.geotiffBytes == 7L)
            assertTrue(usage.ownedTempCacheBytes == 11L)
        }

    @Test
    fun measuredUsageForMissingRootReturnsZeros() =
        runTest {
            val parent = Files.createTempDirectory("tiles-missing").toFile()
            val store = TileStore(parent.resolve("missing"))

            val usage = store.measuredUsage()

            assertTrue(usage.geotiffBytes == 0L)
            assertTrue(usage.ownedTempCacheBytes == 0L)
        }

    @Test
    fun replacingTileAccountsOnlyForSizeDelta() {
        val base = Files.createTempDirectory("tiles-replace").toFile()
        val store = TileStore(base)

        store.write(1, 2, 3, 4, 5, byteArrayOf(1, 2, 3, 4, 5, 6, 7))
        store.write(1, 2, 3, 4, 5, byteArrayOf(1, 2, 3, 4))
        store.write(1, 2, 3, 4, 6, byteArrayOf(1, 2, 3, 4))

        assertTrue(store.contains(1, 2, 3, 4, 5))
        assertTrue(store.contains(1, 2, 3, 4, 6))
    }

    @Test
    fun isValidPayloadAcceptsEncodedPng() {
        val store = tempStore()

        assertTrue(store.isValidPayload(LocalFileRasterTileProvider.TRANSPARENT_PNG))
    }

    @Test
    fun isValidPayloadRejectsInvalidAndEmptyContent() {
        val store = tempStore()

        assertFalse(store.isValidPayload(byteArrayOf(1, 2, 3, 4)))
        assertFalse(store.isValidPayload(ByteArray(0)))
        assertFalse(store.isValidPayload(ByteArray(TileStore.MAX_TILE_BYTES + 1)))
    }

    @Test
    fun isValidTileValidatesFinalEncodedPayload() {
        val store = tempStore()
        store.write(1, 2, 3, 4, 5, LocalFileRasterTileProvider.TRANSPARENT_PNG)
        store.write(1, 2, 3, 4, 6, byteArrayOf(1, 2, 3))

        assertTrue(store.isValidTile(1, 2, 3, 4, 5))
        assertFalse(store.isValidTile(1, 2, 3, 4, 6))
        assertFalse(store.isValidTile(1, 2, 3, 4, 7))
    }

    @Test
    fun isValidTileRejectsOutOfRangeCoordinate() {
        val store = tempStore()

        assertFalse(store.isValidTile(1, 2, 3, 8, 0))
        assertFalse(store.isValidTile(1, 2, 31, 0, 0))
        assertFalse(store.isValidTile(-1, 2, 3, 4, 5))
    }

    @Test
    fun selectiveDeleteRemovesOnlyRequestedKeysAndPreservesTimestamps() {
        val dir = Files.createTempDirectory("tiles-selective").toFile()
        val store = TileStore(dir)
        val png = LocalFileRasterTileProvider.TRANSPARENT_PNG
        store.write(1, 2, 3, 4, 5, png)
        store.write(1, 2, 3, 4, 6, png)
        val survivor = dir.resolve("1/2/3/4/6.png")
        val timestamp = survivor.lastModified()

        store.deleteTiles(listOf(PreDownloadPruneKey.Geotiff(1, 2, 3, 4, 5)))

        assertFalse(dir.resolve("1/2/3/4/5.png").exists())
        assertTrue(survivor.exists())
        assertEquals(timestamp, survivor.lastModified())
    }

    @Test
    fun selectiveDeletePrunesEmptyDirectoriesBelowTheRoot() {
        val dir = Files.createTempDirectory("tiles-prune").toFile()
        val store = TileStore(dir)
        store.write(1, 2, 3, 4, 5, LocalFileRasterTileProvider.TRANSPARENT_PNG)

        store.deleteTiles(listOf(PreDownloadPruneKey.Geotiff(1, 2, 3, 4, 5)))

        assertFalse(dir.resolve("1").exists())
        assertTrue(dir.exists())
    }

    @Test
    fun selectiveDeleteRejectsSymbolicLinkWithoutTouchingTarget() {
        val dir = Files.createTempDirectory("tiles-symlink").toFile()
        val outside = Files.createTempDirectory("tiles-outside").toFile()
        val store = TileStore(dir)
        val outsideFile = File(outside, "target.png")
        outsideFile.writeBytes(LocalFileRasterTileProvider.TRANSPARENT_PNG)
        val link = dir.resolve("1/2/3/4/5.png")
        link.parentFile!!.mkdirs()
        Files.createSymbolicLink(link.toPath(), outsideFile.toPath())

        assertThrows(IOException::class.java) {
            store.deleteTiles(listOf(PreDownloadPruneKey.Geotiff(1, 2, 3, 4, 5)))
        }

        assertTrue(outsideFile.exists())
        assertTrue(Files.isSymbolicLink(link.toPath()))
    }

    @Test
    fun deleteSurveyTilesRejectsSymbolicLinkRoot() {
        val dir = Files.createTempDirectory("tiles-survey-link").toFile()
        val outside = Files.createTempDirectory("tiles-survey-outside").toFile()
        val store = TileStore(dir)
        val outsideFile = File(outside, "keep.png")
        outsideFile.writeBytes(LocalFileRasterTileProvider.TRANSPARENT_PNG)
        Files.createSymbolicLink(dir.resolve("1").toPath(), outside.toPath())

        assertThrows(IOException::class.java) {
            store.deleteSurveyTiles(1)
        }

        assertTrue(outsideFile.exists())
    }

    @Test
    fun clearAllRemovesOrphanedTemporaryFiles() {
        val dir = Files.createTempDirectory("tiles-orphan").toFile()
        val store = TileStore(dir)
        store.write(1, 2, 3, 4, 5, LocalFileRasterTileProvider.TRANSPARENT_PNG)
        dir.resolve("1/2/3/4/9.png.tmp").writeBytes(byteArrayOf(1, 2, 3))

        store.deleteAll()

        assertFalse(dir.exists())
    }

    @Test
    fun clearAllDoesNotFollowSymbolicLinks() {
        val dir = Files.createTempDirectory("tiles-clear-link").toFile()
        val outside = Files.createTempDirectory("tiles-clear-outside").toFile()
        val store = TileStore(dir)
        val outsideFile = File(outside, "keep.png")
        outsideFile.writeBytes(LocalFileRasterTileProvider.TRANSPARENT_PNG)
        store.write(1, 2, 3, 4, 5, LocalFileRasterTileProvider.TRANSPARENT_PNG)
        val link = dir.resolve("evil")
        Files.createSymbolicLink(link.toPath(), outside.toPath())

        store.deleteAll()

        assertTrue(outsideFile.exists())
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertFalse(dir.resolve("1").exists())
    }

    @Test
    fun writeLeavesNoTemporaryFilesAfterSuccess() {
        val dir = Files.createTempDirectory("tiles-temp").toFile()
        val store = TileStore(dir)

        store.write(1, 2, 3, 4, 5, LocalFileRasterTileProvider.TRANSPARENT_PNG)

        val entries = dir.resolve("1/2/3/4").listFiles() ?: emptyArray()
        assertTrue(entries.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun atomicReplaceFallsBackWhenAtomicMoveIsUnsupported() {
        val source = File.createTempFile("atomic-source", ".tmp")
        val target = File.createTempFile("atomic-target", ".png")
        source.writeBytes(byteArrayOf(1, 2, 3))
        var fallbackUsed = false

        replaceFileAtomically(source, target) { from, to, atomic ->
            if (atomic) {
                throw java.nio.file.AtomicMoveNotSupportedException(
                    from.path,
                    to.path,
                    "unsupported",
                )
            }
            fallbackUsed = true
            from.copyTo(to, overwrite = true)
            from.delete()
        }

        assertTrue(fallbackUsed)
        assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
    }
}
