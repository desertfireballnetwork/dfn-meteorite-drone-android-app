package au.edu.fireballs.stage4.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class Stage4DatabaseMigrationTest {
    private val databaseName = "stage4-migration-test"

    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            Stage4Database::class.java,
            emptyList(),
            FrameworkSQLiteOpenHelperFactory(),
        )

    @After
    fun tearDown() {
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(databaseName)
    }

    @Test
    @Throws(IOException::class)
    fun migrateSixToSevenPreservesLegacyAndProtectedRowsAsIncomplete() {
        helper.createDatabase(databaseName, 6).apply {
            execSQL(
                """
                INSERT INTO offline_bundle (
                    rowId, surveyId, created, totalBytes, tileCount,
                    satelliteRegionCount, candidateCount, bufferMeters
                ) VALUES (7, 42, 'legacy', 900, 1, 1, 1, 150.0)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO tile_manifest (
                    rowId, surveyId, candidateId, zoom, x, y, bytes
                ) VALUES (8, 42, 101, 16, 4, 5, 600)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO satellite_region (
                    rowId, surveyId, signature, completed
                ) VALUES (9, 42, 'region-signature', 1)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO local_decision (
                    inferenceResultId, surveyId, verdict, detectionTagId,
                    capturedAt, evidencePhotoRowId, synced, syncedAt,
                    syncFailedReason
                ) VALUES (101, 42, 1, NULL, 'captured', NULL, 0, NULL, NULL)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO pending_photo_upload (
                    rowId, surveyId, inferenceResultId, localFilePath,
                    capturedAt, uploaded, serverPhotoId, uploadFailedReason
                ) VALUES (11, 42, 101, '/evidence/photo.jpg', 'captured',
                    0, NULL, NULL)
                """.trimIndent(),
            )
            close()
        }

        helper
            .runMigrationsAndValidate(
                databaseName,
                7,
                true,
                Stage4Database.MIGRATION_6_7,
            ).use { database ->
                database
                    .query(
                        """
                        SELECT manifestId, surveyId, state, radiusMetres
                        FROM offline_bundle WHERE rowId = 7
                        """.trimIndent(),
                    ).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals("legacy-7", cursor.getString(0))
                        assertEquals(42L, cursor.getLong(1))
                        assertEquals("INCOMPLETE", cursor.getString(2))
                        assertEquals(150.0, cursor.getDouble(3), 0.0)
                    }
                database
                    .query(
                        "SELECT completed FROM tile_manifest WHERE rowId = 8",
                    ).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(0, cursor.getInt(0))
                    }
                database
                    .query(
                        """
                        SELECT completed, pendingDeletion
                        FROM satellite_region WHERE rowId = 9
                        """.trimIndent(),
                    ).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(0, cursor.getInt(0))
                        assertEquals(0, cursor.getInt(1))
                    }
                database
                    .query(
                        "SELECT verdict FROM local_decision WHERE inferenceResultId = 101",
                    ).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(1, cursor.getInt(0))
                    }
                database
                    .query(
                        "SELECT localFilePath FROM pending_photo_upload WHERE rowId = 11",
                    ).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals("/evidence/photo.jpg", cursor.getString(0))
                    }
            }
    }

    @Test
    @Throws(IOException::class)
    fun migrationSixToSevenSupportsRoomOpen() {
        helper.createDatabase(databaseName, 6).close()
        helper
            .runMigrationsAndValidate(
                databaseName,
                7,
                true,
                Stage4Database.MIGRATION_6_7,
            ).close()

        Room
            .databaseBuilder(
                ApplicationProvider.getApplicationContext(),
                Stage4Database::class.java,
                databaseName,
            ).addMigrations(Stage4Database.MIGRATION_6_7)
            .build()
            .close()
    }
}
