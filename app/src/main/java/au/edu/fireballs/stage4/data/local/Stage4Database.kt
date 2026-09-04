package au.edu.fireballs.stage4.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import au.edu.fireballs.stage4.data.local.dao.CandidateDao
import au.edu.fireballs.stage4.data.local.dao.ClaimDao
import au.edu.fireballs.stage4.data.local.dao.LocalDecisionDao
import au.edu.fireballs.stage4.data.local.dao.OfflineBundleDao
import au.edu.fireballs.stage4.data.local.dao.PendingPhotoUploadDao
import au.edu.fireballs.stage4.data.local.dao.SurveyDao
import au.edu.fireballs.stage4.data.local.dao.TileManifestDao

@Database(
    entities = [
        SurveyEntity::class,
        CandidateEntity::class,
        ClaimEntity::class,
        LocalDecisionEntity::class,
        PendingPhotoUploadEntity::class,
        OfflineBundleEntity::class,
        TileManifestEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class Stage4Database : RoomDatabase() {
    abstract fun surveyDao(): SurveyDao

    abstract fun candidateDao(): CandidateDao

    abstract fun claimDao(): ClaimDao

    abstract fun localDecisionDao(): LocalDecisionDao

    abstract fun pendingPhotoUploadDao(): PendingPhotoUploadDao

    abstract fun offlineBundleDao(): OfflineBundleDao

    abstract fun tileManifestDao(): TileManifestDao

    companion object {
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE candidate ADD COLUMN isClaimedByOther INTEGER NOT NULL DEFAULT 0",
                    )
                    db.execSQL(
                        "ALTER TABLE candidate ADD COLUMN serverVerdict INTEGER NOT NULL DEFAULT 0",
                    )
                }
            }
    }
}
