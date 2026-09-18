package au.edu.fireballs.stage4.data.tiles

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import au.edu.fireballs.stage4.data.local.OfflineBundleEntity
import au.edu.fireballs.stage4.data.local.Stage4Database
import au.edu.fireballs.stage4.data.local.TileManifestEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class OfflineBundleRepositoryTest {
    private lateinit var database: Stage4Database
    private lateinit var store: TileStore
    private lateinit var repository: OfflineBundleRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, Stage4Database::class.java)
                .allowMainThreadQueries()
                .build()
        store = TileStore(Files.createTempDirectory("bundle").toFile())
        repository =
            OfflineBundleRepository(
                store,
                database.tileManifestDao(),
                database.offlineBundleDao(),
                kotlinx.coroutines.Dispatchers.Unconfined,
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insertBundlePersistsEntity() =
        runTest {
            val bundle =
                OfflineBundleEntity(
                    surveyId = 1,
                    createdAt = 0L,
                    totalBytes = 1,
                    tileCount = 1,
                    satelliteRegionCount = 0,
                    candidateCount = 1,
                    radiusMetres = 100.0,
                )

            val rowId = repository.insertBundle(bundle)

            assertTrue(rowId > 0)
            val persisted = database.offlineBundleDao().observeLatestBundleForSurvey(1).first()
            assertNotNull(persisted)
            val row = persisted!!
            assertTrue(row.rowId == rowId)
            assertTrue(row.surveyId == 1L)
            assertTrue(row.totalBytes == 1L)
        }

    @Test
    fun deleteBundleRemovesFilesAndRows() =
        runTest {
            store.write(1, 2, 3, 4, 5, byteArrayOf(1))
            database.tileManifestDao().insertAll(
                listOf(TileManifestEntity(surveyId = 1, candidateId = 2, zoom = 3, x = 4, y = 5)),
            )
            database.offlineBundleDao().insert(
                OfflineBundleEntity(
                    surveyId = 1,
                    createdAt = 0L,
                    totalBytes = 1,
                    tileCount = 1,
                    satelliteRegionCount = 0,
                    candidateCount = 1,
                    radiusMetres = 100.0,
                ),
            )

            repository.deleteBundle(1)

            assertFalse(store.contains(1, 2, 3, 4, 5))
            assertTrue(database.tileManifestDao().getTilesForSurvey(1).isEmpty())
            assertTrue(database.offlineBundleDao().observeLatestBundleForSurvey(1).first() == null)
        }

    @Test
    fun deleteBundlePropagatesFirstFailure() =
        runTest {
            store.write(1, 2, 3, 4, 5, byteArrayOf(1))
            val failingDao = FailingTileManifestDao(database.tileManifestDao())
            val failingRepository =
                OfflineBundleRepository(
                    store,
                    failingDao,
                    database.offlineBundleDao(),
                    kotlinx.coroutines.Dispatchers.Unconfined,
                )

            var thrown: Throwable? = null
            try {
                failingRepository.deleteBundle(1)
            } catch (error: IllegalStateException) {
                thrown = error
            }

            assertTrue(thrown is IllegalStateException)
            assertFalse(store.contains(1, 2, 3, 4, 5))
        }

    private class FailingTileManifestDao(
        private val delegate: au.edu.fireballs.stage4.data.local.dao.TileManifestDao,
    ) : au.edu.fireballs.stage4.data.local.dao.TileManifestDao by delegate {
        override suspend fun deleteForSurvey(surveyId: Long) {
            error("manifest delete failed")
        }
    }
}
