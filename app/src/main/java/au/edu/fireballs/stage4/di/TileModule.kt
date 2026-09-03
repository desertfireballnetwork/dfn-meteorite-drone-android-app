package au.edu.fireballs.stage4.di

import android.content.Context
import au.edu.fireballs.stage4.data.remote.SessionAccountScopeProvider
import au.edu.fireballs.stage4.data.tiles.AccountScopeProvider
import au.edu.fireballs.stage4.data.tiles.BufferRadiusRepository
import au.edu.fireballs.stage4.data.tiles.OfflineManagerWrapper
import au.edu.fireballs.stage4.data.tiles.OfflineRegionDownloader
import au.edu.fireballs.stage4.data.tiles.OfflineRegionWrapper
import au.edu.fireballs.stage4.data.tiles.TileStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TileModule {
    private const val TILE_BASE_DIR = "tiles"
    private const val BUFFER_SETTINGS = "buffer_settings"

    @Provides
    @Singleton
    fun provideAccountScopeProvider(
        sessionAccountScopeProvider: SessionAccountScopeProvider,
    ): AccountScopeProvider = sessionAccountScopeProvider

    @Provides
    @Singleton
    fun provideTileStore(
        @ApplicationContext context: Context,
        accountScopeProvider: AccountScopeProvider,
    ): TileStore = TileStore(File(context.noBackupFilesDir, TILE_BASE_DIR), accountScopeProvider)

    @Provides
    @Singleton
    fun provideBufferRadiusRepository(
        @ApplicationContext context: Context,
    ): BufferRadiusRepository =
        BufferRadiusRepository(
            context.getSharedPreferences(BUFFER_SETTINGS, Context.MODE_PRIVATE),
        )

    @Provides
    @Singleton
    fun provideOfflineRegionWrapper(): OfflineRegionWrapper = OfflineRegionWrapper()

    @Provides
    @Singleton
    fun provideOfflineRegionDownloader(
        offlineRegionWrapper: OfflineRegionWrapper,
    ): OfflineRegionDownloader = offlineRegionWrapper

    @Provides
    @Singleton
    fun provideOfflineManagerWrapper(
        offlineRegionDownloader: OfflineRegionDownloader,
    ): OfflineManagerWrapper = OfflineManagerWrapper(offlineRegionDownloader)
}
