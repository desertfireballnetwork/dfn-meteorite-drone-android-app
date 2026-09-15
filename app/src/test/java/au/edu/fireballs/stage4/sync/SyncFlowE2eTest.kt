package au.edu.fireballs.stage4.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import au.edu.fireballs.stage4.data.local.CandidateEntity
import au.edu.fireballs.stage4.data.local.ClaimEntity
import au.edu.fireballs.stage4.data.local.LocalDecisionEntity
import au.edu.fireballs.stage4.data.local.PendingPhotoUploadEntity
import au.edu.fireballs.stage4.data.local.Stage4Database
import au.edu.fireballs.stage4.data.local.dao.CandidateDao
import au.edu.fireballs.stage4.data.local.dao.ClaimDao
import au.edu.fireballs.stage4.data.local.dao.LocalDecisionDao
import au.edu.fireballs.stage4.data.local.dao.PendingPhotoUploadDao
import au.edu.fireballs.stage4.data.local.dao.SyncRunDao
import au.edu.fireballs.stage4.data.remote.AccountManager
import au.edu.fireballs.stage4.data.remote.EvidenceService
import au.edu.fireballs.stage4.data.remote.Stage4Service
import au.edu.fireballs.stage4.data.remote.dto.ClaimRequestDto
import au.edu.fireballs.stage4.data.remote.fixture.FixtureLoader
import au.edu.fireballs.stage4.data.remote.fixture.MockServerContract
import au.edu.fireballs.stage4.data.repository.SyncRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SyncFlowE2eTest {
    private lateinit var server: MockWebServer
    private lateinit var db: Stage4Database
    private lateinit var candidateDao: CandidateDao
    private lateinit var claimDao: ClaimDao
    private lateinit var photoDao: PendingPhotoUploadDao
    private lateinit var decisionDao: LocalDecisionDao
    private lateinit var syncRunDao: SyncRunDao
    private lateinit var stage4Service: Stage4Service
    private lateinit var evidenceService: EvidenceService
    private lateinit var loader: FixtureLoader
    private lateinit var harness: MockServerContract
    private lateinit var syncRepository: SyncRepository
    private val testDispatcher = UnconfinedTestDispatcher()
    private val tempFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        db =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    Stage4Database::class.java,
                ).allowMainThreadQueries()
                .build()
        candidateDao = db.candidateDao()
        claimDao = db.claimDao()
        photoDao = db.pendingPhotoUploadDao()
        decisionDao = db.localDecisionDao()
        syncRunDao = db.syncRunDao()
        val moshi =
            Moshi
                .Builder()
                .addLast(KotlinJsonAdapterFactory())
                .build()
        val retrofit =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
        stage4Service = retrofit.create(Stage4Service::class.java)
        evidenceService = retrofit.create(EvidenceService::class.java)
        loader = FixtureLoader()
        harness = MockServerContract(server)
        syncRepository =
            SyncRepository(
                evidenceService,
                stage4Service,
                photoDao,
                decisionDao,
                moshi,
                testDispatcher,
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
        tempFiles.forEach { it.delete() }
    }

    @Test
    fun `canonical claim evidence verdict lifecycle passes`() =
        runTest(testDispatcher) {
            val surveyId = 1L
            val irId = 1L
            candidateDao.upsert(candidate(irId, surveyId))
            photoDao.insert(pendingPhoto(irId, surveyId, tempFile()))
            decisionDao.saveDecision(decision(irId, surveyId))

            val claimContract = loader.load("claim-ok")
            harness.enqueue(claimContract)
            val claimResponse =
                stage4Service.claimCandidate(surveyId.toString(), ClaimRequestDto(listOf(irId)))
            assertEquals(listOf(irId), claimResponse.claimed)
            harness.assertRequest(claimContract)
            claimDao.upsert(claim(irId, surveyId))

            val orchestrator = orchestrator(signedInAccountManager())

            val evidenceContract = loader.load("evidence-upload-201")
            val verdictContract = loader.load("verdict-first-200")
            harness.enqueue(evidenceContract)
            harness.enqueue(verdictContract)

            val outcome = orchestrator.run(surveyId) {}

            assertEquals(SyncOutcome.Success, outcome)
            harness.assertRequest(evidenceContract)
            harness.assertRequest(verdictContract)
            assertTrue(photoDao.getUnuploaded().isEmpty())
            assertTrue(decisionDao.getUnsynced().isEmpty())
        }

    @Test
    fun `session loss preserves pending operations`() =
        runTest(testDispatcher) {
            val surveyId = 1L
            val irId = 1L
            candidateDao.upsert(candidate(irId, surveyId))
            claimDao.upsert(claim(irId, surveyId))
            photoDao.insert(pendingPhoto(irId, surveyId, tempFile()))
            decisionDao.saveDecision(decision(irId, surveyId))

            val accountManager = mock(AccountManager::class.java)
            `when`(accountManager.isSignedIn()).thenReturn(false)

            val outcome = orchestrator(accountManager).run(surveyId) {}

            assertEquals(SyncOutcome.AuthExpired, outcome)
            assertEquals(1, photoDao.getUnuploaded().size)
            assertEquals(1, decisionDao.getUnsynced().size)
        }

    @Test
    fun `auth expiry mid sync preserves remaining pending operations`() =
        runTest(testDispatcher) {
            val surveyId = 1L
            candidateDao.upsert(candidate(1L, surveyId))
            candidateDao.upsert(candidate(2L, surveyId))
            claimDao.upsert(claim(1L, surveyId))
            claimDao.upsert(claim(2L, surveyId))
            photoDao.insert(pendingPhoto(1L, surveyId, tempFile()))
            photoDao.insert(pendingPhoto(2L, surveyId, tempFile()))
            decisionDao.saveDecision(decision(1L, surveyId))

            val orchestrator = orchestrator(signedInAccountManager())

            harness.enqueue(loader.load("evidence-upload-201"))
            server.enqueue(MockResponse().setResponseCode(401))

            val outcome = orchestrator.run(surveyId) {}

            assertEquals(SyncOutcome.AuthExpired, outcome)
            val remaining = photoDao.getUnuploaded()
            assertEquals(1, remaining.size)
            assertEquals(2L, remaining.first().inferenceResultId)
            assertEquals(1, decisionDao.getUnsynced().size)
        }

    @Test
    fun `idempotent verdict retry marks synced and prevents endless retries`() =
        runTest(testDispatcher) {
            val surveyId = 1L
            val irId = 1L
            candidateDao.upsert(candidate(irId, surveyId))
            claimDao.upsert(claim(irId, surveyId))
            decisionDao.saveDecision(decision(irId, surveyId))

            val orchestrator = orchestrator(signedInAccountManager())

            val verdictContract = loader.load("verdict-idempotent-retry")
            harness.enqueue(verdictContract)

            val outcome = orchestrator.run(surveyId) {}

            assertEquals(SyncOutcome.Success, outcome)
            harness.assertRequest(verdictContract)
            assertTrue(decisionDao.getUnsynced().isEmpty())

            assertEquals(SyncOutcome.Success, orchestrator.run(surveyId) {})
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `completed sync does not reupload photos or repost verdicts`() =
        runTest(testDispatcher) {
            val surveyId = 1L
            val irId = 1L
            candidateDao.upsert(candidate(irId, surveyId))
            claimDao.upsert(claim(irId, surveyId))
            photoDao.insert(pendingPhoto(irId, surveyId, tempFile()))
            decisionDao.saveDecision(decision(irId, surveyId))

            val orchestrator = orchestrator(signedInAccountManager())

            harness.enqueue(loader.load("evidence-upload-201"))
            harness.enqueue(loader.load("verdict-first-200"))

            assertEquals(SyncOutcome.Success, orchestrator.run(surveyId) {})
            assertEquals(SyncOutcome.Success, orchestrator.run(surveyId) {})
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `claim already claimed does not create duplicate claim`() =
        runTest(testDispatcher) {
            val surveyId = 1L
            val irId = 1L
            val contract = loader.load("claim-already-claimed")
            harness.enqueue(contract)
            val response =
                stage4Service.claimCandidate(surveyId.toString(), ClaimRequestDto(listOf(irId)))
            harness.assertRequest(contract)
            assertEquals(emptyList<Long>(), response.claimed)
            assertEquals(listOf(irId), response.alreadyClaimed)
        }

    private fun signedInAccountManager(): AccountManager {
        val accountManager = mock(AccountManager::class.java)
        `when`(accountManager.isSignedIn()).thenReturn(true)
        return accountManager
    }

    private fun orchestrator(accountManager: AccountManager): SyncOrchestrator =
        SyncOrchestrator(
            accountManager = accountManager,
            syncRepository = syncRepository,
            candidateDao = candidateDao,
            claimDao = claimDao,
            pendingPhotoUploadDao = photoDao,
            localDecisionDao = decisionDao,
            syncRunDao = syncRunDao,
            ioDispatcher = testDispatcher,
        )

    private fun candidate(
        id: Long,
        surveyId: Long,
    ): CandidateEntity =
        CandidateEntity(
            inferenceResultId = id,
            surveyId = surveyId,
            imageId = id,
            imageFilename = "img-$id.png",
            imageWidth = 100,
            imageHeight = 100,
            geoCentroidLat = 0.0,
            geoCentroidLon = 0.0,
            geoAreaJson = "[]",
            boxX = 10,
            boxY = 10,
            boxW = 20,
            boxH = 20,
            confidence = 0.9f,
            sizeMw = null,
            sizeMh = null,
            isClaimedByMe = true,
            claimOwnerUsername = null,
        )

    private fun claim(
        id: Long,
        surveyId: Long,
    ): ClaimEntity =
        ClaimEntity(
            inferenceResultId = id,
            surveyId = surveyId,
            userId = 2L,
            username = "me",
            claimedAt = "2026-01-01T00:00:00Z",
            isMine = true,
            isActive = true,
        )

    private fun pendingPhoto(
        id: Long,
        surveyId: Long,
        file: File,
    ): PendingPhotoUploadEntity =
        PendingPhotoUploadEntity(
            rowId = id,
            surveyId = surveyId,
            inferenceResultId = id,
            localFilePath = file.absolutePath,
            capturedAt = "2026-01-01T00:00:00Z",
        )

    private fun decision(
        id: Long,
        surveyId: Long,
    ): LocalDecisionEntity =
        LocalDecisionEntity(
            inferenceResultId = id,
            surveyId = surveyId,
            verdict = true,
            detectionTagId = null,
            capturedAt = "2026-01-01T00:00:00Z",
            evidencePhotoRowId = null,
        )

    private fun tempFile(): File {
        val dir =
            File
                .createTempFile("e2e", "")
                .apply {
                    delete()
                    mkdir()
                }
        val file = File(dir, "evidence.jpg")
        file.writeBytes(byteArrayOf(0x2A, 0x2A))
        tempFiles.add(file)
        return file
    }
}
