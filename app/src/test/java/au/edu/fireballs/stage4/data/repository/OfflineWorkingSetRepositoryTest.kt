package au.edu.fireballs.stage4.data.repository

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import au.edu.fireballs.stage4.data.local.OfflineBundleEntity
import au.edu.fireballs.stage4.data.local.SatelliteRegionEntity
import au.edu.fireballs.stage4.data.local.Stage4Database
import au.edu.fireballs.stage4.data.tiles.OfflineRegionHandle
import au.edu.fireballs.stage4.data.tiles.OfflineRegionPurgeResult
import au.edu.fireballs.stage4.data.tiles.OfflineRegionRetention
import au.edu.fireballs.stage4.data.tiles.OfflineRegionSource
import au.edu.fireballs.stage4.data.tiles.OfflineRegionWrapper
import au.edu.fireballs.stage4.data.tiles.TileStore
import au.edu.fireballs.stage4.data.tiles.encodeOfflineRegionTarget
import com.mapbox.bindgen.ExpectedFactory
import com.mapbox.maps.AsyncOperationResultCallback
import com.mapbox.maps.OfflineRegionDownloadState
import com.mapbox.maps.OfflineRegionObserver
import com.mapbox.maps.OfflineRegionStatus
import com.mapbox.maps.OfflineRegionTilePyramidDefinition
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.nio.file.Files
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
class OfflineWorkingSetRepositoryTest {
    private lateinit var database: Stage4Database
    private lateinit var tileStore: TileStore
    private lateinit var images: CandidateImageRepository
    private lateinit var source: FakeOfflineRegionSource
    private lateinit var repository: OfflineWorkingSetRepository
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database =
            Room
                .inMemoryDatabaseBuilder(context, Stage4Database::class.java)
                .allowMainThreadQueries()
                .build()
        tileStore = TileStore(Files.createTempDirectory("working-set-tiles").toFile())
        images = CandidateImageRepository(context, "https://example.invalid")
        source = FakeOfflineRegionSource()
        repository = createRepository(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        database.close()
        context.filesDir.resolve("crops").deleteRecursively()
    }

    @Test
    fun beginReplacementCommitsGraphBeforeReturningAndDoesNotDeleteOwners() =
        runTest {
            val classification =
                classification(
                    retained = setOf(tile(1L)),
                    missing = setOf(crop(2L), satellite("new")),
                )

            val result =
                repository.beginReplacement(
                    target(candidateCount = 2),
                    classification,
                    "manifest-new",
                )

            val session = (result as ReplacementBeginResult.Started).session
            assertEquals(WorkingSetState.REPLACING, session.state)
            assertEquals(3, session.requiredCount)
            assertEquals("REPLACING", bundle("manifest-new")?.state)
            assertEquals(1, database.tileManifestDao().getForManifest("manifest-new").size)
            assertEquals(
                1,
                database.candidateCropManifestDao().getForManifest("manifest-new").size,
            )
            assertEquals(1, database.satelliteRegionDao().getForManifest("manifest-new").size)
            assertTrue(source.purged.isEmpty())
            assertTrue(tileStore.contains(1L, 1L, 20, 1, 1).not())
        }

    @Test
    fun conflictingManifestIsRejectedWithoutMutatingActiveGraph() =
        runTest {
            repository.beginReplacement(
                target(),
                classification(missing = setOf(tile(1L))),
                "active",
            )

            val result =
                repository.beginReplacement(
                    target(sourceVersion = "changed"),
                    classification(missing = setOf(tile(2L))),
                    "other",
                )

            assertEquals(
                ReplacementBeginResult.ConflictingReplacement("active"),
                result,
            )
            assertNull(bundle("other"))
            assertEquals(1, database.tileManifestDao().getForManifest("active").size)
        }

    @Test
    fun pruneTransitionsBeforeDeletionAndRemovesMetadataOnlyAfterSuccess() =
        runTest {
            seedOldManifest()
            writeTile(tile(7L))
            val started =
                repository.beginReplacement(
                    target(),
                    classification(
                        missing = setOf(tile(1L)),
                        obsolete =
                            setOf(
                                PreDownloadPruneKey.Geotiff(1L, 7L, 20, 1, 1),
                            ),
                    ),
                    "new",
                ) as ReplacementBeginResult.Started

            val result = repository.pruneObsolete(started.session)

            assertTrue(result is ReplacementPruneResult.Completed)
            assertEquals("INCOMPLETE", bundle("new")?.state)
            assertNull(bundle("old"))
            assertTrue(database.tileManifestDao().getForManifest("old").isEmpty())
            assertFalse(tileStore.contains(1L, 7L, 20, 1, 1))
        }

