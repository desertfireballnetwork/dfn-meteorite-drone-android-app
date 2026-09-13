package au.edu.fireballs.stage4.sync

import androidx.work.Data
import androidx.work.workDataOf
import au.edu.fireballs.stage4.data.local.dao.CandidateDao
import au.edu.fireballs.stage4.data.local.dao.ClaimDao
import au.edu.fireballs.stage4.data.local.dao.LocalDecisionDao
import au.edu.fireballs.stage4.data.local.dao.PendingPhotoUploadDao
import au.edu.fireballs.stage4.data.remote.AccountManager
import au.edu.fireballs.stage4.data.repository.PhotoUploadResult
import au.edu.fireballs.stage4.data.repository.SyncRepository
import au.edu.fireballs.stage4.data.repository.VerdictPostResult
import au.edu.fireballs.stage4.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface SyncOutcome {
    data object Success : SyncOutcome

    data object AuthExpired : SyncOutcome

    data class Failure(
        val message: String,
    ) : SyncOutcome
}

class SyncOrchestrator
    @Inject
    constructor(
        private val accountManager: AccountManager,
        private val syncRepository: SyncRepository,
        private val candidateDao: CandidateDao,
        private val claimDao: ClaimDao,
        private val pendingPhotoUploadDao: PendingPhotoUploadDao,
        private val localDecisionDao: LocalDecisionDao,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        suspend fun run(
            surveyId: Long,
            progress: suspend (Data) -> Unit,
        ): SyncOutcome =
            withContext(ioDispatcher) {
                if (!accountManager.isSignedIn()) {
                    SyncOutcome.AuthExpired
                } else {
                    val eligible = buildEligibleIds(surveyId)
                    val photoOutcome = uploadPendingPhotos(surveyId, eligible, progress)
                    if (photoOutcome != null) {
                        photoOutcome
                    } else {
                        val verdictOutcome = postPendingVerdicts(surveyId, eligible, progress)
                        if (verdictOutcome != null) {
                            verdictOutcome
                        } else {
                            SyncOutcome.Success
                        }
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
                    .filter { it.surveyId == surveyId && it.inferenceResultId in eligible }
            var done = 0
            var failed = false
            for (photo in pending) {
                val result = syncRepository.uploadPhoto(photo)
                done++
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
                    else -> failed = true
                }
            }
            return if (failed) SyncOutcome.Failure("Photo upload failed") else null
        }

        private suspend fun postPendingVerdicts(
            surveyId: Long,
            eligible: Set<Long>,
            progress: suspend (Data) -> Unit,
        ): SyncOutcome? {
            val decisions =
                localDecisionDao
                    .getUnsynced()
                    .filter { it.surveyId == surveyId && it.inferenceResultId in eligible }
            var done = 0
            var failed = false
            for (decision in decisions) {
                val result = syncRepository.postVerdict(decision)
                done++
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
                    -> Unit
                    else -> failed = true
                }
            }
            return if (failed) SyncOutcome.Failure("Verdict post failed") else null
        }

        companion object {
            const val KEY_DONE = "done"
            const val KEY_TOTAL = "total"
            const val KEY_PHASE = "phase"
            const val PHASE_PHOTOS = "photos"
            const val PHASE_VERDICTS = "verdicts"
        }
    }
