package au.edu.fireballs.stage4.di

import au.edu.fireballs.stage4.data.local.dao.LocalDecisionDao
import au.edu.fireballs.stage4.data.local.dao.PendingPhotoUploadDao
import au.edu.fireballs.stage4.data.repository.PendingSyncCountSource
import au.edu.fireballs.stage4.data.repository.PendingSyncCounts
import au.edu.fireballs.stage4.data.repository.SyncStatusCoordinator
import au.edu.fireballs.stage4.data.repository.SyncStatusSource
import au.edu.fireballs.stage4.data.repository.SyncWorkInfoObserver
import au.edu.fireballs.stage4.ui.screen.stage4map.SyncWorkManager
import au.edu.fireballs.stage4.ui.screen.stage4map.WorkManagerSyncWorkManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.combine
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncStatusModule {
    @Binds
    @Singleton
    abstract fun bindSyncStatusSource(impl: SyncStatusCoordinator): SyncStatusSource

    companion object {
        @Provides
        @Singleton
        fun provideSyncWorkInfoObserver(syncWorkManager: SyncWorkManager): SyncWorkInfoObserver =
            SyncWorkInfoObserver {
                syncWorkManager.getWorkInfosForUniqueWorkFlow(
                    WorkManagerSyncWorkManager.UNIQUE_WORK_NAME,
                )
            }

        @Provides
        @Singleton
        fun providePendingSyncCountSource(
            localDecisionDao: LocalDecisionDao,
            pendingPhotoUploadDao: PendingPhotoUploadDao,
        ): PendingSyncCountSource =
            PendingSyncCountSource {
                combine(
                    localDecisionDao.getAllUnsyncedCount(),
                    pendingPhotoUploadDao.getAllNotUploadedCount(),
                ) { decisions, photos -> PendingSyncCounts(decisions = decisions, photos = photos) }
            }
    }
}