    @Test
    fun failedLocalDeletionStopsTransferAndLeavesIncomplete() =
        runTest {
            seedOldManifest()
            val invalidPayload =
                tileStore
                    .surveyTilesDirectory(1L)
                    .resolve("7/20/1/1.png")
            invalidPayload.mkdirs()
            val started =
                repository.beginReplacement(
                    target(),
                    classification(
                        missing = setOf(tile(1L)),
                        obsolete =
                            setOf(
                                PreDownloadPruneKey.Geotiff(1L, 7L, 20, 1, 1),
                                PreDownloadPruneKey.Crop(1L, 7L),
                            ),
                    ),
                    "new",
                ) as ReplacementBeginResult.Started

            val result = repository.pruneObsolete(started.session)

            assertTrue(result is ReplacementPruneResult.LocalDeletionFailed)
            assertEquals("INCOMPLETE", bundle("new")?.state)
            assertTrue(database.tileManifestDao().getForManifest("old").isNotEmpty())
            assertTrue(database.candidateCropManifestDao().getForManifest("old").isNotEmpty())
        }

    @Test
    fun satellitePurgeIsPendingBeforeCallAndRemovedAfterConfirmedAbsence() =
        runTest {
            val wrapper = missingOfflineRegionWrapper()
            every { wrapper.purgeExact(any(), any()) } answers {
                val target = firstArg<PreDownloadTargetKey.Satellite>()
                val callback = secondArg<(OfflineRegionPurgeResult) -> Unit>()
                val cursor =
                    database.openHelper.readableDatabase.query(
                        "SELECT COUNT(*) FROM satellite_region WHERE pendingDeletion = 1",
                    )
                cursor.use {
                    assertTrue(it.moveToFirst())
                    assertTrue(it.getInt(0) > 0)
                }
                callback(OfflineRegionPurgeResult.ConfirmedAbsent(target))
            }
            repository = createRepository(UnconfinedTestDispatcher(), wrapper)
            seedOldManifest()
            val started =
                repository.beginReplacement(
                    target(),
                    classification(
                        missing = setOf(tile(1L)),
                        obsolete =
                            setOf(
                                PreDownloadPruneKey.Satellite(1L, "old-region"),
                            ),
                    ),
                    "new",
                ) as ReplacementBeginResult.Started
            val result = repository.pruneObsolete(started.session)

            assertTrue(result is ReplacementPruneResult.Completed)
            assertTrue(database.satelliteRegionDao().getPendingDeletion().isEmpty())
        }

    @Test
    fun itemCompletionRequiresFinalPayloadValidation() =
        runTest {
            val key = tile(1L)
            repository.beginReplacement(
                target(),
                classification(missing = setOf(key)),
                "manifest",
            )

            assertEquals(
                ReplacementItemResult.InvalidPayload(key),
                repository.markItemComplete("manifest", key),
            )
            assertEquals(1, database.tileManifestDao().countIncomplete("manifest"))
            writeTile(key)
            assertEquals(
                ReplacementItemResult.Completed(key),
                repository.markItemComplete("manifest", key),
            )
            assertEquals(0, database.tileManifestDao().countIncomplete("manifest"))
        }

    @Test
    fun completionRefusesMissingAndOnlyCompleteClearsActiveState() =
        runTest {
            val key = tile(1L)
            repository.beginReplacement(
                target(),
                classification(missing = setOf(key)),
                "manifest",
            )

            assertEquals(
                ReplacementCompletionResult.Refused(1),
                repository.completeReplacement("manifest"),
            )
            writeTile(key)
            repository.markItemComplete("manifest", key)

            assertEquals(
                ReplacementCompletionResult.Completed("manifest"),
                repository.completeReplacement("manifest"),
            )
            assertEquals("COMPLETE", bundle("manifest")?.state)
        }

    @Test
    fun incompleteSatelliteRegionIsClassifiedMissingAndRefusesCompletion() =
        runTest {
            val key = satellite("incomplete")
            source.regions =
                listOf(
                    FakeOfflineRegionHandle(
                        encodeOfflineRegionTarget(key),
                        Result.success(incompleteSatelliteStatus()),
                    ),
                )

            val inspected =
                withMainIdle {
                    repository.inspectTarget(
                        PreDownloadTargetSet(emptyList(), emptyList(), listOf(key)),
                    )
                }
            assertEquals(setOf(key), inspected.missing)

            repository.beginReplacement(target(), inspected, "incomplete")
            assertEquals(
                ReplacementCompletionResult.Refused(1),
                withMainIdle { repository.completeReplacement("incomplete") },
            )
            assertEquals("REPLACING", bundle("incomplete")?.state)
        }

