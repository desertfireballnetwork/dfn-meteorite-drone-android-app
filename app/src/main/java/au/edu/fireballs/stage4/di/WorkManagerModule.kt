package au.edu.fireballs.stage4.di

import android.content.Context
import androidx.work.WorkManager
import au.edu.fireballs.stage4.ui.screen.basecamp.PreDownloadWorkManager
import au.edu.fireballs.stage4.ui.screen.basecamp.WorkManagerPreDownloadWorkManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class WorkManagerModule {
    @Binds
    @Singleton
    abstract fun bindPreDownloadWorkManager(
        impl: WorkManagerPreDownloadWorkManager,
    ): PreDownloadWorkManager

    companion object {
        @Provides
        @Singleton
        fun provideWorkManager(
            @ApplicationContext context: Context,
        ): WorkManager = WorkManager.getInstance(context)
    }
}
