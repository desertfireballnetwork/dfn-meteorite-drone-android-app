package au.edu.fireballs.stage4.data.repository

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import au.edu.fireballs.stage4.data.local.CandidateCropManifestEntity
import au.edu.fireballs.stage4.data.local.LocalDecisionEntity
import au.edu.fireballs.stage4.data.local.OfflineBundleEntity
import au.edu.fireballs.stage4.data.local.PendingPhotoUploadEntity
import au.edu.fireballs.stage4.data.local.SatelliteRegionEntity
import au.edu.fireballs.stage4.data.local.Stage4Database
import au.edu.fireballs.stage4.data.local.TileManifestEntity
import au.edu.fireballs.stage4.data.tiles.OfflineRegionHandle
import au.edu.fireballs.stage4.data.tiles.OfflineRegionSource
import au.edu.fireballs.stage4.data.tiles.OfflineRegionWrapper
import au.edu.fireballs.stage4.data.tiles.TileStore
import com.mapbox.bindgen.ExpectedFactory
import com.mapbox.maps.AsyncOperationResultCallback
import com.mapbox.maps.OfflineRegionDownloadState
import com.mapbox.maps.OfflineRegionObserver
import com.mapbox.maps.OfflineRegionTilePyramidDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64
import kotlin.io.path.createTempDirectory

