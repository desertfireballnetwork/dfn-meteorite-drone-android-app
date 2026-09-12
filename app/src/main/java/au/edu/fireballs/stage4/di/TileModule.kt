package au.edu.fireballs.stage4.di

import android.content.Context
import android.net.ConnectivityManager
import au.edu.fireballs.stage4.data.tiles.AuthenticatedTileHttpInterceptor
import au.edu.fireballs.stage4.data.tiles.BufferRadiusRepository
import au.edu.fireballs.stage4.data.tiles.OfflineManagerWrapper
import au.edu.fireballs.stage4.data.tiles.OfflineRegionWrapper
import au.edu.fireballs.stage4.data.tiles.TileStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TileModule {
    private const val TILE_BASE_DIR = "tiles"
    private const val BUFFER_SETTINGS = "buffer_settings"

    @Provides
    @Singleton
    fun provideTileStore(
        @ApplicationContext context: Context,
    ): TileStore = TileStore(File(context.noBackupFilesDir, TILE_BASE_DIR))

    @Provides
    @Singleton
    fun provideAuthenticatedTileHttpInterceptor(
        tileStore: TileStore,
        okHttpClient: OkHttpClient,
        @Named("serverUrl") serverUrl: String,
        @ApplicationContext context: Context,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): AuthenticatedTileHttpInterceptor {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return AuthenticatedTileHttpInterceptor(
            tileStore,
            okHttpClient,
            serverUrl,
            connectivityManager,
            ioDispatcher,
        )
    }

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
    fun provideOfflineManagerWrapper(
        offlineRegionWrapper: OfflineRegionWrapper,
    ): OfflineManagerWrapper = OfflineManagerWrapper(offlineRegionWrapper)
}
