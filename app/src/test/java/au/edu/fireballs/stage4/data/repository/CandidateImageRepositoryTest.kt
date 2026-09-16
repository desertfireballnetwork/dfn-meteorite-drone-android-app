package au.edu.fireballs.stage4.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

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
        clearTilesDir()
    }

    @After
    fun tearDown() {
        clearTilesDir()
    }

    private fun clearTilesDir() {
        val tilesDir = File(context.filesDir, "tiles")
        if (tilesDir.exists()) {
            tilesDir.deleteRecursively()
        }
    }

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
}
