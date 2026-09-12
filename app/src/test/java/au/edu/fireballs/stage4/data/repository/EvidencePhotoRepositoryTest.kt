package au.edu.fireballs.stage4.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import au.edu.fireballs.stage4.data.local.Stage4Database
import au.edu.fireballs.stage4.data.local.dao.PendingPhotoUploadDao
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
        repository = EvidencePhotoRepository(context, dao, testDispatcher)
        evidenceDir().deleteRecursively()
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
                runCatching {
                    repository.saveLocally(missing, surveyId = 10L, inferenceResultId = 42L)
                }

            assertTrue(result.isFailure)
            val rows = dao.getLocalPhotosForCandidate(42L).first()
            assertTrue(rows.isEmpty())
            assertTrue(orphanJpgs().isEmpty())
        }
}
