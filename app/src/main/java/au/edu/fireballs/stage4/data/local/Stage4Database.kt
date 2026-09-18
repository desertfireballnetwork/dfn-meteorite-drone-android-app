package au.edu.fireballs.stage4.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import au.edu.fireballs.stage4.data.local.dao.CandidateCropManifestDao
import au.edu.fireballs.stage4.data.local.dao.CandidateDao
import au.edu.fireballs.stage4.data.local.dao.ClaimDao
import au.edu.fireballs.stage4.data.local.dao.LocalDecisionDao
import au.edu.fireballs.stage4.data.local.dao.OfflineBundleDao
import au.edu.fireballs.stage4.data.local.dao.PendingPhotoUploadDao
import au.edu.fireballs.stage4.data.local.dao.SatelliteRegionDao
import au.edu.fireballs.stage4.data.local.dao.SurveyDao
import au.edu.fireballs.stage4.data.local.dao.SyncRunDao
import au.edu.fireballs.stage4.data.local.dao.TileManifestDao

internal const val STAGE4_DATABASE_VERSION = 7

@Database(
    entities = [
        SurveyEntity::class,
        CandidateEntity::class,
        ClaimEntity::class,
        LocalDecisionEntity::class,
        PendingPhotoUploadEntity::class,
        OfflineBundleEntity::class,
        TileManifestEntity::class,
        CandidateCropManifestEntity::class,
        SyncRunEntity::class,
        SatelliteRegionEntity::class,
    ],
    version = STAGE4_DATABASE_VERSION,
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

    abstract fun candidateCropManifestDao(): CandidateCropManifestDao

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

                    db.execSQL("DROP TABLE candidate")

                    db.execSQL("ALTER TABLE candidate_new RENAME TO candidate")

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

        val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE offline_bundle_new (
                            rowId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            manifestId TEXT NOT NULL,
                            surveyId INTEGER NOT NULL,
                            sourceVersion TEXT NOT NULL,
                            radiusMetres REAL NOT NULL,
                            minZoom INTEGER NOT NULL,
                            maxZoom INTEGER NOT NULL,
                            state TEXT NOT NULL,
                            createdAt INTEGER NOT NULL,
                            updatedAt INTEGER NOT NULL,
                            totalBytes INTEGER NOT NULL,
                            tileCount INTEGER NOT NULL,
                            satelliteRegionCount INTEGER NOT NULL,
                            candidateCount INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        INSERT INTO offline_bundle_new (
                            rowId, manifestId, surveyId, sourceVersion, radiusMetres,
                            minZoom, maxZoom, state, createdAt, updatedAt, totalBytes,
                            tileCount, satelliteRegionCount, candidateCount
                        )
                        SELECT rowId, 'legacy-' || rowId, surveyId, '', bufferMeters,
                            0, 0, 'INCOMPLETE', 0, 0, totalBytes, tileCount,
                            satelliteRegionCount, candidateCount
                        FROM offline_bundle
                        """.trimIndent(),
                    )
                    db.execSQL("DROP TABLE offline_bundle")
                    db.execSQL("ALTER TABLE offline_bundle_new RENAME TO offline_bundle")
                    db.execSQL(
                        """
                        CREATE UNIQUE INDEX index_offline_bundle_manifestId
                        ON offline_bundle(manifestId)
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX index_offline_bundle_surveyId ON offline_bundle(surveyId)",
                    )
                    db.execSQL(
                        """
                        CREATE INDEX index_offline_bundle_surveyId_sourceVersion
                        ON offline_bundle(surveyId, sourceVersion)
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX index_offline_bundle_state ON offline_bundle(state)",
                    )
                    db.execSQL(
                        """
                        CREATE TABLE tile_manifest_new (
                            rowId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            manifestId TEXT NOT NULL,
                            surveyId INTEGER NOT NULL,
                            candidateId INTEGER NOT NULL,
                            sourceVersion TEXT NOT NULL,
                            radiusMetres REAL NOT NULL,
                            zoom INTEGER NOT NULL,
                            x INTEGER NOT NULL,
                            y INTEGER NOT NULL,
                            kind TEXT NOT NULL,
                            expectedFormat TEXT NOT NULL,
                            completed INTEGER NOT NULL,
                            bytes INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        INSERT INTO tile_manifest_new (
                            rowId, manifestId, surveyId, candidateId, sourceVersion,
                            radiusMetres, zoom, x, y, kind, expectedFormat, completed, bytes
                        )
                        SELECT t.rowId, COALESCE(
                            (
                                SELECT b.manifestId FROM offline_bundle b
                                WHERE b.surveyId = t.surveyId
                                ORDER BY b.rowId DESC LIMIT 1
                            ),
                            'legacy-orphan-' || t.rowId
                        ), t.surveyId, t.candidateId, '', 0, t.zoom, t.x, t.y,
                        'SOURCE', '', 0, t.bytes
                        FROM tile_manifest t
                        """.trimIndent(),
                    )
                    db.execSQL("DROP TABLE tile_manifest")
                    db.execSQL("ALTER TABLE tile_manifest_new RENAME TO tile_manifest")
                    db.execSQL(
                        "CREATE INDEX index_tile_manifest_manifestId ON tile_manifest(manifestId)",
                    )
                    db.execSQL(
                        "CREATE INDEX index_tile_manifest_surveyId ON tile_manifest(surveyId)",
                    )
                    db.execSQL(
                        "CREATE INDEX index_tile_manifest_candidateId " +
                            "ON tile_manifest(candidateId)",
                    )
                    db.execSQL(
                        """
                        CREATE UNIQUE INDEX index_tile_manifest_exact_identity
                        ON tile_manifest(
                            manifestId, surveyId, candidateId, sourceVersion, radiusMetres,
                            zoom, x, y, kind, expectedFormat
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE INDEX index_tile_manifest_manifestId_completed
                        ON tile_manifest(manifestId, completed)
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE TABLE candidate_crop_manifest (
                            rowId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            manifestId TEXT NOT NULL,
                            surveyId INTEGER NOT NULL,
                            candidateId INTEGER NOT NULL,
                            sourceVersion TEXT NOT NULL,
                            requestSignature TEXT NOT NULL,
                            completed INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE INDEX index_candidate_crop_manifest_manifestId
                        ON candidate_crop_manifest(manifestId)
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE INDEX index_candidate_crop_manifest_surveyId
                        ON candidate_crop_manifest(surveyId)
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE INDEX index_candidate_crop_manifest_candidateId
                        ON candidate_crop_manifest(candidateId)
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE UNIQUE INDEX index_candidate_crop_manifest_exact_identity
                        ON candidate_crop_manifest(
                            manifestId, surveyId, candidateId, sourceVersion,
                            requestSignature
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE INDEX index_candidate_crop_manifest_manifestId_completed
                        ON candidate_crop_manifest(manifestId, completed)
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE TABLE satellite_region_new (
                            rowId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            manifestId TEXT NOT NULL,
                            surveyId INTEGER NOT NULL,
                            sourceVersion TEXT NOT NULL,
                            signature TEXT NOT NULL,
                            completed INTEGER NOT NULL,
                            pendingDeletion INTEGER NOT NULL,
                            purgeCategory TEXT,
                            lastAttemptTime INTEGER
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        INSERT INTO satellite_region_new (
                            rowId, manifestId, surveyId, sourceVersion, signature,
                            completed, pendingDeletion, purgeCategory, lastAttemptTime
                        )
                        SELECT s.rowId, COALESCE(
                            (
                                SELECT b.manifestId FROM offline_bundle b
                                WHERE b.surveyId = s.surveyId
                                ORDER BY b.rowId DESC LIMIT 1
                            ),
                            'legacy-satellite-' || s.rowId
                        ), s.surveyId, '', s.signature, 0, 0, NULL, NULL
                        FROM satellite_region s
                        """.trimIndent(),
                    )
                    db.execSQL("DROP TABLE satellite_region")
                    db.execSQL(
                        "ALTER TABLE satellite_region_new RENAME TO satellite_region",
                    )
                    db.execSQL(
                        """
                        CREATE INDEX index_satellite_region_manifestId
                        ON satellite_region(manifestId)
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE UNIQUE INDEX index_satellite_region_surveyId_signature
                        ON satellite_region(surveyId, signature)
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE INDEX index_satellite_region_exact_identity
                        ON satellite_region(
                            manifestId, surveyId, sourceVersion, signature
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE INDEX index_satellite_region_pendingDeletion
                        ON satellite_region(pendingDeletion)
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE INDEX index_satellite_region_manifestId_completed
                        ON satellite_region(manifestId, completed)
                        """.trimIndent(),
                    )
                }
            }

        val ALL_MIGRATIONS: Array<Migration> =
            arrayOf(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
            )
    }
}
