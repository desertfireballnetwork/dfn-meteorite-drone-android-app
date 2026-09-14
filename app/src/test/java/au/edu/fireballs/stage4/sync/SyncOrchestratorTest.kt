package au.edu.fireballs.stage4.sync

import au.edu.fireballs.stage4.data.local.CandidateEntity
import au.edu.fireballs.stage4.data.local.ClaimEntity
import au.edu.fireballs.stage4.data.local.LocalDecisionEntity
import au.edu.fireballs.stage4.data.local.PendingPhotoUploadEntity
import au.edu.fireballs.stage4.data.local.SyncRunEntity
import au.edu.fireballs.stage4.data.local.dao.CandidateDao
import au.edu.fireballs.stage4.data.local.dao.ClaimDao
import au.edu.fireballs.stage4.data.local.dao.LocalDecisionDao
import au.edu.fireballs.stage4.data.local.dao.PendingPhotoUploadDao
import au.edu.fireballs.stage4.data.local.dao.SyncRunDao
import au.edu.fireballs.stage4.data.remote.AccountManager
import au.edu.fireballs.stage4.data.repository.PhotoUploadResult
import au.edu.fireballs.stage4.data.repository.SyncRepository
import au.edu.fireballs.stage4.data.repository.VerdictPostResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any

