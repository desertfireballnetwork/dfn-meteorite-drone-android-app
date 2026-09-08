package au.edu.fireballs.stage4.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class Stage4DatabaseMigrationTest {
    private val testDb = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            Stage4Database::class.java,
            emptyList(),
            FrameworkSQLiteOpenHelperFactory(),
        )

    @Test
    @Throws(IOException::class)
    fun migrate1To2() {
        var db =
            helper.createDatabase(testDb, 1).apply {
                // Insert test data using version 1 schema
                execSQL(
                    """
                    INSERT INTO candidate (
                        inferenceResultId, surveyId, imageId, imageFilename,
                        imageWidth, imageHeight, geoCentroidLat, geoCentroidLon,
                        geoAreaJson, boxX, boxY, boxW, boxH, confidence,
                        isClaimedByMe
                    ) VALUES (
                        101, 7, 202, 'test_image.jpg',
                        4000, 3000, -29.0, 115.0,
                        '[]', 2000, 1500, 100, 100, 0.95,
                        0
                    )
                    """.trimIndent(),
                )
                close()
            }

        // Re-open database with version 2 and apply migration
        db = helper.runMigrationsAndValidate(testDb, 2, true, Stage4Database.MIGRATION_1_2)

        // Verify the new columns exist with default values
        val cursor =
            db.query(
                "SELECT isClaimedByOther, serverVerdict FROM candidate WHERE inferenceResultId = 101",
            )
        assertTrue(cursor.moveToFirst())
        assertEquals(0, cursor.getInt(0)) // isClaimedByOther default 0 (false)
        assertEquals(0, cursor.getInt(1)) // serverVerdict default 0 (unprocessed)
        cursor.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate2To3() {
        var db =
            helper.createDatabase(testDb, 2).apply {
                execSQL(
                    """
                    INSERT INTO survey (
                        id, eventId, created, hasStage4, activeSurvey
                    ) VALUES (
                        7, 'event-7', '2026-09-08T00:00:00Z', 1, 1
                    )
                    """.trimIndent(),
                )
                close()
            }

        db = helper.runMigrationsAndValidate(testDb, 3, true, Stage4Database.MIGRATION_2_3)

        val cursor =
            db.query(
                """
                SELECT surveyedAreasJson, detectionTagsJson, userLocationsJson, showGeolocationAccuracyCircle
                FROM survey WHERE id = 7
                """.trimIndent(),
            )
        assertTrue(cursor.moveToFirst())
        assertTrue(cursor.isNull(0)) // surveyedAreasJson
        assertTrue(cursor.isNull(1)) // detectionTagsJson
        assertTrue(cursor.isNull(2)) // userLocationsJson
        assertEquals(1, cursor.getInt(3)) // showGeolocationAccuracyCircle default 1 (true)
        cursor.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate3To4() {
        var db =
            helper.createDatabase(testDb, 3).apply {
                // Insert test data with (0.0, 0.0) sentinel values for coordinates
                execSQL(
                    """
                    INSERT INTO candidate (
                        inferenceResultId, surveyId, imageId, imageFilename,
                        imageWidth, imageHeight, geoCentroidLat, geoCentroidLon,
                        geoAreaJson, boxX, boxY, boxW, boxH, confidence,
                        isClaimedByMe, isClaimedByOther, serverVerdict
                    ) VALUES (
                        101, 7, 202, 'test_image.jpg',
                        4000, 3000, 0.0, 0.0,
                        '[]', 2000, 1500, 100, 100, 0.95,
                        0, 0, 0
                    )
                    """.trimIndent(),
                )
                // Insert another row with real coordinates
                execSQL(
                    """
                    INSERT INTO candidate (
                        inferenceResultId, surveyId, imageId, imageFilename,
                        imageWidth, imageHeight, geoCentroidLat, geoCentroidLon,
                        geoAreaJson, boxX, boxY, boxW, boxH, confidence,
                        isClaimedByMe, isClaimedByOther, serverVerdict
                    ) VALUES (
                        102, 7, 203, 'test_image_2.jpg',
                        4000, 3000, -31.95, 115.86,
                        '[]', 2000, 1500, 100, 100, 0.95,
                        0, 0, 0
                    )
                    """.trimIndent(),
                )
                close()
            }

        db = helper.runMigrationsAndValidate(testDb, 4, true, Stage4Database.MIGRATION_3_4)

        // Verify (0.0, 0.0) converted to NULL
        val cursor1 =
            db.query(
                "SELECT geoCentroidLat, geoCentroidLon FROM candidate WHERE inferenceResultId = 101",
            )
        assertTrue(cursor1.moveToFirst())
        assertTrue(cursor1.isNull(0))
        assertTrue(cursor1.isNull(1))
        cursor1.close()

        // Verify real coordinates preserved
        val cursor2 =
            db.query(
                "SELECT geoCentroidLat, geoCentroidLon FROM candidate WHERE inferenceResultId = 102",
            )
        assertTrue(cursor2.moveToFirst())
        assertEquals(-31.95, cursor2.getDouble(0), 0.001)
        assertEquals(115.86, cursor2.getDouble(1), 0.001)
        cursor2.close()
    }
}
