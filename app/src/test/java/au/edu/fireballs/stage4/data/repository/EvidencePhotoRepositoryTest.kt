package au.edu.fireballs.stage4.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import au.edu.fireballs.stage4.data.local.Stage4Database
import au.edu.fireballs.stage4.data.local.dao.PendingPhotoUploadDao
import au.edu.fireballs.stage4.data.remote.EvidenceService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EvidencePhotoRepositoryTest {
    private val testDispatcher = StandardTestDispatcher()
    private val evidenceService: EvidenceService = mockk()
    private lateinit var context: Context
    private lateinit var db: Stage4Database
    private lateinit var dao: PendingPhotoUploadDao
    private lateinit var repository: EvidencePhotoRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db =
            Room
                .inMemoryDatabaseBuilder(context, Stage4Database::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.pendingPhotoUploadDao()
        repository = EvidencePhotoRepository(context, dao, evidenceService, testDispatcher)
        evidenceDir().deleteRecursively()
        context.cacheDir.listFiles()?.forEach { it.delete() }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun writeSourceBitmap(
        width: Int,
        height: Int,
    ): Uri {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val file = File(context.cacheDir, "source_${width}_$height.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }

    private fun evidenceDir(): File = File(context.filesDir, "evidence")

    private fun orphanJpgs(): List<File> =
        evidenceDir()
            .walkTopDown()
            .filter { it.isFile && it.extension == "jpg" }
            .toList()

    private suspend fun <T> suspendRunCatching(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: Throwable) {
            Result.failure(e)
        }

    @Test
    fun saveLocally_writesFileAndInsertsRow() =
        runTest(testDispatcher) {
            val uri = writeSourceBitmap(4000, 3000)

            val entity = repository.saveLocally(uri, surveyId = 10L, inferenceResultId = 42L)

            val file = File(entity.localFilePath)
            assertTrue(file.exists())
            assertTrue(file.length() > 0L)
            assertEquals(10L, entity.surveyId)
            assertEquals(42L, entity.inferenceResultId)
            assertFalse(entity.uploaded)

            val rows = dao.getLocalPhotosForCandidate(42L).first()
            assertEquals(1, rows.size)
            assertEquals(entity.rowId, rows.first().rowId)
        }

    @Test
    fun saveLocally_boundsLongEdgeTo2048() =
        runTest(testDispatcher) {
            val uri = writeSourceBitmap(6000, 4000)

            val entity = repository.saveLocally(uri, surveyId = 10L, inferenceResultId = 42L)

            val options =
                BitmapFactory
                    .Options()
                    .apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(entity.localFilePath, options)
            val longEdge = maxOf(options.outWidth, options.outHeight)
            assertTrue("long edge was $longEdge, expected <= 2048", longEdge <= 2048)
        }

    @Test
    fun saveLocally_cancelledLeavesNoOrphan() =
        runTest(testDispatcher) {
            val uri = writeSourceBitmap(6000, 6000)

            val job =
                launch(testDispatcher) {
                    repository.saveLocally(uri, surveyId = 10L, inferenceResultId = 42L)
                }
            job.cancelAndJoin()

            val rows = dao.getLocalPhotosForCandidate(42L).first()
            assertTrue(rows.isEmpty())
            assertTrue(orphanJpgs().isEmpty())
        }

    @Test
    fun saveLocally_invalidUriLeavesNoOrphan() =
        runTest(testDispatcher) {
            val missing = Uri.fromFile(File(context.cacheDir, "does_not_exist.jpg"))

            val result =
                suspendRunCatching {
                    repository.saveLocally(missing, surveyId = 10L, inferenceResultId = 42L)
                }

            assertTrue(result.isFailure)
            val rows = dao.getLocalPhotosForCandidate(42L).first()
            assertTrue(rows.isEmpty())
            assertTrue(orphanJpgs().isEmpty())
        }

    @Test
    fun saveLocally_daoFailureLeavesNoOrphan() =
        runTest(testDispatcher) {
            val uri = writeSourceBitmap(100, 100)
            val failingDao =
                mockk<PendingPhotoUploadDao> {
                    coEvery { insert(any()) } throws RuntimeException("insert failed")
                }
            val repo = EvidencePhotoRepository(context, failingDao, evidenceService, testDispatcher)

            val result =
                suspendRunCatching {
                    repo.saveLocally(uri, surveyId = 10L, inferenceResultId = 42L)
                }

            assertTrue(result.isFailure)
            assertTrue(orphanJpgs().isEmpty())
        }

    @Test
    fun saveLocally_deleteSourceDeletesSourceOnSuccess() =
        runTest(testDispatcher) {
            val file = File(context.cacheDir, "source_delete_success.jpg")
            Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
                file.outputStream().use { compress(Bitmap.CompressFormat.JPEG, 90, it) }
                recycle()
            }
            val uri = Uri.fromFile(file)

            repository.saveLocally(
                uri,
                surveyId = 10L,
                inferenceResultId = 42L,
                deleteSource = true,
            )

            assertFalse(file.exists())
        }

    @Test
    fun saveLocally_deleteSourceDeletesSourceOnFailure() =
        runTest(testDispatcher) {
            val file = File(context.cacheDir, "source_delete_fail.jpg")
            Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
                file.outputStream().use { compress(Bitmap.CompressFormat.JPEG, 90, it) }
                recycle()
            }
            val uri = Uri.fromFile(file)
            val failingDao =
                mockk<PendingPhotoUploadDao> {
                    coEvery { insert(any()) } throws RuntimeException("insert failed")
                }
            val repo = EvidencePhotoRepository(context, failingDao, evidenceService, testDispatcher)

            val result =
                suspendRunCatching {
                    repo.saveLocally(
                        uri,
                        surveyId = 10L,
                        inferenceResultId = 42L,
                        deleteSource = true,
                    )
                }

            assertTrue(result.isFailure)
            assertFalse(file.exists())
        }

    @Test
    fun exifOrientationMatrix_appliesAllEightOrientations() {
        val width = 20
        val height = 30
        val cases =
            mapOf(
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL to (17f to 3f),
                ExifInterface.ORIENTATION_ROTATE_180 to (17f to 26f),
                ExifInterface.ORIENTATION_FLIP_VERTICAL to (2f to 26f),
                ExifInterface.ORIENTATION_TRANSPOSE to (3f to 2f),
                ExifInterface.ORIENTATION_ROTATE_90 to (26f to 2f),
                ExifInterface.ORIENTATION_TRANSVERSE to (26f to 17f),
                ExifInterface.ORIENTATION_ROTATE_270 to (3f to 17f),
            )
        cases.forEach { (orientation, expected) ->
            val matrix = exifOrientationMatrix(orientation, width, height)
            val point = floatArrayOf(2f, 3f)
            matrix.mapPoints(point)
            assertEquals(
                "orientation $orientation x",
                expected.first,
                point[0],
                0.001f,
            )
            assertEquals(
                "orientation $orientation y",
                expected.second,
                point[1],
                0.001f,
            )
        }
    }

    @Test
    fun exifOrientationMatrix_normalIsIdentity() {
        val matrix = exifOrientationMatrix(ExifInterface.ORIENTATION_NORMAL, 20, 30)
        assertTrue(matrix.isIdentity)
    }
}
