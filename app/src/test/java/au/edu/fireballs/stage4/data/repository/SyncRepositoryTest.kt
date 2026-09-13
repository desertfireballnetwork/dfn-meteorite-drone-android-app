package au.edu.fireballs.stage4.data.repository

import au.edu.fireballs.stage4.data.local.LocalDecisionEntity
import au.edu.fireballs.stage4.data.local.PendingPhotoUploadEntity
import au.edu.fireballs.stage4.data.local.dao.LocalDecisionDao
import au.edu.fireballs.stage4.data.local.dao.PendingPhotoUploadDao
import au.edu.fireballs.stage4.data.remote.EvidenceService
import au.edu.fireballs.stage4.data.remote.Stage4Service
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SyncRepositoryTest {
    private lateinit var mockWebServer: MockWebServer
    private lateinit var repository: SyncRepository
    private lateinit var photoDao: FakePendingPhotoUploadDao
    private lateinit var decisionDao: FakeLocalDecisionDao
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        val moshi =
            Moshi
                .Builder()
                .addLast(KotlinJsonAdapterFactory())
                .build()

        val retrofit =
            Retrofit
                .Builder()
                .baseUrl(mockWebServer.url("/"))
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()

        photoDao = FakePendingPhotoUploadDao()
        decisionDao = FakeLocalDecisionDao()
        repository =
            SyncRepository(
                evidenceService = retrofit.create(EvidenceService::class.java),
                stage4Service = retrofit.create(Stage4Service::class.java),
                pendingPhotoUploadDao = photoDao,
                localDecisionDao = decisionDao,
                moshi = moshi,
                ioDispatcher = testDispatcher,
            )
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    private fun tempPhotoFile(): File =
        File.createTempFile("evidence", ".jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }

    private fun pendingPhoto(
        file: File,
        rowId: Long = 1L,
    ): PendingPhotoUploadEntity =
        PendingPhotoUploadEntity(
            rowId = rowId,
            surveyId = 7L,
            inferenceResultId = 101L,
            localFilePath = file.absolutePath,
            capturedAt = "2026-01-01T00:00:00Z",
        )

    private fun localDecision(): LocalDecisionEntity =
        LocalDecisionEntity(
            inferenceResultId = 101L,
            surveyId = 7L,
            verdict = true,
            detectionTagId = 3L,
            capturedAt = "2026-01-01T00:00:00Z",
            evidencePhotoRowId = null,
        )

    @Test
    fun `201 upload marks row uploaded`() =
        runTest(testDispatcher) {
            val file = tempPhotoFile()
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(201)
                    .setBody("""{"id": 123}"""),
            )

            val result = repository.uploadPhoto(pendingPhoto(file), 7L)

            assertEquals(PhotoUploadResult.Success, result)
            assertEquals(listOf(1L to 123L), photoDao.markedUploaded)
            assertTrue(photoDao.markedFailed.isEmpty())
            file.delete()
        }

    @Test
    fun `200 upload marks row uploaded`() =
        runTest(testDispatcher) {
            val file = tempPhotoFile()
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"id": 123}"""),
            )

            val result = repository.uploadPhoto(pendingPhoto(file), 7L)

            assertEquals(PhotoUploadResult.Success, result)
            assertEquals(listOf(1L to 123L), photoDao.markedUploaded)
            assertTrue(photoDao.markedFailed.isEmpty())
            file.delete()
        }

    @Test
    fun `403 upload with cross campaign body marks row failed cross campaign`() =
        runTest(testDispatcher) {
            val file = tempPhotoFile()
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(403)
                    .setBody("""{"error": "inference_result_does_not_belong_to_survey"}"""),
            )

            val result = repository.uploadPhoto(pendingPhoto(file), 7L)

            assertEquals(PhotoUploadResult.CrossCampaign, result)
            assertEquals(listOf(1L to "cross_campaign"), photoDao.markedFailed)
            assertTrue(photoDao.markedUploaded.isEmpty())
            file.delete()
        }

    @Test
    fun `403 upload with generic body returns error and preserves row`() =
        runTest(testDispatcher) {
            val file = tempPhotoFile()
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(403)
                    .setBody("""{"error": "survey_access_denied"}"""),
            )

            val result = repository.uploadPhoto(pendingPhoto(file), 7L)

            assertEquals(
                PhotoUploadResult.Error("Forbidden: {\"error\": \"survey_access_denied\"}"),
                result,
            )
            assertEquals(
                listOf(1L to "Forbidden: {\"error\": \"survey_access_denied\"}"),
                photoDao.markedFailed,
            )
            assertTrue(photoDao.markedUploaded.isEmpty())
            file.delete()
        }

    @Test
    fun `200 verdict marks decision synced`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("OK"))

            val result = repository.postVerdict(localDecision(), 7L)

            assertEquals(VerdictPostResult.Success, result)
            assertEquals(listOf(101L), decisionDao.markedSynced.map { it.first })
            assertTrue(decisionDao.markedFailed.isEmpty())
        }

    @Test
    fun `403 verdict marks decision failed claim required`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(403)
                    .setBody("claim-required"),
            )

            val result = repository.postVerdict(localDecision(), 7L)

            assertEquals(VerdictPostResult.ClaimRequired, result)
            assertEquals(listOf(101L to "claim_required"), decisionDao.markedFailed)
            assertTrue(decisionDao.markedSynced.isEmpty())
        }

    @Test
    fun `409 already_completed verdict marks decision synced`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(409)
                    .setBody("""{"status": "already_completed"}"""),
            )

            val result = repository.postVerdict(localDecision(), 7L)

            assertEquals(VerdictPostResult.AlreadyCompleted, result)
            assertEquals(listOf(101L), decisionDao.markedSynced.map { it.first })
            assertTrue(decisionDao.markedFailed.isEmpty())
        }

    @Test
    fun `403 with non claim required body returns error and marks failed`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(403)
                    .setBody("Forbidden"),
            )

            val result = repository.postVerdict(localDecision(), 7L)

            assertEquals(VerdictPostResult.Error("Forbidden: Forbidden"), result)
            assertEquals(listOf(101L to "Forbidden: Forbidden"), decisionDao.markedFailed)
            assertTrue(decisionDao.markedSynced.isEmpty())
        }

    @Test
    fun `409 with malformed body returns error and marks failed`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(409)
                    .setBody("""{"foo": "bar"}"""),
            )

            val result = repository.postVerdict(localDecision(), 7L)

            assertEquals(VerdictPostResult.Error("Conflict: {\"foo\": \"bar\"}"), result)
            assertEquals(listOf(101L to "Conflict: {\"foo\": \"bar\"}"), decisionDao.markedFailed)
            assertTrue(decisionDao.markedSynced.isEmpty())
        }

    @Test
    fun `upload uses passed survey id for request route`() =
        runTest(testDispatcher) {
            val file = tempPhotoFile()
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(201)
                    .setBody("""{"id": 123}"""),
            )

            val result = repository.uploadPhoto(pendingPhoto(file), 99L)

            assertEquals(PhotoUploadResult.Success, result)
            val request = mockWebServer.takeRequest()
            assertTrue(request.path!!.contains("/99/"))
            file.delete()
        }

    @Test
    fun `401 upload preserves row`() =
        runTest(testDispatcher) {
            val file = tempPhotoFile()
            mockWebServer.enqueue(MockResponse().setResponseCode(401))

            val result = repository.uploadPhoto(pendingPhoto(file), 7L)

            assertEquals(PhotoUploadResult.AuthExpired, result)
            assertTrue(photoDao.markedUploaded.isEmpty())
            assertTrue(photoDao.markedFailed.isEmpty())
            file.delete()
        }

    @Test
    fun `302 login redirect on upload maps to auth expired and preserves row`() =
        runTest(testDispatcher) {
            val file = tempPhotoFile()
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "/login"),
            )
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/html")
                    .setBody("<html><body>Login Page</body></html>"),
            )

            val result = repository.uploadPhoto(pendingPhoto(file), 7L)

            assertEquals(PhotoUploadResult.AuthExpired, result)
            assertTrue(photoDao.markedUploaded.isEmpty())
            assertTrue(photoDao.markedFailed.isEmpty())
            file.delete()
        }

    @Test
    fun `missing photo file maps to permanent error and marks row failed`() =
        runTest(testDispatcher) {
            val missing = File("/nonexistent/evidence.jpg")

            val result = repository.uploadPhoto(pendingPhoto(missing), 7L)

            assertEquals(PhotoUploadResult.Error("file_missing"), result)
            assertTrue(photoDao.markedUploaded.isEmpty())
            assertEquals(listOf(1L to "file_missing"), photoDao.markedFailed)
        }

    @Test
    fun `401 verdict preserves row`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(MockResponse().setResponseCode(401))

            val result = repository.postVerdict(localDecision(), 7L)

            assertEquals(VerdictPostResult.AuthExpired, result)
            assertTrue(decisionDao.markedSynced.isEmpty())
            assertTrue(decisionDao.markedFailed.isEmpty())
        }

    @Test
    fun `network loss on upload preserves row`() =
        runTest(testDispatcher) {
            val file = tempPhotoFile()
            mockWebServer.enqueue(
                MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START),
            )

            val result = repository.uploadPhoto(pendingPhoto(file), 7L)

            assertEquals(PhotoUploadResult.NetworkError, result)
            assertTrue(photoDao.markedUploaded.isEmpty())
            assertTrue(photoDao.markedFailed.isEmpty())
            file.delete()
        }

    @Test
    fun `network loss on verdict preserves row`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(
                MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START),
            )

            val result = repository.postVerdict(localDecision(), 7L)

            assertEquals(VerdictPostResult.NetworkError, result)
            assertTrue(decisionDao.markedSynced.isEmpty())
            assertTrue(decisionDao.markedFailed.isEmpty())
        }
}

private class FakePendingPhotoUploadDao : PendingPhotoUploadDao {
    val markedUploaded = mutableListOf<Pair<Long, Long>>()
    val markedFailed = mutableListOf<Pair<Long, String>>()

    override suspend fun insert(pendingPhotoUpload: PendingPhotoUploadEntity): Long = 1L

    override suspend fun getUnuploaded(): List<PendingPhotoUploadEntity> = emptyList()

    override fun getLocalPhotosForCandidate(
        inferenceResultId: Long,
    ): Flow<List<PendingPhotoUploadEntity>> = flowOf(emptyList())

    override suspend fun markUploaded(
        rowId: Long,
        serverPhotoId: Long,
    ) {
        markedUploaded.add(rowId to serverPhotoId)
    }

    override suspend fun markFailed(
        rowId: Long,
        reason: String,
    ) {
        markedFailed.add(rowId to reason)
    }

    override suspend fun deleteAll() = Unit
}

private class FakeLocalDecisionDao : LocalDecisionDao {
    val markedSynced = mutableListOf<Pair<Long, String>>()
    val markedFailed = mutableListOf<Pair<Long, String>>()

    override suspend fun saveDecision(decision: LocalDecisionEntity) = Unit

    override suspend fun getUnsynced(): List<LocalDecisionEntity> = emptyList()

    override fun observeDecisionsForSurvey(surveyId: Long): Flow<List<LocalDecisionEntity>> =
        flowOf(emptyList())

    override fun getVerdict(inferenceResultId: Long): Flow<LocalDecisionEntity?> = flowOf(null)

    override suspend fun deleteByInferenceResultId(inferenceResultId: Long) = Unit

    override suspend fun markSynced(
        inferenceResultId: Long,
        syncedAt: String,
    ) {
        markedSynced.add(inferenceResultId to syncedAt)
    }

    override suspend fun markFailed(
        inferenceResultId: Long,
        reason: String,
    ) {
        markedFailed.add(inferenceResultId to reason)
    }

    override suspend fun deleteAll() = Unit
}