@RunWith(AndroidJUnit4::class)
class OfflineWorkingSetRepositoryIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: Stage4Database
    private lateinit var root: File
    private lateinit var tileStore: TileStore
    private lateinit var images: CandidateImageRepository
    private lateinit var source: FakeOfflineRegionSource
    private lateinit var repository: OfflineWorkingSetRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database =
            Room
                .inMemoryDatabaseBuilder(context, Stage4Database::class.java)
                .allowMainThreadQueries()
                .build()
        root = createTempDirectory("working-set-integration-").toFile()
        tileStore = TileStore(File(root, "tiles"))
        images = CandidateImageRepository(context, "https://example.invalid")
        source = FakeOfflineRegionSource()
        repository =
            OfflineWorkingSetRepository(
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
                ioDispatcher = Dispatchers.IO,
            )
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
        File(context.filesDir, "crops").deleteRecursively()
    }

    @Test
    fun persistedManifestLifecycleBeginsPrunesAndCompletes() =
        runBlocking {
            seedOwnedManifest()
            writeTile(oldTile())
            writeCrop(1L)
            val retained = newTile()
            writeTile(retained)
            val started =
                repository.beginReplacement(
                    target(),
                    classification(
                        retained = setOf(retained),
                        missing = setOf(newCrop()),
                        obsolete = setOf(oldTile().toPruneKey()),
                    ),
                    "replacement",
                ) as ReplacementBeginResult.Started

            assertEquals("REPLACING", bundle("replacement")?.state)
            assertEquals(2, requiredCount("replacement"))
            assertTrue(
                repository.pruneObsolete(started.session) is ReplacementPruneResult.Completed,
            )
            assertEquals("INCOMPLETE", bundle("replacement")?.state)
            assertNull(bundle("owned"))

            writeCrop(2L)
            assertTrue(
                repository.markItemComplete("replacement", newCrop())
                    is ReplacementItemResult.Completed,
            )
            assertEquals(
                ReplacementCompletionResult.Completed("replacement"),
                repository.completeReplacement("replacement"),
            )
            assertEquals("COMPLETE", bundle("replacement")?.state)
        }

    @Test
    fun protectedRowsSurvivePartialOverlap() =
        runBlocking {
            seedProtectedRows()
            seedOwnedManifest()
            writeTile(newTile())
            val started =
                repository.beginReplacement(
                    target(),
                    classification(
                        retained = setOf(newTile()),
                        missing = setOf(newCrop()),
                        obsolete = setOf(oldTile().toPruneKey()),
                    ),
                    "partial",
                ) as ReplacementBeginResult.Started

            repository.pruneObsolete(started.session)

            assertProtectedRowsAndEvidence()
        }

    @Test
    fun protectedRowsSurviveZeroOverlapAndBroadOwnerClears() =
        runBlocking {
            seedProtectedRows()
            seedOwnedManifest()
            writeTile(oldTile())
            writeCrop(1L)
            val started =
                repository.beginReplacement(
                    target(),
                    classification(
                        missing = setOf(newTile(), newCrop()),
                        obsolete =
                            setOf(
                                oldTile().toPruneKey(),
                                PreDownloadPruneKey.Crop(SURVEY_ID, 1L),
                            ),
                        clearCommands =
                            listOf(
                                PreDownloadClearCommand.ClearGeotiffs,
                                PreDownloadClearCommand.ClearCrops,
                            ),
                    ),
                    "zero",
                ) as ReplacementBeginResult.Started

            repository.pruneObsolete(started.session)

            assertProtectedRowsAndEvidence()
            assertFalse(tileStore.contains(SURVEY_ID, 1L, 20, 1, 1))
            assertFalse(evidenceFile().readText().isEmpty())
        }

    @Test
    fun protectedRowsSurviveOwnerFailureAndReplacementStaysIncomplete() =
        runBlocking {
            seedProtectedRows()
            seedOwnedManifest()
            val tileRoot = tileStore.surveyTilesDirectory(SURVEY_ID).parentFile!!
            tileRoot.deleteRecursively()
            tileRoot.writeText("blocks-owned-directory")
            val started =
                repository.beginReplacement(
                    target(),
                    classification(
                        missing = setOf(newTile()),
                        obsolete = setOf(oldTile().toPruneKey()),
                    ),
                    "failure",
                ) as ReplacementBeginResult.Started

            val result = repository.pruneObsolete(started.session)

            assertTrue(result is ReplacementPruneResult.LocalDeletionFailed)
            assertEquals("INCOMPLETE", bundle("failure")?.state)
            assertTrue(database.tileManifestDao().getForManifest("owned").isNotEmpty())
            assertProtectedRowsAndEvidence()
        }

    @Test
    fun legacyIncompleteBundleCanBeCompleted() =
        runBlocking {
            database.offlineBundleDao().insert(
                OfflineBundleEntity(
                    manifestId = "legacy-7",
                    surveyId = SURVEY_ID,
                    sourceVersion = "",
                    state = "INCOMPLETE",
                    totalBytes = 100L,
                    tileCount = 0,
                    satelliteRegionCount = 0,
                    candidateCount = 1,
                ),
            )

            assertEquals(
                ReplacementCompletionResult.Completed("legacy-7"),
                repository.completeReplacement("legacy-7"),
            )
            assertEquals("COMPLETE", bundle("legacy-7")?.state)
        }

    private suspend fun seedProtectedRows() {
        val evidence = evidenceFile()
        evidence.parentFile!!.mkdirs()
        evidence.writeText("field evidence")
        val photoId =
            database.pendingPhotoUploadDao().insert(
                PendingPhotoUploadEntity(
                    surveyId = SURVEY_ID,
                    inferenceResultId = 1L,
                    localFilePath = evidence.absolutePath,
                    capturedAt = CAPTURED_AT,
                ),
            )
        database.localDecisionDao().saveDecision(
            LocalDecisionEntity(
                inferenceResultId = 1L,
                surveyId = SURVEY_ID,
                verdict = true,
                detectionTagId = null,
                capturedAt = CAPTURED_AT,
                evidencePhotoRowId = photoId,
            ),
        )
    }

    private suspend fun assertProtectedRowsAndEvidence() {
        assertEquals(1, database.pendingPhotoUploadDao().getUnuploaded().size)
        assertEquals(1, database.localDecisionDao().getUnsynced().size)
        assertTrue(evidenceFile().isFile)
        assertEquals("field evidence", evidenceFile().readText())
    }

    private suspend fun seedOwnedManifest() {
        database.offlineBundleDao().insert(
            OfflineBundleEntity(
                manifestId = "owned",
                surveyId = SURVEY_ID,
                sourceVersion = "v1",
                state = "COMPLETE",
                totalBytes = 100L,
                tileCount = 1,
                satelliteRegionCount = 1,
                candidateCount = 1,
            ),
        )
        database.tileManifestDao().insertAll(
            listOf(oldTile().toEntity("owned", completed = true)),
        )
        database.candidateCropManifestDao().insertAll(
            listOf(
                CandidateCropManifestEntity(
                    manifestId = "owned",
                    surveyId = SURVEY_ID,
                    candidateId = 1L,
                    sourceVersion = "v1",
                    requestSignature = "crop:$SURVEY_ID:1",
                    completed = true,
                ),
            ),
        )
        database.satelliteRegionDao().upsert(
            SatelliteRegionEntity(
                manifestId = "owned",
                surveyId = SURVEY_ID,
                sourceVersion = "v1",
                signature = "old-region",
                completed = true,
            ),
        )
    }

    private suspend fun requiredCount(manifestId: String): Int =
        database.tileManifestDao().getForManifest(manifestId).size +
            database.candidateCropManifestDao().getForManifest(manifestId).size +
            database.satelliteRegionDao().getForManifest(manifestId).size

    private suspend fun bundle(manifestId: String) =
        database.offlineBundleDao().getByManifestId(manifestId)

    private fun target() =
        ReplacementTarget(
            surveyId = SURVEY_ID,
            sourceVersion = "v2",
            radiusMetres = 150.0,
            minZoom = 18,
            maxZoom = 22,
            candidateCount = 2,
        )

    private fun classification(
        retained: Set<PreDownloadTargetKey> = emptySet(),
        missing: Set<PreDownloadTargetKey> = emptySet(),
        obsolete: Set<PreDownloadPruneKey> = emptySet(),
        clearCommands: List<PreDownloadClearCommand> = emptyList(),
    ) = ReplacementClassification(
        retained = retained,
        missing = missing,
        obsolete = obsolete,
        clearCommands = clearCommands,
        confidentlyDeletableBytes = 0L,
    )

    private fun oldTile() = tile(candidateId = 1L, sourceVersion = "v1")

    private fun newTile() = tile(candidateId = 2L, sourceVersion = "v2")

    private fun tile(
        candidateId: Long,
        sourceVersion: String,
    ) = PreDownloadTargetKey.Tile(
        surveyId = SURVEY_ID,
        candidateId = candidateId,
        sourceVersion = sourceVersion,
        radiusMetres = 15.0,
        zoom = 20,
        x = 1,
        y = 1,
        kind = "GEOTIFF",
        expectedFormat = "PNG",
    )

    private fun newCrop() =
        PreDownloadTargetKey.Crop(
            surveyId = SURVEY_ID,
            candidateId = 2L,
            sourceVersion = "v2",
            requestSignature = "crop:$SURVEY_ID:2",
        )

    private fun PreDownloadTargetKey.Tile.toPruneKey() =
        PreDownloadPruneKey.Geotiff(surveyId, candidateId, zoom, x, y)

    private fun PreDownloadTargetKey.Tile.toEntity(
        manifestId: String,
        completed: Boolean,
    ) = TileManifestEntity(
        manifestId = manifestId,
        surveyId = surveyId,
        candidateId = candidateId,
        sourceVersion = sourceVersion,
        radiusMetres = radiusMetres,
        zoom = zoom,
        x = x,
        y = y,
        kind = kind,
        expectedFormat = expectedFormat,
        completed = completed,
        bytes = PNG.size.toLong(),
    )

    private fun writeTile(key: PreDownloadTargetKey.Tile) {
        tileStore.write(
            key.surveyId,
            key.candidateId,
            key.zoom,
            key.x,
            key.y,
            PNG,
        )
    }

    private fun writeCrop(candidateId: Long) {
        val result = images.writeCrop(SURVEY_ID, candidateId, JPEG)
        assertTrue(result is CropWriteResult.Success)
    }

    private fun createOwnerTempFiles() {
        val tileDirectory = tileStore.surveyTilesDirectory(SURVEY_ID)
        tileDirectory.mkdirs()
        File(tileDirectory, "orphan.tmp").writeText("temporary")
        val cropDirectory = File(context.filesDir, "crops/$SURVEY_ID")
        cropDirectory.mkdirs()
        File(cropDirectory, "orphan.tmp.jpg").writeText("temporary")
    }

    private fun evidenceFile() = File(root, "evidence/photo.jpg")

    private class FakeOfflineRegionSource : OfflineRegionSource {
        var regions: List<OfflineRegionHandle> = emptyList()

        override fun createOfflineRegion(
            definition: OfflineRegionTilePyramidDefinition,
            callback: (Result<OfflineRegionHandle>) -> Unit,
        ) {
            callback(Result.failure(UnsupportedOperationException()))
        }

        override fun getOfflineRegions(callback: (Result<List<OfflineRegionHandle>>) -> Unit) {
            callback(Result.success(regions))
        }
    }

    private class FakeOfflineRegionHandle(
        override val identifier: Long,
        override var metadata: ByteArray?,
    ) : OfflineRegionHandle {
        override fun setOfflineRegionObserver(observer: OfflineRegionObserver) = Unit

        override fun setOfflineRegionDownloadState(state: OfflineRegionDownloadState) = Unit

        override fun purge(callback: AsyncOperationResultCallback) {
            callback.run(ExpectedFactory.createNone())
        }

        override fun setMetadata(
            metadata: ByteArray,
            callback: AsyncOperationResultCallback,
        ) {
            this.metadata = metadata
            callback.run(ExpectedFactory.createNone())
        }
    }

    companion object {
        private const val SURVEY_ID = 42L
        private const val CAPTURED_AT = "2026-09-18T00:00:00Z"

        private val PNG =
            Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk" +
                    "YAAAAAYAAjCB0C8AAAAASUVORK5CYII=",
            )

        private val JPEG =
            byteArrayOf(
                0xFF.toByte(),
                0xD8.toByte(),
                0xFF.toByte(),
                0xC0.toByte(),
                0x00,
                0x0B,
                0x08,
                0x00,
                0x01,
                0x00,
                0x01,
                0x01,
                0x01,
                0x11,
                0x00,
            )
    }
}
