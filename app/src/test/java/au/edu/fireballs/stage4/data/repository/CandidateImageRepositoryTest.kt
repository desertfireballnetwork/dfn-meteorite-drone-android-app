package au.edu.fireballs.stage4.data.repository

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CandidateImageRepositoryTest {
    private lateinit var context: Context
    private lateinit var repository: CandidateImageRepository
    private val serverUrl = "https://find.gfo.rocks/"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository =
            CandidateImageRepository(
                context = context,
                serverUrl = serverUrl,
            )
        clearStorage()
    }

    @After
    fun tearDown() {
        clearStorage()
    }

    private fun clearStorage() {
        listOf("crops", "tiles").forEach { name ->
            deleteNoFollow(File(context.filesDir, name))
        }
    }

    private fun deleteNoFollow(file: File) {
        val path = file.toPath()
        if (Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path)
            return
        }
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteNoFollow(it) }
        }
        Files.deleteIfExists(path)
    }

    private fun jpegBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        bitmap.recycle()
        return output.toByteArray()
    }

    private fun cropTempFiles(surveyId: Long): List<File> =
        File(context.filesDir, "crops/$surveyId")
            .listFiles()
            ?.filter { it.name.contains(".tmp") }
            .orEmpty()

    @Test
    fun getCroppedImageUrl_trimsTrailingSlashAndFormatsUrl() {
        val expected = "https://find.gfo.rocks/image_survey_cropped/42/"
        assertEquals(expected, repository.getCroppedImageUrl(42L))

        val repoWithoutTrailingSlash =
            CandidateImageRepository(
                context = context,
                serverUrl = "https://find.gfo.rocks",
            )
        assertEquals(expected, repoWithoutTrailingSlash.getCroppedImageUrl(42L))
    }

    @Test
    fun getCandidateTileUrlPattern_trimsTrailingSlashAndFormatsPattern() {
        val expected =
            "https://find.gfo.rocks/image_geotiff_candidate_tile/10/42/{z}/{x}/{y}/"
        assertEquals(expected, repository.getCandidateTileUrlPattern(10L, 42L))

        val repoWithoutTrailingSlash =
            CandidateImageRepository(
                context = context,
                serverUrl = "https://find.gfo.rocks",
            )
        assertEquals(
            expected,
            repoWithoutTrailingSlash.getCandidateTileUrlPattern(10L, 42L),
        )
    }

    @Test
    fun getLocalCropImageFile_returnsNullWhenFileDoesNotExist() {
        assertNull(repository.getLocalCropImageFile(10L, 42L))
    }

    @Test
    fun getLocalCropImageFile_returnsNullWhenFileIsEmpty() {
        val file = File(context.filesDir, "crops/10/42.jpg")
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(0))

        assertNull(repository.getLocalCropImageFile(10L, 42L))
    }

    @Test
    fun getLocalCropImageFile_returnsFileWhenPresentAndNonEmpty() {
        val file = File(context.filesDir, "crops/10/42.jpg")
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3, 4))

        val result = repository.getLocalCropImageFile(10L, 42L)
        assertEquals(file.absolutePath, result?.absolutePath)
    }

    @Test
    fun buildCroppedImageRequest_returnsRemoteUrlWhenLocalFileAbsent() {
        val request =
            repository.buildCroppedImageRequest(
                inferenceResultId = 42L,
                surveyId = 10L,
            )

        assertEquals("https://find.gfo.rocks/image_survey_cropped/42/", request.data)
    }

    @Test
    fun buildCroppedImageRequest_prefersLocalFileWhenPresent() {
        val file = File(context.filesDir, "crops/10/42.jpg")
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3, 4))

        val request =
            repository.buildCroppedImageRequest(
                inferenceResultId = 42L,
                surveyId = 10L,
            )

        assertEquals(file, request.data)
    }

    @Test
    fun buildCroppedImageRequest_retryKeyChangesCacheKey() {
        val initial =
            repository.buildCroppedImageRequest(
                inferenceResultId = 42L,
                surveyId = 10L,
                retryKey = 0,
            )
        val retried =
            repository.buildCroppedImageRequest(
                inferenceResultId = 42L,
                surveyId = 10L,
                retryKey = 1,
            )

        assertNotEquals(initial.memoryCacheKey, retried.memoryCacheKey)
    }

    @Test
    fun writeCrop_promotesValidJpegAndRemovesTemporaryFile() {
        val result = repository.writeCrop(10L, 42L, jpegBytes())

        assertEquals(CropWriteResult.Success, result)
        assertTrue(repository.isValidCrop(10L, 42L))
        assertTrue(cropTempFiles(10L).isEmpty())
    }

    @Test
    fun writeCrop_replacesExistingCropAtomically() {
        repository.writeCrop(10L, 42L, jpegBytes())
        val firstLength = repository.getLocalCropImageFile(10L, 42L)?.length()

        val result = repository.writeCrop(10L, 42L, jpegBytes())

        assertEquals(CropWriteResult.Success, result)
        assertTrue(repository.isValidCrop(10L, 42L))
        assertEquals(firstLength, repository.getLocalCropImageFile(10L, 42L)?.length())
        assertTrue(cropTempFiles(10L).isEmpty())
    }

    @Test
    fun writeCrop_rejectsInvalidContentAndRemovesTemporaryFile() {
        val result = repository.writeCrop(10L, 42L, byteArrayOf(1, 2, 3, 4))

        assertEquals(CropWriteResult.InvalidContent, result)
        assertNull(repository.getLocalCropImageFile(10L, 42L))
        assertTrue(cropTempFiles(10L).isEmpty())
    }

    @Test
    fun writeCrop_rejectsEmptyAndOversizedContent() {
        assertEquals(CropWriteResult.InvalidContent, repository.writeCrop(10L, 42L, ByteArray(0)))
        val oversized = ByteArray(CandidateImageRepository.MAX_CROP_BYTES + 1)
        assertEquals(CropWriteResult.InvalidContent, repository.writeCrop(10L, 42L, oversized))
        assertTrue(cropTempFiles(10L).isEmpty())
    }

    @Test
    fun writeCrop_returnsIoFailureWhenDirectoryCannotBeCreated() {
        val blocked = File(context.filesDir, "crops/10")
        blocked.parentFile?.mkdirs()
        blocked.writeBytes(byteArrayOf(1))

        val result = repository.writeCrop(10L, 42L, jpegBytes())

        assertTrue(result is CropWriteResult.IoFailure)
        assertTrue(blocked.isFile)
    }

    @Test
    fun writeCrop_rejectsNegativeIdentifier() {
        assertThrows(IllegalArgumentException::class.java) {
            repository.writeCrop(-1L, 42L, jpegBytes())
        }
    }

    @Test
    fun writeCrop_rejectsSymlinkedCropRootAndLeavesTargetUntouched() {
        val outside = Files.createTempDirectory("crop-escape").toFile()
        val cropsDir = File(context.filesDir, "crops")
        cropsDir.mkdirs()
        Files.createSymbolicLink(File(cropsDir, "10").toPath(), outside.toPath())

        val result = repository.writeCrop(10L, 42L, jpegBytes())

        assertTrue(result is CropWriteResult.IoFailure)
        assertFalse(repository.isValidCrop(10L, 42L))
        assertTrue(outside.listFiles().isNullOrEmpty())
    }

    @Test
    fun cropWriteFailure_classifiesStorageFullAndGenericIoFailure() {
        assertTrue(
            cropWriteFailure(IOException("ENOSPC: No space left on device")) ==
                CropWriteResult.StorageFull,
        )
        assertTrue(
            cropWriteFailure(IOException("write failed")) is CropWriteResult.IoFailure,
        )
    }

    @Test
    fun isValidCrop_rejectsMissingEmptyAndInvalidContent() {
        assertFalse(repository.isValidCrop(10L, 42L))

        val file = File(context.filesDir, "crops/10/42.jpg")
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(0))
        assertFalse(repository.isValidCrop(10L, 42L))

        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        assertFalse(repository.isValidCrop(10L, 42L))
    }

    @Test
    fun isValidCrop_acceptsDecodableJpeg() {
        val file = File(context.filesDir, "crops/10/42.jpg")
        file.parentFile?.mkdirs()
        file.writeBytes(jpegBytes())

        assertTrue(repository.isValidCrop(10L, 42L))
    }

    @Test
    fun deleteCrops_removesOnlyRequestedKeysAndPreservesTimestamps() {
        repository.writeCrop(10L, 1L, jpegBytes())
        repository.writeCrop(10L, 2L, jpegBytes())
        val survivor = File(context.filesDir, "crops/10/2.jpg")
        val timestamp = survivor.lastModified()

        repository.deleteCrops(listOf(PreDownloadPruneKey.Crop(10L, 1L)))

        assertNull(repository.getLocalCropImageFile(10L, 1L))
        assertTrue(survivor.exists())
        assertEquals(timestamp, survivor.lastModified())
    }

    @Test
    fun deleteCrops_rejectsSymbolicLinkWithoutTouchingTarget() {
        val outside = Files.createTempDirectory("crop-delete-outside").toFile()
        val outsideFile = File(outside, "target.jpg")
        outsideFile.writeBytes(jpegBytes())
        val link = File(context.filesDir, "crops/10/1.jpg")
        link.parentFile?.mkdirs()
        Files.createSymbolicLink(link.toPath(), outsideFile.toPath())

        assertThrows(IOException::class.java) {
            repository.deleteCrops(listOf(PreDownloadPruneKey.Crop(10L, 1L)))
        }

        assertTrue(outsideFile.exists())
        assertTrue(Files.isSymbolicLink(link.toPath()))
    }

    @Test
    fun clearAllCrops_removesOwnedCropsAndLeavesSymbolicLinks() {
        repository.writeCrop(10L, 1L, jpegBytes())
        val outside = Files.createTempDirectory("crop-clear-outside").toFile()
        val outsideFile = File(outside, "keep.jpg")
        outsideFile.writeBytes(jpegBytes())
        val link = File(context.filesDir, "crops/evil")
        Files.createSymbolicLink(link.toPath(), outside.toPath())

        repository.clearAllCrops()

        assertNull(repository.getLocalCropImageFile(10L, 1L))
        assertTrue(outsideFile.exists())
        assertTrue(Files.isSymbolicLink(link.toPath()))
    }
}
