package au.edu.fireballs.stage4.ui.screen.basecamp

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject

interface PreDownloadWorkManager {
    fun enqueueUniqueWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ): Operation

    fun getWorkInfoByIdFlow(id: UUID): Flow<WorkInfo?>

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

        override fun cancelUniqueWork(uniqueWorkName: String): Operation =
            workManager.cancelUniqueWork(uniqueWorkName)
    }
