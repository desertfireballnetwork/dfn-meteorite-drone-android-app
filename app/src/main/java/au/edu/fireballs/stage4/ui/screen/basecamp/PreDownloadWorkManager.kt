package au.edu.fireballs.stage4.ui.screen.basecamp

import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import au.edu.fireballs.stage4.sync.PreDownloadWorker
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject

interface PreDownloadWorkManager {
    companion object {
        const val UNIQUE_WORK_NAME = "pre_download"

        fun inputData(
            surveyId: Long,
            bufferMeters: Float,
            geotiffRadiusMeters: Float,
        ): Data =
            Data
                .Builder()
                .putLong(PreDownloadWorker.KEY_SURVEY_ID, surveyId)
                .putFloat(PreDownloadWorker.KEY_BUFFER_METERS, bufferMeters)
                .putFloat(
                    PreDownloadWorker.KEY_GEOTIFF_RADIUS_METERS,
                    geotiffRadiusMeters,
                ).build()
    }

    fun enqueueUniqueWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ): Operation

    fun getWorkInfoByIdFlow(id: UUID): Flow<WorkInfo?>

    fun getWorkInfosForUniqueWorkFlow(uniqueWorkName: String): Flow<List<WorkInfo>>

    fun cancelUniqueWork(uniqueWorkName: String): Operation
}

class WorkManagerPreDownloadWorkManager
    @Inject
    constructor(
        private val workManager: WorkManager,
    ) : PreDownloadWorkManager {
        override fun enqueueUniqueWork(
            uniqueWorkName: String,
            existingWorkPolicy: ExistingWorkPolicy,
            request: OneTimeWorkRequest,
        ): Operation = workManager.enqueueUniqueWork(uniqueWorkName, existingWorkPolicy, request)

        override fun getWorkInfoByIdFlow(id: UUID): Flow<WorkInfo?> =
            workManager.getWorkInfoByIdFlow(id)

        override fun getWorkInfosForUniqueWorkFlow(uniqueWorkName: String): Flow<List<WorkInfo>> =
            workManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName)

        override fun cancelUniqueWork(uniqueWorkName: String): Operation =
            workManager.cancelUniqueWork(uniqueWorkName)
    }