@OptIn(ExperimentalCoroutinesApi::class)
class SyncOrchestratorTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    private fun candidate(id: Long): CandidateEntity =
        CandidateEntity(
            inferenceResultId = id,
            surveyId = SURVEY_ID,
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
        isMine: Boolean = true,
        isActive: Boolean = true,
    ): ClaimEntity =
        ClaimEntity(
            inferenceResultId = id,
            surveyId = SURVEY_ID,
            userId = 2L,
            username = "me",
            claimedAt = "2026-01-01T00:00:00Z",
            isMine = isMine,
            isActive = isActive,
        )

    private fun pendingPhoto(
        id: Long,
        surveyId: Long = SURVEY_ID,
    ): PendingPhotoUploadEntity =
        PendingPhotoUploadEntity(
            rowId = id,
            surveyId = surveyId,
            inferenceResultId = id,
            localFilePath = "/tmp/$id.jpg",
            capturedAt = "2026-01-01T00:00:00Z",
        )

    private fun decision(
        id: Long,
        surveyId: Long = SURVEY_ID,
    ): LocalDecisionEntity =
        LocalDecisionEntity(
            inferenceResultId = id,
            surveyId = surveyId,
            verdict = true,
            detectionTagId = null,
            capturedAt = "2026-01-01T00:00:00Z",
            evidencePhotoRowId = null,
        )

    private fun orchestrator(
        accountManager: AccountManager,
        syncRepository: SyncRepository,
        candidateDao: CandidateDao,
        claimDao: ClaimDao,
        photoDao: PendingPhotoUploadDao,
        decisionDao: LocalDecisionDao,
        syncRunDao: SyncRunDao = FakeSyncRunDao(),
    ): SyncOrchestrator =
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

    @Test
    fun `success uploads photos and posts verdicts for eligible candidates only`() =
        runTest(testDispatcher) {
            val accountManager = mock(AccountManager::class.java)
            `when`(accountManager.isSignedIn()).thenReturn(true)
            val syncRepository = mock(SyncRepository::class.java)
            `when`(syncRepository.uploadPhoto(any(), any())).thenReturn(PhotoUploadResult.Success)
            `when`(syncRepository.postVerdict(any(), any())).thenReturn(VerdictPostResult.Success)

            val candidateDao = FakeCandidateDao(listOf(candidate(1L), candidate(2L)))
            val claimDao = FakeClaimDao(listOf(claim(1L)))
            val photoDao = FakePendingPhotoUploadDao(listOf(pendingPhoto(1L), pendingPhoto(2L)))
            val decisionDao = FakeLocalDecisionDao(listOf(decision(1L), decision(2L)))

            val orchestrator =
                orchestrator(
                    accountManager,
                    syncRepository,
                    candidateDao,
                    claimDao,
                    photoDao,
                    decisionDao,
                )

            val outcome = orchestrator.run(SURVEY_ID) {}

            assertEquals(SyncOutcome.Success, outcome)
            verify(syncRepository).uploadPhoto(pendingPhoto(1L), SURVEY_ID)
            verify(syncRepository, never()).uploadPhoto(pendingPhoto(2L), SURVEY_ID)
            verify(syncRepository).postVerdict(decision(1L), SURVEY_ID)
            verify(syncRepository, never()).postVerdict(decision(2L), SURVEY_ID)
        }

    @Test
    fun `auth expired returns AuthExpired and preserves all pending rows`() =
        runTest(testDispatcher) {
            val accountManager = mock(AccountManager::class.java)
            `when`(accountManager.isSignedIn()).thenReturn(false)
            val syncRepository = mock(SyncRepository::class.java)

            val candidateDao = FakeCandidateDao(listOf(candidate(1L)))
            val claimDao = FakeClaimDao(listOf(claim(1L)))
            val photoDao = FakePendingPhotoUploadDao(listOf(pendingPhoto(1L)))
            val decisionDao = FakeLocalDecisionDao(listOf(decision(1L)))

            val orchestrator =
                orchestrator(
                    accountManager,
                    syncRepository,
                    candidateDao,
                    claimDao,
                    photoDao,
                    decisionDao,
                )

            val outcome = orchestrator.run(SURVEY_ID) {}

            assertEquals(SyncOutcome.AuthExpired, outcome)
            verifyNoInteractions(syncRepository)
            assertEquals(1, photoDao.unuploaded.size)
            assertEquals(1, decisionDao.unsynced.size)
        }

    @Test
    fun `ineligible candidates are skipped`() =
        runTest(testDispatcher) {
            val accountManager = mock(AccountManager::class.java)
            `when`(accountManager.isSignedIn()).thenReturn(true)
            val syncRepository = mock(SyncRepository::class.java)
            `when`(syncRepository.uploadPhoto(any(), any())).thenReturn(PhotoUploadResult.Success)
            `when`(syncRepository.postVerdict(any(), any())).thenReturn(VerdictPostResult.Success)

            val candidateDao =
                FakeCandidateDao(
                    listOf(candidate(1L), candidate(2L), candidate(3L)),
                )
            val claimDao =
                FakeClaimDao(
                    listOf(
                        claim(1L),
                        claim(2L, isActive = false),
                        claim(3L, isMine = false),
                    ),
                )
            val photoDao =
                FakePendingPhotoUploadDao(
                    listOf(pendingPhoto(1L), pendingPhoto(2L), pendingPhoto(3L)),
                )
            val decisionDao =
                FakeLocalDecisionDao(
                    listOf(decision(1L), decision(2L), decision(3L)),
                )

            val orchestrator =
                orchestrator(
                    accountManager,
                    syncRepository,
                    candidateDao,
                    claimDao,
                    photoDao,
                    decisionDao,
                )

            val outcome = orchestrator.run(SURVEY_ID) {}

            assertEquals(SyncOutcome.Success, outcome)
            verify(syncRepository).uploadPhoto(pendingPhoto(1L), SURVEY_ID)
            verify(syncRepository, never()).uploadPhoto(pendingPhoto(2L), SURVEY_ID)
            verify(syncRepository, never()).uploadPhoto(pendingPhoto(3L), SURVEY_ID)
            verify(syncRepository).postVerdict(decision(1L), SURVEY_ID)
            verify(syncRepository, never()).postVerdict(decision(2L), SURVEY_ID)
            verify(syncRepository, never()).postVerdict(decision(3L), SURVEY_ID)
        }

    @Test
    fun `pending rows from other surveys are skipped`() =
        runTest(testDispatcher) {
            val accountManager = mock(AccountManager::class.java)
            `when`(accountManager.isSignedIn()).thenReturn(true)
            val syncRepository = mock(SyncRepository::class.java)
            `when`(syncRepository.uploadPhoto(any(), any())).thenReturn(PhotoUploadResult.Success)
            `when`(syncRepository.postVerdict(any(), any())).thenReturn(VerdictPostResult.Success)

            val candidateDao = FakeCandidateDao(listOf(candidate(1L)))
            val claimDao = FakeClaimDao(listOf(claim(1L)))
            val photoDao =
                FakePendingPhotoUploadDao(
                    listOf(pendingPhoto(1L), pendingPhoto(2L, surveyId = OTHER_SURVEY_ID)),
                )
            val decisionDao =
                FakeLocalDecisionDao(
                    listOf(decision(1L), decision(2L, surveyId = OTHER_SURVEY_ID)),
                )

            val orchestrator =
                orchestrator(
                    accountManager,
                    syncRepository,
                    candidateDao,
                    claimDao,
                    photoDao,
                    decisionDao,
                )

            val outcome = orchestrator.run(SURVEY_ID) {}

            assertEquals(SyncOutcome.Success, outcome)
            verify(syncRepository).uploadPhoto(pendingPhoto(1L), SURVEY_ID)
            verify(
                syncRepository,
                never(),
            ).uploadPhoto(pendingPhoto(2L, surveyId = OTHER_SURVEY_ID), SURVEY_ID)
            verify(syncRepository).postVerdict(decision(1L), SURVEY_ID)
            verify(syncRepository, never())
                .postVerdict(decision(2L, surveyId = OTHER_SURVEY_ID), SURVEY_ID)
        }

    @Test
    fun `pending row with different survey but eligible candidate is synced`() =
        runTest(testDispatcher) {
            val accountManager = mock(AccountManager::class.java)
            `when`(accountManager.isSignedIn()).thenReturn(true)
            val syncRepository = mock(SyncRepository::class.java)
            `when`(syncRepository.uploadPhoto(any(), any())).thenReturn(PhotoUploadResult.Success)
            `when`(syncRepository.postVerdict(any(), any())).thenReturn(VerdictPostResult.Success)

            val candidateDao = FakeCandidateDao(listOf(candidate(1L)))
            val claimDao = FakeClaimDao(listOf(claim(1L)))
            val photoDao =
                FakePendingPhotoUploadDao(
                    listOf(pendingPhoto(1L, surveyId = OTHER_SURVEY_ID)),
                )
            val decisionDao =
                FakeLocalDecisionDao(
                    listOf(decision(1L, surveyId = OTHER_SURVEY_ID)),
                )

            val orchestrator =
                orchestrator(
                    accountManager,
                    syncRepository,
                    candidateDao,
                    claimDao,
                    photoDao,
                    decisionDao,
                )

            val outcome = orchestrator.run(SURVEY_ID) {}

            assertEquals(SyncOutcome.Success, outcome)
            verify(syncRepository).uploadPhoto(
                pendingPhoto(1L, surveyId = OTHER_SURVEY_ID),
                SURVEY_ID,
            )
            verify(syncRepository).postVerdict(
                decision(1L, surveyId = OTHER_SURVEY_ID),
                SURVEY_ID,
            )
        }

    @Test
    fun `network error during photo pass returns retryable failure`() =
        runTest(testDispatcher) {
            val accountManager = mock(AccountManager::class.java)
            `when`(accountManager.isSignedIn()).thenReturn(true)
            val syncRepository = mock(SyncRepository::class.java)
            `when`(syncRepository.uploadPhoto(any(), any()))
                .thenReturn(PhotoUploadResult.NetworkError)

            val candidateDao = FakeCandidateDao(listOf(candidate(1L)))
            val claimDao = FakeClaimDao(listOf(claim(1L)))
            val photoDao = FakePendingPhotoUploadDao(listOf(pendingPhoto(1L)))
            val decisionDao = FakeLocalDecisionDao(listOf(decision(1L)))

            val orchestrator =
                orchestrator(
                    accountManager,
                    syncRepository,
                    candidateDao,
                    claimDao,
                    photoDao,
                    decisionDao,
                )

            val outcome = orchestrator.run(SURVEY_ID) {}

            assertEquals(SyncOutcome.RetryableFailure, outcome)
        }

    @Test
    fun `auth expired during photo pass short circuits and preserves remaining rows`() =
        runTest(testDispatcher) {
            val accountManager = mock(AccountManager::class.java)
            `when`(accountManager.isSignedIn()).thenReturn(true)
            val syncRepository = mock(SyncRepository::class.java)
            `when`(syncRepository.uploadPhoto(pendingPhoto(1L), SURVEY_ID))
                .thenReturn(PhotoUploadResult.Success)
            `when`(syncRepository.uploadPhoto(pendingPhoto(2L), SURVEY_ID))
                .thenReturn(PhotoUploadResult.AuthExpired)

            val candidateDao = FakeCandidateDao(listOf(candidate(1L), candidate(2L)))
            val claimDao = FakeClaimDao(listOf(claim(1L), claim(2L)))
            val photoDao =
                FakePendingPhotoUploadDao(
                    listOf(pendingPhoto(1L), pendingPhoto(2L), pendingPhoto(3L)),
                )
            val decisionDao = FakeLocalDecisionDao(listOf(decision(1L)))

            val orchestrator =
                orchestrator(
                    accountManager,
                    syncRepository,
                    candidateDao,
                    claimDao,
                    photoDao,
                    decisionDao,
                )

            val outcome = orchestrator.run(SURVEY_ID) {}

            assertEquals(SyncOutcome.AuthExpired, outcome)
            verify(syncRepository).uploadPhoto(pendingPhoto(1L), SURVEY_ID)
            verify(syncRepository).uploadPhoto(pendingPhoto(2L), SURVEY_ID)
            verify(syncRepository, never()).uploadPhoto(pendingPhoto(3L), SURVEY_ID)
            verify(syncRepository, never()).postVerdict(any(), any())
        }

    private class FakeSyncRunDao : SyncRunDao {
        val upserts = mutableListOf<SyncRunEntity>()
        val cleared = mutableListOf<Long>()

        override suspend fun upsert(run: SyncRunEntity) {
            upserts.add(run)
        }

        override fun observeRun(surveyId: Long): Flow<SyncRunEntity?> =
            flowOf(upserts.lastOrNull { it.surveyId == surveyId })

        override suspend fun clear(surveyId: Long) {
            cleared.add(surveyId)
        }
    }

    @Test
    fun `persists phase total and done during run and clears on success`() =
        runTest(testDispatcher) {
            val accountManager = mock(AccountManager::class.java)
            `when`(accountManager.isSignedIn()).thenReturn(true)
            val syncRepository = mock(SyncRepository::class.java)
            `when`(syncRepository.uploadPhoto(any(), any())).thenReturn(PhotoUploadResult.Success)
            `when`(syncRepository.postVerdict(any(), any())).thenReturn(VerdictPostResult.Success)

            val candidateDao = FakeCandidateDao(listOf(candidate(1L), candidate(2L)))
            val claimDao = FakeClaimDao(listOf(claim(1L), claim(2L)))
            val photoDao =
                FakePendingPhotoUploadDao(listOf(pendingPhoto(1L), pendingPhoto(2L)))
            val decisionDao = FakeLocalDecisionDao(listOf(decision(1L)))
            val syncRunDao = FakeSyncRunDao()

            val orchestrator =
                orchestrator(
                    accountManager,
                    syncRepository,
                    candidateDao,
                    claimDao,
                    photoDao,
                    decisionDao,
                    syncRunDao,
                )

            val outcome = orchestrator.run(SURVEY_ID) {}

            assertEquals(SyncOutcome.Success, outcome)
            assertEquals(
                listOf(
                    SyncRunEntity(SURVEY_ID, SyncOrchestrator.PHASE_PHOTOS, 2, 0),
                    SyncRunEntity(SURVEY_ID, SyncOrchestrator.PHASE_PHOTOS, 2, 1),
                    SyncRunEntity(SURVEY_ID, SyncOrchestrator.PHASE_PHOTOS, 2, 2),
                    SyncRunEntity(SURVEY_ID, SyncOrchestrator.PHASE_VERDICTS, 1, 0),
                    SyncRunEntity(SURVEY_ID, SyncOrchestrator.PHASE_VERDICTS, 1, 1),
                ),
                syncRunDao.upserts,
            )
            assertEquals(listOf(SURVEY_ID), syncRunDao.cleared)
        }

    @Test
    fun `clears persisted run when auth expired before any work`() =
        runTest(testDispatcher) {
            val accountManager = mock(AccountManager::class.java)
            `when`(accountManager.isSignedIn()).thenReturn(false)
            val syncRepository = mock(SyncRepository::class.java)

            val candidateDao = FakeCandidateDao(listOf(candidate(1L)))
            val claimDao = FakeClaimDao(listOf(claim(1L)))
            val photoDao = FakePendingPhotoUploadDao(listOf(pendingPhoto(1L)))
            val decisionDao = FakeLocalDecisionDao(listOf(decision(1L)))
            val syncRunDao = FakeSyncRunDao()

            val orchestrator =
                orchestrator(
                    accountManager,
                    syncRepository,
                    candidateDao,
                    claimDao,
                    photoDao,
                    decisionDao,
                    syncRunDao,
                )

            val outcome = orchestrator.run(SURVEY_ID) {}

            assertEquals(SyncOutcome.AuthExpired, outcome)
            assertEquals(listOf(SURVEY_ID), syncRunDao.cleared)
        }

    @Test
    fun `clears persisted run when auth expired during photo pass`() =
        runTest(testDispatcher) {
            val accountManager = mock(AccountManager::class.java)
            `when`(accountManager.isSignedIn()).thenReturn(true)
            val syncRepository = mock(SyncRepository::class.java)
            `when`(syncRepository.uploadPhoto(pendingPhoto(1L), SURVEY_ID))
                .thenReturn(PhotoUploadResult.AuthExpired)

            val candidateDao = FakeCandidateDao(listOf(candidate(1L)))
            val claimDao = FakeClaimDao(listOf(claim(1L)))
            val photoDao = FakePendingPhotoUploadDao(listOf(pendingPhoto(1L)))
            val decisionDao = FakeLocalDecisionDao(listOf(decision(1L)))
            val syncRunDao = FakeSyncRunDao()

            val orchestrator =
                orchestrator(
                    accountManager,
                    syncRepository,
                    candidateDao,
                    claimDao,
                    photoDao,
                    decisionDao,
                    syncRunDao,
                )

            val outcome = orchestrator.run(SURVEY_ID) {}

            assertEquals(SyncOutcome.AuthExpired, outcome)
            assertEquals(listOf(SURVEY_ID), syncRunDao.cleared)
        }

    @Test
    fun `clears persisted run on terminal failure`() =
        runTest(testDispatcher) {
            val accountManager = mock(AccountManager::class.java)
            `when`(accountManager.isSignedIn()).thenReturn(true)
            val syncRepository = mock(SyncRepository::class.java)
            `when`(syncRepository.uploadPhoto(any(), any()))
                .thenReturn(PhotoUploadResult.Error("boom"))

            val candidateDao = FakeCandidateDao(listOf(candidate(1L)))
            val claimDao = FakeClaimDao(listOf(claim(1L)))
            val photoDao = FakePendingPhotoUploadDao(listOf(pendingPhoto(1L)))
            val decisionDao = FakeLocalDecisionDao(listOf(decision(1L)))
            val syncRunDao = FakeSyncRunDao()

            val orchestrator =
                orchestrator(
                    accountManager,
                    syncRepository,
                    candidateDao,
                    claimDao,
                    photoDao,
                    decisionDao,
                    syncRunDao,
                )

            val outcome = orchestrator.run(SURVEY_ID) {}

            assertEquals(SyncOutcome.Failure("Photo upload failed"), outcome)
            assertEquals(listOf(SURVEY_ID), syncRunDao.cleared)
        }

    private class FakeCandidateDao(
        initial: List<CandidateEntity> = emptyList(),
    ) : CandidateDao {
        var candidates: List<CandidateEntity> = initial

        override suspend fun upsertAll(candidates: List<CandidateEntity>) {
            this.candidates = candidates
        }

        override suspend fun upsert(candidate: CandidateEntity) = Unit

        override fun observeCandidatesForSurvey(surveyId: Long): Flow<List<CandidateEntity>> =
            flowOf(candidates.filter { it.surveyId == surveyId })

        override suspend fun getCandidatesForSurvey(surveyId: Long): List<CandidateEntity> =
            candidates.filter { it.surveyId == surveyId }

        override suspend fun getById(inferenceResultId: Long): CandidateEntity? =
            candidates.find { it.inferenceResultId == inferenceResultId }

        override suspend fun deleteForSurvey(surveyId: Long) {
            candidates = candidates.filterNot { it.surveyId == surveyId }
        }

        override suspend fun deleteAll() {
            candidates = emptyList()
        }
    }

    private class FakeClaimDao(
        initial: List<ClaimEntity> = emptyList(),
    ) : ClaimDao {
        var claims: List<ClaimEntity> = initial

        override suspend fun upsert(claim: ClaimEntity) {
            claims = claims + claim
        }

        override suspend fun upsertAll(claims: List<ClaimEntity>) {
            this.claims = this.claims + claims
        }

        override fun getClaims(
            surveyId: Long,
            onlyActive: Boolean,
        ): Flow<List<ClaimEntity>> =
            flowOf(
                claims.filter {
                    it.surveyId == surveyId && (!onlyActive || it.isActive)
                },
            )

        override suspend fun getByCandidateId(inferenceResultId: Long): ClaimEntity? =
            claims.find { it.inferenceResultId == inferenceResultId }

        override suspend fun countActiveClaimedCandidates(surveyId: Long): Int =
            claims.count { it.surveyId == surveyId && it.isActive && it.isMine }

        override suspend fun releaseClaimsForUser(
            userId: Long,
            candidateIds: List<Long>,
        ) = Unit

        override suspend fun deactivateMineClaimsForSurvey(surveyId: Long) {
            claims =
                claims.map {
                    if (it.surveyId == surveyId && it.isMine) {
                        it.copy(isActive = false)
                    } else {
                        it
                    }
                }
        }

        override suspend fun deleteForSurvey(surveyId: Long) {
            claims = claims.filterNot { it.surveyId == surveyId }
        }

        override suspend fun deleteAll() {
            claims = emptyList()
        }
    }

    private class FakePendingPhotoUploadDao(
        initial: List<PendingPhotoUploadEntity> = emptyList(),
    ) : PendingPhotoUploadDao {
        var unuploaded: List<PendingPhotoUploadEntity> = initial

        override suspend fun insert(pendingPhotoUpload: PendingPhotoUploadEntity): Long =
            pendingPhotoUpload.rowId

        override fun getNotUploadedCount(surveyId: Long): Flow<Int> =
            flowOf(unuploaded.count { !it.uploaded && it.surveyId == surveyId })

        override fun getAllNotUploadedCount(): Flow<Int> = flowOf(unuploaded.count { !it.uploaded })

        override fun getNotUploadedFailed(surveyId: Long): Flow<List<PendingPhotoUploadEntity>> =
            flowOf(
                unuploaded.filter {
                    !it.uploaded && it.uploadFailedReason != null && it.surveyId == surveyId
                },
            )

        override suspend fun deleteByRowId(rowId: Long) {
            unuploaded = unuploaded.filterNot { it.rowId == rowId }
        }

        override suspend fun getUnuploaded(): List<PendingPhotoUploadEntity> = unuploaded

        override fun getLocalPhotosForCandidate(
            inferenceResultId: Long,
        ): Flow<List<PendingPhotoUploadEntity>> =
            flowOf(unuploaded.filter { it.inferenceResultId == inferenceResultId })

        override suspend fun markUploaded(
            rowId: Long,
            serverPhotoId: Long,
        ) {
            unuploaded =
                unuploaded.map {
                    if (it.rowId == rowId) {
                        it.copy(uploaded = true, serverPhotoId = serverPhotoId)
                    } else {
                        it
                    }
                }
        }

        override suspend fun markFailed(
            rowId: Long,
            reason: String,
        ) {
            unuploaded =
                unuploaded.map {
                    if (it.rowId == rowId) {
                        it.copy(uploadFailedReason = reason)
                    } else {
                        it
                    }
                }
        }

        override suspend fun deleteAll() {
            unuploaded = emptyList()
        }
    }

    private class FakeLocalDecisionDao(
        initial: List<LocalDecisionEntity> = emptyList(),
    ) : LocalDecisionDao {
        var unsynced: List<LocalDecisionEntity> = initial

        override suspend fun saveDecision(decision: LocalDecisionEntity) {
            unsynced = unsynced + decision
        }

        override fun getUnsyncedCount(surveyId: Long): Flow<Int> =
            flowOf(unsynced.count { !it.synced && it.surveyId == surveyId })

        override fun getAllUnsyncedCount(): Flow<Int> = flowOf(unsynced.count { !it.synced })

        override fun getUnsyncedFailed(surveyId: Long): Flow<List<LocalDecisionEntity>> =
            flowOf(
                unsynced.filter {
                    !it.synced && it.syncFailedReason != null && it.surveyId == surveyId
                },
            )

        override suspend fun getUnsynced(): List<LocalDecisionEntity> = unsynced

        override fun observeDecisionsForSurvey(surveyId: Long): Flow<List<LocalDecisionEntity>> =
            flowOf(unsynced.filter { it.surveyId == surveyId })

        override fun getVerdict(inferenceResultId: Long): Flow<LocalDecisionEntity?> =
            flowOf(unsynced.find { it.inferenceResultId == inferenceResultId })

        override suspend fun deleteByInferenceResultId(inferenceResultId: Long) {
            unsynced = unsynced.filterNot { it.inferenceResultId == inferenceResultId }
        }

        override suspend fun markSynced(
            inferenceResultId: Long,
            syncedAt: String,
        ) {
            unsynced =
                unsynced.map {
                    if (it.inferenceResultId == inferenceResultId) {
                        it.copy(synced = true, syncedAt = syncedAt)
                    } else {
                        it
                    }
                }
        }

        override suspend fun markFailed(
            inferenceResultId: Long,
            reason: String,
        ) {
            unsynced =
                unsynced.map {
                    if (it.inferenceResultId == inferenceResultId) {
                        it.copy(syncFailedReason = reason)
                    } else {
                        it
                    }
                }
        }

        override suspend fun deleteAll() {
            unsynced = emptyList()
        }
    }

    private companion object {
        const val SURVEY_ID = 7L
        const val OTHER_SURVEY_ID = 99L
    }
}
