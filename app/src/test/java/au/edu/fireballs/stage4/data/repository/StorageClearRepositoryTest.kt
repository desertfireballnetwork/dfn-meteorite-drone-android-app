package au.edu.fireballs.stage4.data.repository

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import au.edu.fireballs.stage4.data.local.CandidateEntity
import au.edu.fireballs.stage4.data.local.ClaimEntity
import au.edu.fireballs.stage4.data.local.LocalDecisionEntity
import au.edu.fireballs.stage4.data.local.OfflineBundleEntity
import au.edu.fireballs.stage4.data.local.PendingPhotoUploadEntity
import au.edu.fireballs.stage4.data.local.Stage4Database
import au.edu.fireballs.stage4.data.local.SurveyEntity
import au.edu.fireballs.stage4.data.local.TileManifestEntity
import au.edu.fireballs.stage4.data.tiles.OfflineRegionFailureCategory
import au.edu.fireballs.stage4.data.tiles.OfflineRegionHandle
import au.edu.fireballs.stage4.data.tiles.OfflineRegionPurgeResult
import au.edu.fireballs.stage4.data.tiles.OfflineRegionSource
import au.edu.fireballs.stage4.data.tiles.OfflineRegionWrapper
import au.edu.fireballs.stage4.data.tiles.TileStore
import au.edu.fireballs.stage4.data.tiles.TileStoreMeasuredUsage
import com.mapbox.maps.OfflineRegionTilePyramidDefinition
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
class StorageClearRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: Stage4Database
    private lateinit var tileStore: TileStore
    private lateinit var images: CandidateImageRepository
    private lateinit var source: FakeOfflineRegionSource
    private lateinit var repository: StorageClearRepository
    private lateinit var tempRoot: java.io.File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database =
            Room
                .inMemoryDatabaseBuilder(context, Stage4Database::class.java)
                .allowMainThreadQueries()
                .build()
        tileStore = TileStore(Files.createTempDirectory("storage-clear-tiles").toFile())
        images = CandidateImageRepository(context, "https://example.invalid")
        source = FakeOfflineRegionSource()
        tempRoot = Files.createTempDirectory("storage-clear-temp").toFile()
        val coordinator = mockk<StorageCoordinator>()
        coEvery { coordinator.withClearLease<StorageClearResult>(any()) } coAnswers {
            firstArg<suspend () -> StorageClearResult>().invoke()
        }
        repository =
            StorageClearRepository(
                storageCoordinator = coordinator,
                database = database,
                offlineBundleDao = database.offlineBundleDao(),
                tileManifestDao = database.tileManifestDao(),
                candidateCropManifestDao = database.candidateCropManifestDao(),
                satelliteRegionDao = database.satelliteRegionDao(),
                tileStore = tileStore,
                candidateImageRepository = images,
                offlineRegionWrapper =
                    OfflineRegionWrapper(
                        source = source,
                        mainHandler = Handler(Looper.getMainLooper()),
                    ),
                candidateCropRoot = context.filesDir.resolve("crops"),
                ownedTempCacheRoots = listOf(tempRoot),
                ioDispatcher = UnconfinedTestDispatcher(),
            )
    }

    @After
    fun tearDown() {
        ActiveEvidenceCapture.reset()
        database.close()
        context.filesDir.resolve("crops").deleteRecursively()
        tempRoot.deleteRecursively()
    }

    @Test
    fun geotiffClearRemovesTilesAndManifestsWhileProtectedRowsRemain() =
        runTest {
            database.offlineBundleDao().insert(
                OfflineBundleEntity(
                    manifestId = "manifest",
                    surveyId = 1L,
                    sourceVersion = "v1",
                    state = WorkingSetState.COMPLETE.value,
                    totalBytes = PNG.size.toLong(),
                    tileCount = 1,
                    satelliteRegionCount = 0,
                    candidateCount = 1,
                ),
            )
            database.tileManifestDao().insertAll(
                listOf(
                    TileManifestEntity(
                        manifestId = "manifest",
                        surveyId = 1L,
                        candidateId = 7L,
                        sourceVersion = "v1",
                        radiusMetres = 15.0,
                        zoom = 20,
                        x = 1,
                        y = 1,
                        kind = "GEOTIFF",
                        expectedFormat = "PNG",
                        completed = true,
                        bytes = PNG.size.toLong(),
                    ),
                ),
            )
            tileStore.write(1L, 7L, 20, 1, 1, PNG)
            seedProtectedData()
            val protectedTables =
                listOf(
                    "pending_photo_upload",
                    "local_decision",
                    "claim",
                    "survey",
                    "candidate",
                )
            val protectedBefore =
                protectedTables.associateWith { table ->
                    requireNotNull(rowCountIfPresent(table)).also {
                        assertTrue(it > 0)
                    }
                }
            val evidence = context.filesDir.resolve("evidence/protected.jpg")
            evidence.parentFile?.mkdirs()
            evidence.writeBytes(PNG)

            val result = repository.clear(StorageClearCategory.GeotiffTiles)

            assertEquals(
                StorageClearResult.Cleared(StorageClearCategory.GeotiffTiles),
                result,
            )
            assertFalse(tileStore.contains(1L, 7L, 20, 1, 1))
            assertTrue(database.tileManifestDao().getForManifest("manifest").isEmpty())
            assertTrue(evidence.exists())
            assertEquals(
                WorkingSetState.INCOMPLETE.value,
                database.offlineBundleDao().getByManifestId("manifest")?.state,
            )
            protectedTables.forEach { table ->
                val after = requireNotNull(rowCountIfPresent(table))
                assertTrue(after > 0)
                assertEquals(protectedBefore[table], after)
            }
            assertNotNull(database.surveyDao().getById(PROTECTED_SURVEY_ID))
            assertNotNull(database.candidateDao().getById(PROTECTED_CANDIDATE_ID))
            assertNotNull(
                database.claimDao().getByCandidateId(PROTECTED_CANDIDATE_ID),
            )
            assertEquals(
                1,
                database.localDecisionDao().getUnsynced().count {
                    it.inferenceResultId == PROTECTED_CANDIDATE_ID
                },
            )
            assertEquals(
                1,
                database.pendingPhotoUploadDao().getUnuploaded().count {
                    it.inferenceResultId == PROTECTED_CANDIDATE_ID
                },
            )
        }

    private suspend fun seedProtectedData() {
        database.surveyDao().upsert(
            SurveyEntity(
                id = PROTECTED_SURVEY_ID,
                eventId = "protected-event",
                description = "Protected survey",
                created = TEST_TIME,
                hasStage4 = true,
                activeSurvey = true,
                tilesetId = null,
                latestTaskCreated = TEST_TIME,
                baseLat = -37.81,
                baseLon = 144.96,
            ),
        )
        database.candidateDao().upsert(
            CandidateEntity(
                inferenceResultId = PROTECTED_CANDIDATE_ID,
                surveyId = PROTECTED_SURVEY_ID,
                imageId = 99L,
                imageFilename = "protected.jpg",
                imageWidth = 100,
                imageHeight = 100,
                geoCentroidLat = -37.81,
                geoCentroidLon = 144.96,
                geoAreaJson = "[]",
                boxX = 50,
                boxY = 50,
                boxW = 10,
                boxH = 10,
                confidence = 0.9f,
                sizeMw = null,
                sizeMh = null,
                isClaimedByMe = true,
                claimOwnerUsername = "tester",
            ),
        )
        database.claimDao().upsert(
            ClaimEntity(
                inferenceResultId = PROTECTED_CANDIDATE_ID,
                surveyId = PROTECTED_SURVEY_ID,
                userId = 7L,
                username = "tester",
                claimedAt = TEST_TIME,
                isMine = true,
                isActive = true,
            ),
        )
        val uploadId =
            database.pendingPhotoUploadDao().insert(
                PendingPhotoUploadEntity(
                    surveyId = PROTECTED_SURVEY_ID,
                    inferenceResultId = PROTECTED_CANDIDATE_ID,
                    localFilePath = "evidence/protected.jpg",
                    capturedAt = TEST_TIME,
                ),
            )
        database.localDecisionDao().saveDecision(
            LocalDecisionEntity(
                inferenceResultId = PROTECTED_CANDIDATE_ID,
                surveyId = PROTECTED_SURVEY_ID,
                verdict = true,
                detectionTagId = null,
                capturedAt = TEST_TIME,
                evidencePhotoRowId = uploadId,
            ),
        )
    }

    private fun rowCountIfPresent(table: String): Int? {
        val tables =
            database.openHelper.readableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf(table),
            )
        tables.use {
            if (!it.moveToFirst()) {
                return null
            }
        }
        val rows = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table")
        return rows.use {
            assertTrue(it.moveToFirst())
            it.getInt(0)
        }
    }

    private class FakeOfflineRegionSource : OfflineRegionSource {
        var purgeResult: OfflineRegionPurgeResult? = null

        override fun createOfflineRegion(
            definition: OfflineRegionTilePyramidDefinition,
            callback: (Result<OfflineRegionHandle>) -> Unit,
        ) = callback(Result.failure(UnsupportedOperationException()))

        override fun getOfflineRegions(callback: (Result<List<OfflineRegionHandle>>) -> Unit) {
            callback(Result.success(emptyList()))
        }
    }

    companion object {
        private const val PROTECTED_SURVEY_ID = 91L
        private const val PROTECTED_CANDIDATE_ID = 92L
        private const val TEST_TIME = "2026-09-18T00:00:00Z"
        private val PNG =
            Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk" +
                    "YAAAAAYAAjCB0C8AAAAASUVORK5CYII=",
            )
    }

    @Test
    fun geotiffClearInvalidatesOnlyBundlesOwningTileManifests() =
        runTest {
            insertBundle("tiles", WorkingSetState.COMPLETE)
            insertBundle("crops", WorkingSetState.COMPLETE)
            database.tileManifestDao().insertAll(listOf(tileManifest("tiles", 11L)))
            database.candidateCropManifestDao().insertAll(
                listOf(cropManifest("crops", 12L)),
            )

            val result = repository.clear(StorageClearCategory.GeotiffTiles)

            assertEquals(
                StorageClearResult.Cleared(StorageClearCategory.GeotiffTiles),
                result,
            )
            assertEquals(
                WorkingSetState.INCOMPLETE.value,
                database.offlineBundleDao().getByManifestId("tiles")?.state,
            )
            assertEquals(
                WorkingSetState.COMPLETE.value,
                database.offlineBundleDao().getByManifestId("crops")?.state,
            )
            assertTrue(database.tileManifestDao().getForManifest("tiles").isEmpty())
            assertEquals(
                1,
                database.candidateCropManifestDao().getForManifest("crops").size,
            )
        }

    @Test
    fun satelliteClearRetainsRetryableRowsAndRemovesConfirmedAbsentRows() =
        runTest {
            insertBundle("satellite", WorkingSetState.COMPLETE)
            val row = satelliteRegion("satellite", "retry")
            database.satelliteRegionDao().upsert(row)
            val retryable =
                OfflineRegionPurgeResult.RetryableFailure(
                    target = satelliteTarget("retry"),
                    category =
                        OfflineRegionFailureCategory.PURGE_REJECTED,
                    attemptTimeMillis = 123L,
                )
            repository = repositoryWithWrapper(synchronousWrapper(retryable))

            val partial = repository.clear(StorageClearCategory.SatelliteMaps)

            assertEquals(
                StorageClearResult.PartiallyCleared(
                    StorageClearCategory.SatelliteMaps,
                    1,
                ),
                partial,
            )
            assertEquals(1, database.satelliteRegionDao().getPendingDeletion().size)

            repository =
                repositoryWithWrapper(
                    synchronousWrapper(
                        OfflineRegionPurgeResult.ConfirmedAbsent(
                            satelliteTarget("retry"),
                        ),
                    ),
                )

            val cleared = repository.clear(StorageClearCategory.SatelliteMaps)

            assertEquals(
                StorageClearResult.Cleared(StorageClearCategory.SatelliteMaps),
                cleared,
            )
            assertTrue(database.satelliteRegionDao().getPendingDeletion().isEmpty())
        }

    @Test
    fun clearAllRemovesDownloadsAndBundlesButLeavesTemporaryCache() =
        runTest {
            insertBundle("all", WorkingSetState.COMPLETE)
            database.tileManifestDao().insertAll(listOf(tileManifest("all", 21L)))
            database.candidateCropManifestDao().insertAll(
                listOf(cropManifest("all", 21L)),
            )
            database.satelliteRegionDao().upsert(satelliteRegion("all", "all-region"))
            tileStore.write(1L, 21L, 20, 1, 1, PNG)
            val crop = context.filesDir.resolve("crops/1/21.jpg")
            crop.parentFile?.mkdirs()
            crop.writeBytes(PNG)
            val temporary = tempRoot.resolve("retained.tmp")
            temporary.writeText("retained")
            repository =
                repositoryWithWrapper(
                    synchronousWrapper(
                        OfflineRegionPurgeResult.ConfirmedAbsent(
                            satelliteTarget("all-region"),
                        ),
                    ),
                )

            val result = repository.clear(StorageClearCategory.AllCachedDownloads)

            assertEquals(
                StorageClearResult.Cleared(StorageClearCategory.AllCachedDownloads),
                result,
            )
            assertFalse(tileStore.contains(1L, 21L, 20, 1, 1))
            assertFalse(crop.exists())
            assertTrue(database.satelliteRegionDao().getPendingDeletion().isEmpty())
            assertTrue(database.offlineBundleDao().getAll().isEmpty())
            assertTrue(temporary.exists())
        }

    private fun repositoryWithWrapper(wrapper: OfflineRegionWrapper): StorageClearRepository {
        val coordinator = mockk<StorageCoordinator>()
        coEvery { coordinator.withClearLease<StorageClearResult>(any()) } coAnswers {
            firstArg<suspend () -> StorageClearResult>().invoke()
        }
        return StorageClearRepository(
            storageCoordinator = coordinator,
            database = database,
            offlineBundleDao = database.offlineBundleDao(),
            tileManifestDao = database.tileManifestDao(),
            candidateCropManifestDao = database.candidateCropManifestDao(),
            satelliteRegionDao = database.satelliteRegionDao(),
            tileStore = tileStore,
            candidateImageRepository = images,
            offlineRegionWrapper = wrapper,
            candidateCropRoot = context.filesDir.resolve("crops"),
            ownedTempCacheRoots = listOf(tempRoot),
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    private fun synchronousWrapper(purgeResult: OfflineRegionPurgeResult): OfflineRegionWrapper {
        val wrapper = mockk<OfflineRegionWrapper>()
        io.mockk.every { wrapper.listRegions(any()) } answers {
            val callback =
                firstArg<(Result<List<OfflineRegionHandle>>) -> Unit>()
            callback(Result.success(emptyList()))
        }
        io.mockk.every { wrapper.purgeExact(any(), any()) } answers {
            val callback = secondArg<(OfflineRegionPurgeResult) -> Unit>()
            callback(purgeResult)
        }
        return wrapper
    }

    private suspend fun insertBundle(
        manifestId: String,
        state: WorkingSetState,
    ) {
        database.offlineBundleDao().insert(
            OfflineBundleEntity(
                manifestId = manifestId,
                surveyId = 1L,
                sourceVersion = "v1",
                state = state.value,
                totalBytes = 1L,
                tileCount = 1,
                satelliteRegionCount = 1,
                candidateCount = 1,
            ),
        )
    }

    private fun tileManifest(
        manifestId: String,
        candidateId: Long,
    ) = TileManifestEntity(
        manifestId = manifestId,
        surveyId = 1L,
        candidateId = candidateId,
        sourceVersion = "v1",
        radiusMetres = 15.0,
        zoom = 20,
        x = 1,
        y = 1,
        kind = "GEOTIFF",
        expectedFormat = "PNG",
        completed = true,
        bytes = PNG.size.toLong(),
    )

    private fun cropManifest(
        manifestId: String,
        candidateId: Long,
    ) = au.edu.fireballs.stage4.data.local.CandidateCropManifestEntity(
        manifestId = manifestId,
        surveyId = 1L,
        candidateId = candidateId,
        sourceVersion = "v1",
        requestSignature = "crop:1:$candidateId",
        completed = true,
    )

    private fun satelliteRegion(
        manifestId: String,
        signature: String,
    ) = au.edu.fireballs.stage4.data.local.SatelliteRegionEntity(
        manifestId = manifestId,
        surveyId = 1L,
        sourceVersion = "v1",
        signature = signature,
        completed = true,
    )

    private fun satelliteTarget(signature: String) =
        PreDownloadTargetKey.Satellite(
            surveyId = 1L,
            sourceVersion = "v1",
            signature = signature,
        )

    @Test
    fun temporaryCacheClearSkipsActiveCaptureAndToleratesMissingRoot() =
        runTest {
            val inactive = tempRoot.resolve("inactive.jpg")
            val active = tempRoot.resolve("active.jpg")
            inactive.writeText("inactive")
            active.writeText("active")
            ActiveEvidenceCapture.mark(active.canonicalPath)

            val result = repository.clear(StorageClearCategory.TemporaryCache)

            assertEquals(
                StorageClearResult.Cleared(StorageClearCategory.TemporaryCache),
                result,
            )
            assertFalse(inactive.exists())
            assertTrue(active.exists())

            ActiveEvidenceCapture.reset()
            tempRoot.deleteRecursively()

            val missingRootResult =
                repository.clear(StorageClearCategory.TemporaryCache)

            assertEquals(
                StorageClearResult.Cleared(StorageClearCategory.TemporaryCache),
                missingRootResult,
            )
        }

    @Test
    fun geotiffResidualFilesReturnFailed() =
        runTest {
            val residualTileStore = mockk<TileStore>()
            io.mockk.every { residualTileStore.deleteAll() } returns Unit
            coEvery { residualTileStore.measuredUsage() } returns
                TileStoreMeasuredUsage(
                    geotiffBytes = 1L,
                    ownedTempCacheBytes = 0L,
                )
            val coordinator = mockk<StorageCoordinator>()
            coEvery { coordinator.withClearLease<StorageClearResult>(any()) } coAnswers {
                firstArg<suspend () -> StorageClearResult>().invoke()
            }
            val residualRepository =
                StorageClearRepository(
                    storageCoordinator = coordinator,
                    database = database,
                    offlineBundleDao = database.offlineBundleDao(),
                    tileManifestDao = database.tileManifestDao(),
                    candidateCropManifestDao = database.candidateCropManifestDao(),
                    satelliteRegionDao = database.satelliteRegionDao(),
                    tileStore = residualTileStore,
                    candidateImageRepository = images,
                    offlineRegionWrapper =
                        OfflineRegionWrapper(
                            source = source,
                            mainHandler = Handler(Looper.getMainLooper()),
                        ),
                    candidateCropRoot = context.filesDir.resolve("crops"),
                    ownedTempCacheRoots = listOf(tempRoot),
                    ioDispatcher = UnconfinedTestDispatcher(),
                )

            val result =
                residualRepository.clear(StorageClearCategory.GeotiffTiles)

            assertEquals(
                StorageClearResult.Failed(
                    StorageClearCategory.GeotiffTiles,
                    "Tile files remain after clear",
                ),
                result,
            )
        }

    @Test
    fun geotiffFileIoFailureReturnsFailed() =
        runTest {
            val failingTileStore = mockk<TileStore>()
            io.mockk.every { failingTileStore.deleteAll() } throws
                java.io.IOException("delete failed")
            val coordinator = mockk<StorageCoordinator>()
            coEvery { coordinator.withClearLease<StorageClearResult>(any()) } coAnswers {
                firstArg<suspend () -> StorageClearResult>().invoke()
            }
            val failingRepository =
                StorageClearRepository(
                    storageCoordinator = coordinator,
                    database = database,
                    offlineBundleDao = database.offlineBundleDao(),
                    tileManifestDao = database.tileManifestDao(),
                    candidateCropManifestDao = database.candidateCropManifestDao(),
                    satelliteRegionDao = database.satelliteRegionDao(),
                    tileStore = failingTileStore,
                    candidateImageRepository = images,
                    offlineRegionWrapper =
                        OfflineRegionWrapper(
                            source = source,
                            mainHandler = Handler(Looper.getMainLooper()),
                        ),
                    candidateCropRoot = context.filesDir.resolve("crops"),
                    ownedTempCacheRoots = listOf(tempRoot),
                    ioDispatcher = UnconfinedTestDispatcher(),
                )

            val result =
                failingRepository.clear(StorageClearCategory.GeotiffTiles)

            assertTrue(result is StorageClearResult.Failed)
            assertEquals(StorageClearCategory.GeotiffTiles, result.category)
            assertFalse(result is StorageClearResult.Cleared)
        }
}
