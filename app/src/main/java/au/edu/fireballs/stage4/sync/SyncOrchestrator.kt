package au.edu.fireballs.stage4.sync

import androidx.work.Data
import androidx.work.workDataOf
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
import au.edu.fireballs.stage4.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SyncOutcome {
    data object Success : SyncOutcome

    data object AuthExpired : SyncOutcome

    data object RetryableFailure : SyncOutcome

    data class Failure(
        val message: String,
    ) : SyncOutcome
}

@Singleton
class SyncOrchestrator
    @Inject
    constructor(
        private val accountManager: AccountManager,
        private val syncRepository: SyncRepository,
        private val candidateDao: CandidateDao,
        private val claimDao: ClaimDao,
        private val pendingPhotoUploadDao: PendingPhotoUploadDao,
        private val localDecisionDao: LocalDecisionDao,
        private val syncRunDao: SyncRunDao,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        private val runMutex = Mutex()

        suspend fun run(
            surveyId: Long,
            progress: suspend (Data) -> Unit,
        ): SyncOutcome =
            runMutex.withLock {
                withContext(ioDispatcher) {
                    try {
                        if (!accountManager.isSignedIn()) {
                            SyncOutcome.AuthExpired
                        } else {
                            val eligible = buildEligibleIds(surveyId)
                            val photoOutcome = uploadPendingPhotos(surveyId, eligible, progress)
                            if (photoOutcome != null) {
                                photoOutcome
                            } else {
                                val verdictOutcome =
                                    postPendingVerdicts(surveyId, eligible, progress)
                                if (verdictOutcome != null) {
                                    verdictOutcome
                                } else {
                                    SyncOutcome.Success
                                }
                            }
                        }
                    } finally {
                        syncRunDao.clear(surveyId)
                    }
                }
            }

        private suspend fun buildEligibleIds(surveyId: Long): Set<Long> {
            val candidates = candidateDao.getCandidatesForSurvey(surveyId)
            val mineActive =
                claimDao
                    .getClaims(surveyId, onlyActive = true)
                    .first()
                    .filter { it.isMine && it.isActive }
                    .map { it.inferenceResultId }
                    .toSet()
            return candidates
                .filter { it.inferenceResultId in mineActive }
                .map { it.inferenceResultId }
                .toSet()
        }

        private suspend fun uploadPendingPhotos(
            surveyId: Long,
            eligible: Set<Long>,
            progress: suspend (Data) -> Unit,
        ): SyncOutcome? {
            val pending =
                pendingPhotoUploadDao
                    .getUnuploaded()
                    .filter { it.inferenceResultId in eligible }
            syncRunDao.upsert(SyncRunEntity(surveyId, PHASE_PHOTOS, pending.size, 0))
            var done = 0
            var retryable = false
            var terminal = false
            for (photo in pending) {
                val result = syncRepository.uploadPhoto(photo, surveyId)
                done++
                syncRunDao.upsert(SyncRunEntity(surveyId, PHASE_PHOTOS, pending.size, done))
                progress(
                    workDataOf(
                        KEY_DONE to done,
                        KEY_TOTAL to pending.size,
                        KEY_PHASE to PHASE_PHOTOS,
                    ),
                )
                when (result) {
                    is PhotoUploadResult.AuthExpired -> return SyncOutcome.AuthExpired
                    is PhotoUploadResult.Success -> Unit
                    is PhotoUploadResult.NetworkError -> retryable = true
                    else -> terminal = true
                }
            }
            return when {
                retryable -> SyncOutcome.RetryableFailure
                terminal -> SyncOutcome.Failure("Photo upload failed")
                else -> null
            }
        }

        private suspend fun postPendingVerdicts(
            surveyId: Long,
            eligible: Set<Long>,
            progress: suspend (Data) -> Unit,
        ): SyncOutcome? {
            val decisions =
                localDecisionDao
                    .getUnsynced()
                    .filter { it.inferenceResultId in eligible }
            syncRunDao.upsert(SyncRunEntity(surveyId, PHASE_VERDICTS, decisions.size, 0))
            var done = 0
            var retryable = false
            var terminal = false
            for (decision in decisions) {
                val result = syncRepository.postVerdict(decision, surveyId)
                done++
                syncRunDao.upsert(SyncRunEntity(surveyId, PHASE_VERDICTS, decisions.size, done))
                progress(
                    workDataOf(
                        KEY_DONE to done,
                        KEY_TOTAL to decisions.size,
                        KEY_PHASE to PHASE_VERDICTS,
                    ),
                )
                when (result) {
                    is VerdictPostResult.AuthExpired -> return SyncOutcome.AuthExpired
                    is VerdictPostResult.Success,
                    is VerdictPostResult.AlreadyCompleted,
                    is VerdictPostResult.ClaimRequired,
                    -> Unit
                    is VerdictPostResult.NetworkError -> retryable = true
                    else -> terminal = true
                }
            }
            return when {
                retryable -> SyncOutcome.RetryableFailure
                terminal -> SyncOutcome.Failure("Verdict post failed")
                else -> null
            }
        }

        companion object {
            const val KEY_DONE = "done"
            const val KEY_TOTAL = "total"
            const val KEY_PHASE = "phase"
            const val PHASE_PHOTOS = "photos"
            const val PHASE_VERDICTS = "verdicts"
        }
    }
