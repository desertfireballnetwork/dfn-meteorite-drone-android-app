package au.edu.fireballs.stage4.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import au.edu.fireballs.stage4.data.repository.SelectedSurveyRepository
import au.edu.fireballs.stage4.ui.session.SessionExpiredBus
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

@HiltWorker
class SyncWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val selectedSurveyRepository: SelectedSurveyRepository,
        private val orchestrator: SyncOrchestrator,
        private val sessionExpiredBus: SessionExpiredBus,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            val surveyId = selectedSurveyRepository.selectedSurveyId.first()
            if (surveyId == null) {
                return Result.success()
            }
            val outcome = orchestrator.run(surveyId) { setProgress(it) }
            return when (outcome) {
                is SyncOutcome.Success -> Result.success()
                is SyncOutcome.AuthExpired -> {
                    sessionExpiredBus.emit()
                    Result.success(workDataOf(KEY_AUTH_EXPIRED to true))
                }
                is SyncOutcome.RetryableFailure -> Result.retry()
                is SyncOutcome.Failure -> Result.failure()
            }
        }

        companion object {
            const val KEY_AUTH_EXPIRED = "authExpired"
        }
    }
