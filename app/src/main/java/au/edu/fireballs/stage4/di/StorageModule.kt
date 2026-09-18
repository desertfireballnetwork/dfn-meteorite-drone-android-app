package au.edu.fireballs.stage4.di

import android.content.Context
import au.edu.fireballs.stage4.data.local.Stage4Database
import au.edu.fireballs.stage4.data.local.dao.CandidateCropManifestDao
import au.edu.fireballs.stage4.data.local.dao.OfflineBundleDao
import au.edu.fireballs.stage4.data.local.dao.SatelliteRegionDao
import au.edu.fireballs.stage4.data.local.dao.TileManifestDao
import au.edu.fireballs.stage4.data.repository.CandidateImageRepository
import au.edu.fireballs.stage4.data.repository.OfflineWorkingSetRepository
import au.edu.fireballs.stage4.data.repository.StorageCoordinator
import au.edu.fireballs.stage4.data.tiles.OfflineRegionWrapper
import au.edu.fireballs.stage4.data.tiles.TileStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

@Retention(AnnotationRetention.BINARY)
@Qualifier
annotation class AppVolumeProbeRoot

@Retention(AnnotationRetention.BINARY)
@Qualifier
annotation class CandidateCropRoot

@Retention(AnnotationRetention.BINARY)
@Qualifier
annotation class EvidenceRoot

@Retention(AnnotationRetention.BINARY)
@Qualifier
annotation class EvidenceCaptureCacheRoot

@Retention(AnnotationRetention.BINARY)
@Qualifier
annotation class OwnedTempCacheRoots

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides
    @AppVolumeProbeRoot
    fun provideAppVolumeProbeRoot(
        @ApplicationContext context: Context,
    ): File = context.filesDir

    @Provides
    @CandidateCropRoot
    fun provideCandidateCropRoot(
        @ApplicationContext context: Context,
    ): File = File(context.filesDir, "crops")

    @Provides
    @EvidenceRoot
    fun provideEvidenceRoot(
        @ApplicationContext context: Context,
    ): File = File(context.filesDir, "evidence")

    @Provides
    @EvidenceCaptureCacheRoot
    fun provideEvidenceCaptureCacheRoot(
        @ApplicationContext context: Context,
    ): File = File(context.cacheDir, "evidence")

    @Provides
    @OwnedTempCacheRoots
    fun provideOwnedTempCacheRoots(
        @EvidenceCaptureCacheRoot evidenceCaptureCacheRoot: File,
    ): List<File> = listOf(evidenceCaptureCacheRoot)

    @Provides
    @Singleton
    fun provideStorageCoordinator(
        tileStore: TileStore,
        offlineRegionWrapper: OfflineRegionWrapper,
        @AppVolumeProbeRoot appVolumeProbeRoot: File,
        @CandidateCropRoot candidateCropRoot: File,
        @EvidenceRoot evidenceRoot: File,
        @OwnedTempCacheRoots ownedTempCacheRoots: List<@JvmSuppressWildcards File>,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): StorageCoordinator =
        StorageCoordinator(
            tileStore = tileStore,
            offlineRegionWrapper = offlineRegionWrapper,
            appStorageRoot = appVolumeProbeRoot,
            candidateCropRoot = candidateCropRoot,
            evidenceRoot = evidenceRoot,
            ownedTempCacheRoots = ownedTempCacheRoots,
            ioDispatcher = ioDispatcher,
        )

    @Provides
    fun provideCandidateCropManifestDao(database: Stage4Database): CandidateCropManifestDao =
        database.candidateCropManifestDao()

    @Provides
    @Singleton
    fun provideOfflineWorkingSetRepository(
        database: Stage4Database,
        offlineBundleDao: OfflineBundleDao,
        tileManifestDao: TileManifestDao,
        candidateCropManifestDao: CandidateCropManifestDao,
        satelliteRegionDao: SatelliteRegionDao,
        tileStore: TileStore,
        candidateImageRepository: CandidateImageRepository,
        offlineRegionWrapper: OfflineRegionWrapper,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): OfflineWorkingSetRepository =
        OfflineWorkingSetRepository(
            database = database,
            offlineBundleDao = offlineBundleDao,
            tileManifestDao = tileManifestDao,
            candidateCropManifestDao = candidateCropManifestDao,
            satelliteRegionDao = satelliteRegionDao,
            tileStore = tileStore,
            candidateImageRepository = candidateImageRepository,
            offlineRegionWrapper = offlineRegionWrapper,
            ioDispatcher = ioDispatcher,
        )
}