    @Test
    fun markItemCompleteReturnsInvalidPayloadForIncompleteSatellite() =
        runTest {
            val key = satellite("incomplete-item")
            source.regions =
                listOf(
                    FakeOfflineRegionHandle(
                        encodeOfflineRegionTarget(key),
                        Result.success(incompleteSatelliteStatus()),
                    ),
                )
            repository.beginReplacement(
                target(),
                classification(missing = setOf(key)),
                "incomplete-item",
            )

            assertEquals(
                ReplacementItemResult.InvalidPayload(key),
                withMainIdle { repository.markItemComplete("incomplete-item", key) },
            )
        }

    @Test
    fun markItemCompleteReturnsCompletedForCompleteSatellite() =
        runTest {
            val key = satellite("complete-item")
            source.regions =
                listOf(FakeOfflineRegionHandle(encodeOfflineRegionTarget(key)))
            repository.beginReplacement(
                target(),
                classification(missing = setOf(key)),
                "complete-item",
            )

            assertEquals(
                ReplacementItemResult.Completed(key),
                withMainIdle { repository.markItemComplete("complete-item", key) },
            )
        }

    @Test
    fun completeSatelliteRegionPermitsReplacementCompletion() =
        runTest {
            val key = satellite("complete-replacement")
            source.regions =
                listOf(FakeOfflineRegionHandle(encodeOfflineRegionTarget(key)))
            repository.beginReplacement(
                target(),
                classification(missing = setOf(key)),
                "complete-replacement",
            )

            assertEquals(
                ReplacementCompletionResult.Completed("complete-replacement"),
                withMainIdle { repository.completeReplacement("complete-replacement") },
            )
            assertEquals("COMPLETE", bundle("complete-replacement")?.state)
        }

    @Test
    fun purgeSatelliteTargetReturnsConfirmedAbsentWhenNoOwnedRegion() =
        runTest {
            val key = satellite("absent")

            assertEquals(
                OfflineRegionPurgeResult.ConfirmedAbsent(key),
                withMainIdle { repository.purgeSatelliteTarget(key) },
            )
        }

    @Test
    fun purgeSatelliteTargetReturnsPurgedWhenOwnedRegionPresent() =
        runTest {
            val key = satellite("owned")
            source.regions =
                listOf(FakeOfflineRegionHandle(encodeOfflineRegionTarget(key)))

            assertEquals(
                OfflineRegionPurgeResult.Purged(key),
                withMainIdle { repository.purgeSatelliteTarget(key) },
            )
            assertEquals(listOf(1L), source.purged)
        }

    @Test
    fun claimChangesCannotMutatePersistedManifest() =
        runTest {
            val session =
                (
                    repository.beginReplacement(
                        target(),
                        classification(missing = setOf(tile(41L))),
                        "immutable",
                    ) as ReplacementBeginResult.Started
                ).session
            database.claimDao().deleteAll()

            assertEquals("immutable", session.manifestId)
            assertEquals(setOf(tile(41L)), session.missing)
            assertEquals(1, database.tileManifestDao().getForManifest("immutable").size)
        }

    private suspend fun <T> withMainIdle(block: suspend () -> T): T =
        coroutineScope {
            val result = async(UnconfinedTestDispatcher()) { block() }
            while (!result.isCompleted) {
                shadowOf(Looper.getMainLooper()).idle()
                yield()
            }
            result.await()
        }

    private fun createRepository(
        dispatcher: CoroutineDispatcher,
        wrapper: OfflineRegionWrapper =
            OfflineRegionWrapper(
                source = source,
                mainHandler = Handler(Looper.getMainLooper()),
            ),
    ) = OfflineWorkingSetRepository(
        database = database,
        offlineBundleDao = database.offlineBundleDao(),
        tileManifestDao = database.tileManifestDao(),
        candidateCropManifestDao = database.candidateCropManifestDao(),
        satelliteRegionDao = database.satelliteRegionDao(),
        tileStore = tileStore,
        candidateImageRepository = images,
        offlineRegionWrapper = wrapper,
        ioDispatcher = dispatcher,
    )

    private fun missingOfflineRegionWrapper(): OfflineRegionWrapper {
        val wrapper = mockk<OfflineRegionWrapper>()
        every { wrapper.retainExact(any(), any()) } answers {
            val targets = firstArg<List<PreDownloadTargetKey.Satellite>>()
            val callback =
                secondArg<(Result<List<OfflineRegionRetention>>) -> Unit>()
            callback(
                Result.success(
                    targets.map { OfflineRegionRetention.Missing(it) },
                ),
            )
        }
        return wrapper
    }

