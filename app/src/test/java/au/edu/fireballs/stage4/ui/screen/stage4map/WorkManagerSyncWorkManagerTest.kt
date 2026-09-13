package au.edu.fireballs.stage4.ui.screen.stage4map

import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class WorkManagerSyncWorkManagerTest {
    private val workManager: WorkManager = mock<WorkManager>()

    private fun manager(): WorkManagerSyncWorkManager {
        whenever(workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()))
            .thenReturn(mock<Operation>())
        return WorkManagerSyncWorkManager(workManager)
    }

    @Test
    fun `enqueueSync enqueues unique work with connected constraint`() {
        manager().enqueueSync()

        val requestCaptor = argumentCaptor<OneTimeWorkRequest>()
        verify(workManager).enqueueUniqueWork(
            org.mockito.kotlin.eq(WorkManagerSyncWorkManager.UNIQUE_WORK_NAME),
            org.mockito.kotlin.eq(ExistingWorkPolicy.KEEP),
            requestCaptor.capture(),
        )
        val request = requestCaptor.firstValue
        assertEquals(
            NetworkType.CONNECTED,
            request.workSpec.constraints.requiredNetworkType,
        )
    }

    @Test
    fun `second enqueue reuses keep policy so work does not stack`() {
        val syncManager = manager()

        syncManager.enqueueSync()
        syncManager.enqueueSync()

        verify(workManager, times(2)).enqueueUniqueWork(
            org.mockito.kotlin.eq(WorkManagerSyncWorkManager.UNIQUE_WORK_NAME),
            org.mockito.kotlin.eq(ExistingWorkPolicy.KEEP),
            any<OneTimeWorkRequest>(),
        )
    }
}
