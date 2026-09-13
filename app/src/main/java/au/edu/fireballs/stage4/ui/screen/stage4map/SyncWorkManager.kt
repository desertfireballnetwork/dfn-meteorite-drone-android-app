package au.edu.fireballs.stage4.ui.screen.stage4map

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import au.edu.fireballs.stage4.sync.SyncWorker
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject

interface SyncWorkManager {
    fun enqueueSync(): OneTimeWorkRequest

    fun getWorkInfoByIdFlow(id: UUID): Flow<WorkInfo?>

    fun cancelUniqueWork(uniqueWorkName: String): Operation
}

class WorkManagerSyncWorkManager
    @Inject
    constructor(
        private val workManager: WorkManager,
    ) : SyncWorkManager {
        override fun enqueueSync(): OneTimeWorkRequest {
            val request =
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    ).build()
            workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
            return request
        }

        override fun getWorkInfoByIdFlow(id: UUID): Flow<WorkInfo?> =
            workManager.getWorkInfoByIdFlow(id)

        override fun cancelUniqueWork(uniqueWorkName: String): Operation =
            workManager.cancelUniqueWork(uniqueWorkName)

        companion object {
            const val UNIQUE_WORK_NAME = "sync-pending"
        }
    }
