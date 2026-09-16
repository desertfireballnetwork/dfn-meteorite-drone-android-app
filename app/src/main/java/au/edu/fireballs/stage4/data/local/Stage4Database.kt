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
import au.edu.fireballs.stage4.data.local.dao.SatelliteRegionDao
import au.edu.fireballs.stage4.data.local.dao.SurveyDao
import au.edu.fireballs.stage4.data.local.dao.SyncRunDao
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
        SyncRunEntity::class,
        SatelliteRegionEntity::class,
    ],
    version = 6,
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

    abstract fun syncRunDao(): SyncRunDao

    abstract fun satelliteRegionDao(): SatelliteRegionDao

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

        val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE survey ADD COLUMN surveyedAreasJson TEXT DEFAULT NULL")
                    db.execSQL("ALTER TABLE survey ADD COLUMN detectionTagsJson TEXT DEFAULT NULL")
                    db.execSQL("ALTER TABLE survey ADD COLUMN userLocationsJson TEXT DEFAULT NULL")
                    db.execSQL(
                        "ALTER TABLE survey ADD COLUMN showGeolocationAccuracyCircle INTEGER NOT NULL DEFAULT 1",
                    )
                }
            }

        val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 1. Create temporary candidate table with NULLABLE lat/lon
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS candidate_new (
                            inferenceResultId INTEGER NOT NULL PRIMARY KEY,
                            surveyId INTEGER NOT NULL,
                            imageId INTEGER NOT NULL,
                            imageFilename TEXT NOT NULL,
                            imageWidth INTEGER NOT NULL,
                            imageHeight INTEGER NOT NULL,
                            geoCentroidLat REAL,
                            geoCentroidLon REAL,
                            geoAreaJson TEXT NOT NULL,
                            boxX INTEGER NOT NULL,
                            boxY INTEGER NOT NULL,
                            boxW INTEGER NOT NULL,
                            boxH INTEGER NOT NULL,
                            confidence REAL NOT NULL,
                            sizeMw REAL,
                            sizeMh REAL,
                            isClaimedByMe INTEGER NOT NULL,
                            isClaimedByOther INTEGER NOT NULL,
                            claimOwnerUsername TEXT,
                            serverVerdict INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )

                    // 2. Copy data, converting (0.0, 0.0) sentinel values to NULL
                    db.execSQL(
                        """
                        INSERT INTO candidate_new
                        SELECT inferenceResultId, surveyId, imageId, imageFilename, imageWidth, imageHeight,
                               CASE WHEN geoCentroidLat = 0.0 AND geoCentroidLon = 0.0 THEN NULL ELSE geoCentroidLat END,
                               CASE WHEN geoCentroidLat = 0.0 AND geoCentroidLon = 0.0 THEN NULL ELSE geoCentroidLon END,
                               geoAreaJson, boxX, boxY, boxW, boxH, confidence, sizeMw, sizeMh,
                               isClaimedByMe, isClaimedByOther, claimOwnerUsername, serverVerdict
                        FROM candidate
                        """.trimIndent(),
                    )

                    // 3. Drop old table
                    db.execSQL("DROP TABLE candidate")

                    // 4. Rename temp table
                    db.execSQL("ALTER TABLE candidate_new RENAME TO candidate")

                    // 5. Re-create index on surveyId
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_candidate_surveyId ON candidate(surveyId)",
                    )
                }
            }

        val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS sync_run (
                            surveyId INTEGER NOT NULL PRIMARY KEY,
                            phase TEXT NOT NULL,
                            total INTEGER NOT NULL,
                            done INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                }
            }
        val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS satellite_region (
                            rowId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            surveyId INTEGER NOT NULL,
                            signature TEXT NOT NULL,
                            completed INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE UNIQUE INDEX IF NOT EXISTS
                        index_satellite_region_surveyId_signature
                        ON satellite_region(surveyId, signature)
                        """.trimIndent(),
                    )
                }
            }
    }
}
