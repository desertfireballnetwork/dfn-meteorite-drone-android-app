package au.edu.fireballs.stage4.di

import android.content.Context
import androidx.room.Room
import au.edu.fireballs.stage4.data.local.Stage4Database
import au.edu.fireballs.stage4.data.local.dao.CandidateDao
import au.edu.fireballs.stage4.data.local.dao.ClaimDao
import au.edu.fireballs.stage4.data.local.dao.LocalDecisionDao
import au.edu.fireballs.stage4.data.local.dao.OfflineBundleDao
import au.edu.fireballs.stage4.data.local.dao.PendingPhotoUploadDao
import au.edu.fireballs.stage4.data.local.dao.SatelliteRegionDao
import au.edu.fireballs.stage4.data.local.dao.SurveyDao
import au.edu.fireballs.stage4.data.local.dao.SyncRunDao
import au.edu.fireballs.stage4.data.local.dao.TileManifestDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideStage4Database(
        @ApplicationContext context: Context,
    ): Stage4Database {
        val builder =
            Room.databaseBuilder(
                context,
                Stage4Database::class.java,
                "stage4.db",
            )
        Stage4Database.ALL_MIGRATIONS.forEach { migration ->
            builder.addMigrations(migration)
        }
        return builder.build()
    }

    @Provides
    fun provideSurveyDao(db: Stage4Database): SurveyDao = db.surveyDao()

    @Provides
    fun provideCandidateDao(db: Stage4Database): CandidateDao = db.candidateDao()

    @Provides
    fun provideClaimDao(db: Stage4Database): ClaimDao = db.claimDao()

    @Provides
    fun provideLocalDecisionDao(db: Stage4Database): LocalDecisionDao = db.localDecisionDao()

    @Provides
    fun providePendingPhotoUploadDao(db: Stage4Database): PendingPhotoUploadDao =
        db.pendingPhotoUploadDao()

    @Provides
    fun provideOfflineBundleDao(db: Stage4Database): OfflineBundleDao = db.offlineBundleDao()

    @Provides
    fun provideTileManifestDao(db: Stage4Database): TileManifestDao = db.tileManifestDao()

    @Provides
    fun provideSatelliteRegionDao(db: Stage4Database): SatelliteRegionDao = db.satelliteRegionDao()

    @Provides
    fun provideSyncRunDao(db: Stage4Database): SyncRunDao = db.syncRunDao()
}
