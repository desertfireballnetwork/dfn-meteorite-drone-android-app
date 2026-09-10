package au.edu.fireballs.stage4.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import au.edu.fireballs.stage4.data.local.Stage4Database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DecisionRepositoryTest {
    private lateinit var db: Stage4Database
    private lateinit var repository: DecisionRepository

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    Stage4Database::class.java,
                ).allowMainThreadQueries()
                .build()
        repository = DecisionRepository(db.localDecisionDao(), Dispatchers.IO)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `upsertVerdict round-trips an insert with synced false`() =
        runBlocking {
            repository.upsertVerdict(1L, 10L, true, null).first()

            val row = repository.getVerdict(1L).first()
            assertEquals(1L, row?.inferenceResultId)
            assertEquals(10L, row?.surveyId)
            assertEquals(true, row?.verdict)
            assertFalse(row?.synced ?: true)
        }

    @Test
    fun `upsertVerdict resolves to a single row on update`() =
        runBlocking {
            repository.upsertVerdict(1L, 10L, true, null).first()
            repository.upsertVerdict(1L, 10L, false, 5L).first()

            val rows = db.localDecisionDao().observeDecisionsForSurvey(10L).first()
            assertEquals(1, rows.size)
            val row = rows.first()
            assertEquals(false, row.verdict)
            assertEquals(5L, row.detectionTagId)
            assertFalse(row.synced)
        }

    @Test
    fun `upsertVerdict flips verdict from true to false`() =
        runBlocking {
            repository.upsertVerdict(1L, 10L, true, null).first()
            repository.upsertVerdict(1L, 10L, false, 7L).first()

            val row = repository.getVerdict(1L).first()
            assertEquals(false, row?.verdict)
            assertEquals(7L, row?.detectionTagId)
        }

    @Test
    fun `only latest decision remains in Room`() =
        runBlocking {
            repository.upsertVerdict(1L, 10L, true, null).first()
            repository.upsertVerdict(1L, 10L, false, 5L).first()
            repository.upsertVerdict(1L, 10L, true, null).first()

            val rows = db.localDecisionDao().observeDecisionsForSurvey(10L).first()
            assertEquals(1, rows.size)
            assertEquals(true, rows.first().verdict)
            assertTrue(rows.first().synced == false)
        }

    @Test
    fun `getVerdictCounts tallies yes and no for a survey`() =
        runBlocking {
            repository.upsertVerdict(1L, 10L, true, null).first()
            repository.upsertVerdict(2L, 10L, false, 5L).first()
            repository.upsertVerdict(3L, 10L, false, 6L).first()
            repository.upsertVerdict(4L, 20L, true, null).first()

            val counts = repository.getVerdictCounts(10L).first()
            assertEquals(1, counts.yes)
            assertEquals(2, counts.no)
        }
}