    private suspend fun seedOldManifest() {
        database.offlineBundleDao().insert(
            OfflineBundleEntity(
                manifestId = "old",
                surveyId = 1L,
                sourceVersion = "v1",
                state = "COMPLETE",
                totalBytes = 10,
                tileCount = 1,
                satelliteRegionCount = 1,
                candidateCount = 1,
            ),
        )
        database.tileManifestDao().insertAll(
            listOf(
                au.edu.fireballs.stage4.data.local.TileManifestEntity(
                    manifestId = "old",
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
                    bytes = 10,
                ),
            ),
        )
        database.candidateCropManifestDao().insertAll(
            listOf(
                au.edu.fireballs.stage4.data.local.CandidateCropManifestEntity(
                    manifestId = "old",
                    surveyId = 1L,
                    candidateId = 7L,
                    sourceVersion = "v1",
                    requestSignature = "crop:1:7",
                    completed = true,
                ),
            ),
        )
        database.satelliteRegionDao().upsert(
            SatelliteRegionEntity(
                manifestId = "old",
                surveyId = 1L,
                sourceVersion = "v1",
                signature = "old-region",
                completed = true,
            ),
        )
    }

    private suspend fun bundle(id: String) = database.offlineBundleDao().getByManifestId(id)

    private fun target(
        sourceVersion: String = "v2",
        candidateCount: Int = 1,
    ) = ReplacementTarget(1L, sourceVersion, 150.0, 18, 22, candidateCount)

    private fun classification(
        retained: Set<PreDownloadTargetKey> = emptySet(),
        missing: Set<PreDownloadTargetKey> = emptySet(),
        obsolete: Set<PreDownloadPruneKey> = emptySet(),
    ) = ReplacementClassification(retained, missing, obsolete, emptyList(), 0)

    private fun tile(candidateId: Long) =
        PreDownloadTargetKey.Tile(
            1L,
            candidateId,
            "v2",
            15.0,
            20,
            1,
            1,
            "GEOTIFF",
            "PNG",
        )

    private fun crop(candidateId: Long) =
        PreDownloadTargetKey.Crop(1L, candidateId, "v2", "crop:1:$candidateId")

    private fun satellite(signature: String) = PreDownloadTargetKey.Satellite(1L, "v2", signature)

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

    private fun createTileTemp() {
        val directory = tileStore.surveyTilesDirectory(1L)
        directory.mkdirs()
        directory.resolve("orphan.tmp").writeText("temporary")
    }

    private fun createCropTemp() {
        val directory = context.filesDir.resolve("crops/1")
        directory.mkdirs()
        directory.resolve("orphan.tmp.jpg").writeText("temporary")
    }

    private class FakeOfflineRegionHandle(
        override val metadata: ByteArray,
        var statusResult: Result<OfflineRegionStatus> =
            Result.success(completeSatelliteStatus()),
        private val onPurge: (Long) -> Unit = {},
    ) : OfflineRegionHandle {
        override val identifier: Long = 1L

        override fun setOfflineRegionObserver(observer: OfflineRegionObserver) = Unit

        override fun setOfflineRegionDownloadState(state: OfflineRegionDownloadState) = Unit

        override fun purge(callback: AsyncOperationResultCallback) {
            onPurge(identifier)
            callback.run(ExpectedFactory.createNone())
        }

        override fun getStatus(callback: (Result<OfflineRegionStatus>) -> Unit) {
            callback(statusResult)
        }
    }

    private class FakeOfflineRegionSource : OfflineRegionSource {
        val purged = mutableListOf<Long>()
        var regions: List<OfflineRegionHandle> = emptyList()
        var onList: (() -> Unit)? = null

        override fun createOfflineRegion(
            definition: OfflineRegionTilePyramidDefinition,
            callback: (Result<OfflineRegionHandle>) -> Unit,
        ) = callback(Result.failure(UnsupportedOperationException()))

        override fun getOfflineRegions(callback: (Result<List<OfflineRegionHandle>>) -> Unit) {
            onList?.invoke()
            callback(
                Result.success(
                    regions.map { region ->
                        if (region is FakeOfflineRegionHandle) {
                            FakeOfflineRegionHandle(
                                metadata = region.metadata,
                                statusResult = region.statusResult,
                                onPurge = purged::add,
                            )
                        } else {
                            region
                        }
                    },
                ),
            )
        }
    }

    companion object {
        private fun completeSatelliteStatus(): OfflineRegionStatus =
            satelliteStatus(completed = 10, required = 10, precise = true)

        private fun incompleteSatelliteStatus(): OfflineRegionStatus =
            satelliteStatus(completed = 5, required = 10, precise = true)

        private fun satelliteStatus(
            completed: Long,
            required: Long,
            precise: Boolean,
        ): OfflineRegionStatus =
            OfflineRegionStatus(
                OfflineRegionDownloadState.ACTIVE,
                completed,
                0,
                completed,
                0,
                required,
                required,
                precise,
            )

        private val PNG =
            Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk" +
                    "YAAAAAYAAjCB0C8AAAAASUVORK5CYII=",
            )
    }
}
