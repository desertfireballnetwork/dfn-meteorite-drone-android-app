package au.edu.fireballs.stage4.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import au.edu.fireballs.stage4.data.repository.SelectedSurveyRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject

@HiltWorker
class SyncWorker
    @Inject
    constructor(
        @ApplicationContext appContext: Context,
        params: WorkerParameters,
        private val selectedSurveyRepository: SelectedSurveyRepository,
        private val orchestrator: SyncOrchestrator,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            val surveyId = selectedSurveyRepository.selectedSurveyId.first()
            if (surveyId == null) {
                return Result.success()
            }
            val outcome = orchestrator.run(surveyId) { setProgress(it) }
            return when (outcome) {
                is SyncOutcome.Success -> Result.success()
                is SyncOutcome.AuthExpired -> Result.success(workDataOf(KEY_AUTH_EXPIRED to true))
                is SyncOutcome.Failure -> Result.failure()
            }
        }

        companion object {
            const val KEY_AUTH_EXPIRED = "authExpired"
        }
    }
