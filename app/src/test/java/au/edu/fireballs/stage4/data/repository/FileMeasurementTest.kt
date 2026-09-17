package au.edu.fireballs.stage4.data.repository

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.nio.file.Files

class FileMeasurementTest {
    @Test
    fun missingAndEmptyRootsReturnZero() =
        runTest {
            val root = Files.createTempDirectory("file-measurement").toFile()
            assertEquals(0L, regularFileBytes(root))
            assertEquals(0L, regularFileBytes(root.resolve("missing")))
        }

    @Test
    fun nestedRegularFilesAreSummed() =
        runTest {
            val root = Files.createTempDirectory("file-measurement").toFile()
            root.resolve("one.bin").writeBytes(ByteArray(3))
            root.resolve("nested").mkdirs()
            root.resolve("nested/two.bin").writeBytes(ByteArray(7))

            assertEquals(10L, regularFileBytes(root))
        }

    @Test
    fun symbolicLinksAreNotFollowed() =
        runTest {
            val root = Files.createTempDirectory("file-measurement").toFile()
            val target = root.resolve("target.bin")
            target.writeBytes(ByteArray(5))
            try {
                Files.createSymbolicLink(root.resolve("link.bin").toPath(), target.toPath())
            } catch (error: UnsupportedOperationException) {
                assumeNoException(error)
            } catch (error: SecurityException) {
                assumeNoException(error)
            }

            assertEquals(5L, regularFileBytes(root))
        }
}
