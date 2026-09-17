package au.edu.fireballs.stage4.sync

import androidx.work.Data
import au.edu.fireballs.stage4.data.local.CandidateEntity
import au.edu.fireballs.stage4.data.local.ClaimEntity
import au.edu.fireballs.stage4.data.local.OfflineBundleEntity
import au.edu.fireballs.stage4.data.local.SurveyEntity
import au.edu.fireballs.stage4.data.local.TileManifestEntity
import au.edu.fireballs.stage4.data.local.dao.CandidateDao
import au.edu.fireballs.stage4.data.local.dao.ClaimDao
import au.edu.fireballs.stage4.data.local.dao.OfflineBundleDao
import au.edu.fireballs.stage4.data.local.dao.SurveyDao
import au.edu.fireballs.stage4.data.local.dao.TileManifestDao
import au.edu.fireballs.stage4.data.remote.Stage4Service
import au.edu.fireballs.stage4.data.remote.TileService
import au.edu.fireballs.stage4.data.remote.dto.ClaimDto
import au.edu.fireballs.stage4.data.remote.dto.ListClaimsResponseDto
import au.edu.fireballs.stage4.data.repository.CandidateImageRepository
import au.edu.fireballs.stage4.data.repository.ClaimRepository
import au.edu.fireballs.stage4.data.repository.Stage4Repository
import au.edu.fireballs.stage4.data.tiles.Bbox
import au.edu.fireballs.stage4.data.tiles.GeotiffRadiusRepository
import au.edu.fireballs.stage4.data.tiles.LocalFileRasterTileProvider
import au.edu.fireballs.stage4.data.tiles.LowZoomCompositor
import au.edu.fireballs.stage4.data.tiles.NoOpSatelliteRegionStore
import au.edu.fireballs.stage4.data.tiles.OfflineBundleRepository
import au.edu.fireballs.stage4.data.tiles.OfflineManagerWrapper
import au.edu.fireballs.stage4.data.tiles.TileCoord
import au.edu.fireballs.stage4.data.tiles.TileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import retrofit2.Response
import java.io.File
import java.nio.file.Files

class PreDownloadWorkerTest {
    private val noOpCompositor =
        object : LowZoomCompositor {
            override fun compose(
                surveyId: Long,
                candidateId: Long,
                parent: TileCoord,
            ): ByteArray = LocalFileRasterTileProvider.TRANSPARENT_PNG

            override fun parentTiles(
                surveyId: Long,
                candidateId: Long,
                zoom: Int,
            ): List<TileCoord> = emptyList()
        }

    private val testDispatcher = Dispatchers.IO
    private val candidateImageRepository =
        mock(CandidateImageRepository::class.java)
    private val geotiffRadiusRepository =
        mock(GeotiffRadiusRepository::class.java).also {
            `when`(it.getRadiusMeters()).thenReturn(15.0f)
        }
    private val satelliteRegionStore = NoOpSatelliteRegionStore

    private fun candidate(
        id: Long,
        lat: Double,
        lon: Double,
    ): CandidateEntity =
        CandidateEntity(
            inferenceResultId = id,
            surveyId = SURVEY_ID,
            imageId = id,
            imageFilename = "img-$id.png",
            imageWidth = 100,
            imageHeight = 100,
            geoCentroidLat = lat,
            geoCentroidLon = lon,
            geoAreaJson = "[]",
            boxX = 10,
            boxY = 10,
            boxW = 20,
            boxH = 20,
            confidence = 0.9f,
            sizeMw = null,
            sizeMh = null,
            isClaimedByMe = true,
            claimOwnerUsername = null,
        )

    private fun claim(id: Long): ClaimEntity =
        ClaimEntity(
            inferenceResultId = id,
            surveyId = SURVEY_ID,
            userId = 2L,
            username = "me",
            claimedAt = "2026-01-01T00:00:00Z",
            isMine = true,
            isActive = true,
        )

    @Test
    fun successfulDownloadRunsFullOrchestration() =
        runTest {
            val candidateDao = FakeCandidateDao(listOf(candidate(1L, 0.0, 0.0)))
            val claimDao = FakeClaimDao()
            val surveyDao = FakeSurveyDao(latestTaskCreated = "2026-01-01T00:00:00Z")
            val tileStore = TileStore(Files.createTempDirectory("tiles").toFile())
            val offlineBundleDao = FakeOfflineBundleDao()
            val offlineBundleRepository =
                OfflineBundleRepository(
                    tileStore,
                    FakeTileManifestDao(),
                    offlineBundleDao,
                    testDispatcher,
                )

            val stage4Service = mock(Stage4Service::class.java)
            `when`(stage4Service.getClaims(SURVEY_ID.toString(), true))
                .thenReturn(
                    ListClaimsResponseDto(
                        listOf(
                            ClaimDto(
                                inferenceResultId = 1L,
                                userId = 2L,
                                username = "me",
                                claimedAt = "2026-01-01T00:00:00Z",
                                isMe = true,
                            ),
                        ),
                    ),
                )
            val claimRepository =
                ClaimRepository(
                    stage4Service,
                    claimDao,
                    testDispatcher,
                )

            val stage4Repository = mock(Stage4Repository::class.java)
            `when`(stage4Repository.fetchLatestTaskCreated(SURVEY_ID))
                .thenReturn("2026-01-01T00:00:00Z")

            val tileService = mock(TileService::class.java)
            val tileBody =
                byteArrayOf(1, 2, 3)
                    .toResponseBody("image/png".toMediaType())
            `when`(
                tileService.getCandidateTile(
                    anyLong(),
                    anyLong(),
                    anyInt(),
                    anyInt(),
                    anyInt(),
                ),
            ).thenReturn(Response.success(tileBody))
            val cropBody =
                byteArrayOf(9, 9, 9)
                    .toResponseBody("image/jpeg".toMediaType())
            `when`(tileService.getCandidateCrop(anyLong()))
                .thenReturn(Response.success(cropBody))

            val offlineManagerWrapper = mock(OfflineManagerWrapper::class.java)
            doAnswer { invocation ->
                val completionCb =
                    invocation.getArgument<(Result<Unit>) -> Unit>(4)
                completionCb(Result.success(Unit))
            }.`when`(offlineManagerWrapper)
                .splitAndDownload(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )

            val filesDir = Files.createTempDirectory("crops").toFile()
            val orchestrator =
                PreDownloadOrchestrator(
                    claimRepository = claimRepository,
                    stage4Repository = stage4Repository,
                    candidateDao = candidateDao,
                    claimDao = claimDao,
                    surveyDao = surveyDao,
                    tileStore = tileStore,
                    lowZoomCompositor = noOpCompositor,
                    tileService = tileService,
                    offlineManagerWrapper = offlineManagerWrapper,
                    offlineBundleRepository = offlineBundleRepository,
                    candidateImageRepository = candidateImageRepository,
                    geotiffRadiusRepository = geotiffRadiusRepository,
                    satelliteRegionStore = satelliteRegionStore,
                    filesDir = filesDir,
                    ioDispatcher = testDispatcher,
                    freeBytes = { Long.MAX_VALUE },
                )

            val outcome = orchestrator.run(SURVEY_ID, BUFFER_METERS) {}

            assertTrue("Expected success but got $outcome", outcome is PreDownloadOutcome.Success)
            val output = (outcome as PreDownloadOutcome.Success).outputData
            assertFalse(
                output.getBoolean(
                    PreDownloadOrchestrator.KEY_RE_DOWNLOAD_RECOMMENDED,
                    true,
                ),
            )
            assertEquals(1, output.getInt(PreDownloadOrchestrator.KEY_CANDIDATE_COUNT, -1))
            assertEquals(1, output.getInt(PreDownloadOrchestrator.KEY_SATELLITE_REGION_COUNT, -1))
            assertTrue(output.getInt(PreDownloadOrchestrator.KEY_TILE_COUNT, -1) > 0)
            assertEquals(1, output.getInt(PreDownloadOrchestrator.KEY_CROP_COUNT, -1))

            verify(stage4Service).getClaims(SURVEY_ID.toString(), true)
            verify(offlineManagerWrapper).splitAndDownload(
                any(),
                any(),
                any(),
                any(),
                any(),
            )
            verify(tileService, atLeastOnce())
                .getCandidateTile(
                    anyLong(),
                    anyLong(),
                    anyInt(),
                    anyInt(),
                    anyInt(),
                )
            verify(tileService).getCandidateCrop(1L)

            assertTrue(tileStore.hasCandidate(SURVEY_ID, 1L))
            assertTrue(File(filesDir, "crops/$SURVEY_ID/1.jpg").isFile)

            assertEquals(1, offlineBundleDao.inserted.size)
            val bundle = offlineBundleDao.inserted.single()
            assertEquals(SURVEY_ID, bundle.surveyId)
            assertEquals(1, bundle.candidateCount)
            assertEquals(1, bundle.satelliteRegionCount)
            assertEquals(BUFFER_METERS, bundle.bufferMeters)
            assertTrue(bundle.totalBytes > 0)
        }

    @Test
    fun staleTaskDetectedWhenServerTaskDiffers() =
        runTest {
            val candidateDao = FakeCandidateDao(listOf(candidate(1L, 0.0, 0.0)))
            val claimDao = FakeClaimDao()
            val surveyDao = FakeSurveyDao(latestTaskCreated = "2026-01-01T00:00:00Z")
            val tileStore = TileStore(Files.createTempDirectory("tiles").toFile())
            val offlineBundleDao = FakeOfflineBundleDao()
            val offlineBundleRepository =
                OfflineBundleRepository(
                    tileStore,
                    FakeTileManifestDao(),
                    offlineBundleDao,
                    testDispatcher,
                )

            val stage4Service = mock(Stage4Service::class.java)
            `when`(stage4Service.getClaims(SURVEY_ID.toString(), true))
                .thenReturn(
                    ListClaimsResponseDto(
                        listOf(
                            ClaimDto(
                                inferenceResultId = 1L,
                                userId = 2L,
                                username = "me",
                                claimedAt = "2026-01-01T00:00:00Z",
                                isMe = true,
                            ),
                        ),
                    ),
                )
            val claimRepository =
                ClaimRepository(
                    stage4Service,
                    claimDao,
                    testDispatcher,
                )

            val stage4Repository = mock(Stage4Repository::class.java)
            `when`(stage4Repository.fetchLatestTaskCreated(SURVEY_ID))
                .thenReturn("2026-02-01T00:00:00Z")

            val tileService = mock(TileService::class.java)
            `when`(
                tileService.getCandidateTile(
                    anyLong(),
                    anyLong(),
                    anyInt(),
                    anyInt(),
                    anyInt(),
                ),
            ).thenReturn(
                Response.success(
                    byteArrayOf(1, 2, 3)
                        .toResponseBody("image/png".toMediaType()),
                ),
            )
            `when`(tileService.getCandidateCrop(anyLong()))
                .thenReturn(
                    Response.success(
                        byteArrayOf(9, 9, 9)
                            .toResponseBody("image/jpeg".toMediaType()),
                    ),
                )
            val offlineManagerWrapper = mock(OfflineManagerWrapper::class.java)
            doAnswer { invocation ->
                val completionCb =
                    invocation.getArgument<(Result<Unit>) -> Unit>(4)
                completionCb(Result.success(Unit))
            }.`when`(offlineManagerWrapper)
                .splitAndDownload(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )

            val filesDir = Files.createTempDirectory("crops").toFile()
            val orchestrator =
                PreDownloadOrchestrator(
                    claimRepository = claimRepository,
                    stage4Repository = stage4Repository,
                    candidateDao = candidateDao,
                    claimDao = claimDao,
                    surveyDao = surveyDao,
                    tileStore = tileStore,
                    lowZoomCompositor = noOpCompositor,
                    tileService = tileService,
                    offlineManagerWrapper = offlineManagerWrapper,
                    offlineBundleRepository = offlineBundleRepository,
                    candidateImageRepository = candidateImageRepository,
                    geotiffRadiusRepository = geotiffRadiusRepository,
                    satelliteRegionStore = satelliteRegionStore,
                    filesDir = filesDir,
                    ioDispatcher = testDispatcher,
                    freeBytes = { Long.MAX_VALUE },
                )

            val outcome = orchestrator.run(SURVEY_ID, BUFFER_METERS) {}

            assertTrue("Expected success but got $outcome", outcome is PreDownloadOutcome.Success)
            val output = (outcome as PreDownloadOutcome.Success).outputData
            assertTrue(
                output.getBoolean(
                    PreDownloadOrchestrator.KEY_RE_DOWNLOAD_RECOMMENDED,
                    false,
                ),
            )
        }

    @Test
    fun tileStoreQuotaExceededReturnsFailure() =
        runTest {
            val candidateDao = FakeCandidateDao(listOf(candidate(1L, 0.0, 0.0)))
            val claimDao = FakeClaimDao()
            val surveyDao = FakeSurveyDao(latestTaskCreated = null)
            val tileStore = TileStore(Files.createTempDirectory("tiles").toFile(), quotaBytes = 1L)
            val offlineBundleDao = FakeOfflineBundleDao()
            val offlineBundleRepository =
                OfflineBundleRepository(
                    tileStore,
                    FakeTileManifestDao(),
                    offlineBundleDao,
                    testDispatcher,
                )

            val stage4Service = mock(Stage4Service::class.java)
            `when`(stage4Service.getClaims(SURVEY_ID.toString(), true))
                .thenReturn(
                    ListClaimsResponseDto(
                        listOf(
                            ClaimDto(
                                inferenceResultId = 1L,
                                userId = 2L,
                                username = "me",
                                isMe = true,
                            ),
                        ),
                    ),
                )
            val claimRepository =
                ClaimRepository(
                    stage4Service,
                    claimDao,
                    testDispatcher,
                )

            val stage4Repository = mock(Stage4Repository::class.java)
            `when`(stage4Repository.fetchLatestTaskCreated(SURVEY_ID)).thenReturn(null)

            val tileService = mock(TileService::class.java)
            val offlineManagerWrapper = mock(OfflineManagerWrapper::class.java)

            val filesDir = Files.createTempDirectory("crops").toFile()
            val orchestrator =
                PreDownloadOrchestrator(
                    claimRepository = claimRepository,
                    stage4Repository = stage4Repository,
                    candidateDao = candidateDao,
                    claimDao = claimDao,
                    surveyDao = surveyDao,
                    tileStore = tileStore,
                    lowZoomCompositor = noOpCompositor,
                    tileService = tileService,
                    offlineManagerWrapper = offlineManagerWrapper,
                    offlineBundleRepository = offlineBundleRepository,
                    candidateImageRepository = candidateImageRepository,
                    geotiffRadiusRepository = geotiffRadiusRepository,
                    satelliteRegionStore = satelliteRegionStore,
                    filesDir = filesDir,
                    ioDispatcher = testDispatcher,
                    freeBytes = { Long.MAX_VALUE },
                )

            val outcome = orchestrator.run(SURVEY_ID, BUFFER_METERS) {}

            assertTrue("Expected failure but got $outcome", outcome is PreDownloadOutcome.Failure)
            val output = (outcome as PreDownloadOutcome.Failure).outputData
            assertEquals(
                "Insufficient storage",
                output.getString(PreDownloadOrchestrator.KEY_ERROR),
            )
            verifyNoInteractions(offlineManagerWrapper)
            verifyNoInteractions(tileService)
            assertTrue(offlineBundleDao.inserted.isEmpty())
        }

    @Test
    fun insufficientStorageReturnsFailure() =
        runTest {
            val candidateDao = FakeCandidateDao(listOf(candidate(1L, 0.0, 0.0)))
            val claimDao = FakeClaimDao()
            val surveyDao = FakeSurveyDao(latestTaskCreated = null)
            val tileStore = TileStore(Files.createTempDirectory("tiles").toFile())
            val offlineBundleDao = FakeOfflineBundleDao()
            val offlineBundleRepository =
                OfflineBundleRepository(
                    tileStore,
                    FakeTileManifestDao(),
                    offlineBundleDao,
                    testDispatcher,
                )

            val stage4Service = mock(Stage4Service::class.java)
            `when`(stage4Service.getClaims(SURVEY_ID.toString(), true))
                .thenReturn(
                    ListClaimsResponseDto(
                        listOf(
                            ClaimDto(
                                inferenceResultId = 1L,
                                userId = 2L,
                                username = "me",
                                isMe = true,
                            ),
                        ),
                    ),
                )
            val claimRepository =
                ClaimRepository(
                    stage4Service,
                    claimDao,
                    testDispatcher,
                )

            val stage4Repository = mock(Stage4Repository::class.java)
            `when`(stage4Repository.fetchLatestTaskCreated(SURVEY_ID)).thenReturn(null)

            val tileService = mock(TileService::class.java)
            val offlineManagerWrapper = mock(OfflineManagerWrapper::class.java)

            val filesDir = Files.createTempDirectory("crops").toFile()
            val orchestrator =
                PreDownloadOrchestrator(
                    claimRepository = claimRepository,
                    stage4Repository = stage4Repository,
                    candidateDao = candidateDao,
                    claimDao = claimDao,
                    surveyDao = surveyDao,
                    tileStore = tileStore,
                    lowZoomCompositor = noOpCompositor,
                    tileService = tileService,
                    offlineManagerWrapper = offlineManagerWrapper,
                    offlineBundleRepository = offlineBundleRepository,
                    candidateImageRepository = candidateImageRepository,
                    geotiffRadiusRepository = geotiffRadiusRepository,
                    satelliteRegionStore = satelliteRegionStore,
                    filesDir = filesDir,
                    ioDispatcher = testDispatcher,
                    freeBytes = { 1L },
                )

            val outcome = orchestrator.run(SURVEY_ID, BUFFER_METERS) {}

            assertTrue("Expected failure but got $outcome", outcome is PreDownloadOutcome.Failure)
            val output = (outcome as PreDownloadOutcome.Failure).outputData
            assertEquals(
                "Insufficient storage",
                output.getString(PreDownloadOrchestrator.KEY_ERROR),
            )
            verifyNoInteractions(offlineManagerWrapper)
            verifyNoInteractions(tileService)
            assertTrue(offlineBundleDao.inserted.isEmpty())
        }

    @Test
    fun oneCandidateClusterProducesBufferedSatelliteBounds() =
        runTest {
            val candidateDao = FakeCandidateDao(listOf(candidate(1L, 0.0, 0.0)))
            val claimDao = FakeClaimDao()
            val surveyDao = FakeSurveyDao(latestTaskCreated = null)
            val tileStore = TileStore(Files.createTempDirectory("tiles").toFile())
            val offlineBundleDao = FakeOfflineBundleDao()
            val offlineBundleRepository =
                OfflineBundleRepository(
                    tileStore,
                    FakeTileManifestDao(),
                    offlineBundleDao,
                    testDispatcher,
                )

            val stage4Service = mock(Stage4Service::class.java)
            `when`(stage4Service.getClaims(SURVEY_ID.toString(), true))
                .thenReturn(
                    ListClaimsResponseDto(
                        listOf(
                            ClaimDto(
                                inferenceResultId = 1L,
                                userId = 2L,
                                username = "me",
                                claimedAt = "2026-01-01T00:00:00Z",
                                isMe = true,
                            ),
                        ),
                    ),
                )
            val claimRepository =
                ClaimRepository(
                    stage4Service,
                    claimDao,
                    testDispatcher,
                )

            val stage4Repository = mock(Stage4Repository::class.java)
            `when`(stage4Repository.fetchLatestTaskCreated(SURVEY_ID)).thenReturn(null)

            val tileService = mock(TileService::class.java)
            `when`(
                tileService.getCandidateTile(
                    anyLong(),
                    anyLong(),
                    anyInt(),
                    anyInt(),
                    anyInt(),
                ),
            ).thenReturn(
                Response.success(
                    byteArrayOf(1, 2, 3)
                        .toResponseBody("image/png".toMediaType()),
                ),
            )
            `when`(tileService.getCandidateCrop(anyLong()))
                .thenReturn(
                    Response.success(
                        byteArrayOf(9, 9, 9)
                            .toResponseBody("image/jpeg".toMediaType()),
                    ),
                )

            val capturedBboxes = mutableListOf<List<Bbox>>()
            val offlineManagerWrapper = mock(OfflineManagerWrapper::class.java)
            doAnswer { invocation ->
                capturedBboxes.add(invocation.getArgument(0))
                val completionCb =
                    invocation.getArgument<(Result<Unit>) -> Unit>(4)
                completionCb(Result.success(Unit))
            }.`when`(offlineManagerWrapper)
                .splitAndDownload(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )

            val filesDir = Files.createTempDirectory("crops").toFile()
            val orchestrator =
                PreDownloadOrchestrator(
                    claimRepository = claimRepository,
                    stage4Repository = stage4Repository,
                    candidateDao = candidateDao,
                    claimDao = claimDao,
                    surveyDao = surveyDao,
                    tileStore = tileStore,
                    lowZoomCompositor = noOpCompositor,
                    tileService = tileService,
                    offlineManagerWrapper = offlineManagerWrapper,
                    offlineBundleRepository = offlineBundleRepository,
                    candidateImageRepository = candidateImageRepository,
                    geotiffRadiusRepository = geotiffRadiusRepository,
                    satelliteRegionStore = satelliteRegionStore,
                    filesDir = filesDir,
                    ioDispatcher = testDispatcher,
                    freeBytes = { Long.MAX_VALUE },
                )

            val outcome = orchestrator.run(SURVEY_ID, BUFFER_METERS) {}

            assertTrue("Expected success but got $outcome", outcome is PreDownloadOutcome.Success)
            val bbox = capturedBboxes.single().single()
            assertTrue("Expected non-zero latitude extent", bbox.minLat < bbox.maxLat)
            assertTrue("Expected non-zero longitude extent", bbox.minLon < bbox.maxLon)
            assertTrue("Expected buffer covered south", bbox.minLat < 0.0)
            assertTrue("Expected buffer covered north", bbox.maxLat > 0.0)
            assertTrue("Expected buffer covered west", bbox.minLon < 0.0)
            assertTrue("Expected buffer covered east", bbox.maxLon > 0.0)
        }

    @Test
    fun progressReachesTotalAfterExhaustedTileAndCropFailures() =
        runTest {
            val candidateDao = FakeCandidateDao(listOf(candidate(1L, 0.0, 0.0)))
            val claimDao = FakeClaimDao()
            val surveyDao = FakeSurveyDao(latestTaskCreated = null)
            val tileStore = TileStore(Files.createTempDirectory("tiles").toFile())
            val offlineBundleDao = FakeOfflineBundleDao()
            val offlineBundleRepository =
                OfflineBundleRepository(
                    tileStore,
                    FakeTileManifestDao(),
                    offlineBundleDao,
                    testDispatcher,
                )

            val stage4Service = mock(Stage4Service::class.java)
            `when`(stage4Service.getClaims(SURVEY_ID.toString(), true))
                .thenReturn(
                    ListClaimsResponseDto(
                        listOf(
                            ClaimDto(
                                inferenceResultId = 1L,
                                userId = 2L,
                                username = "me",
                                claimedAt = "2026-01-01T00:00:00Z",
                                isMe = true,
                            ),
                        ),
                    ),
                )
            val claimRepository =
                ClaimRepository(
                    stage4Service,
                    claimDao,
                    testDispatcher,
                )

            val stage4Repository = mock(Stage4Repository::class.java)
            `when`(stage4Repository.fetchLatestTaskCreated(SURVEY_ID)).thenReturn(null)

            val tileService = mock(TileService::class.java)
            `when`(
                tileService.getCandidateTile(
                    anyLong(),
                    anyLong(),
                    anyInt(),
                    anyInt(),
                    anyInt(),
                ),
            ).thenReturn(Response.error(500, "err".toResponseBody()))
            `when`(tileService.getCandidateCrop(anyLong()))
                .thenReturn(Response.error(500, "err".toResponseBody()))

            val offlineManagerWrapper = mock(OfflineManagerWrapper::class.java)
            doAnswer { invocation ->
                val completionCb =
                    invocation.getArgument<(Result<Unit>) -> Unit>(4)
                completionCb(Result.success(Unit))
            }.`when`(offlineManagerWrapper)
                .splitAndDownload(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )

            val filesDir = Files.createTempDirectory("crops").toFile()
            val orchestrator =
                PreDownloadOrchestrator(
                    claimRepository = claimRepository,
                    stage4Repository = stage4Repository,
                    candidateDao = candidateDao,
                    claimDao = claimDao,
                    surveyDao = surveyDao,
                    tileStore = tileStore,
                    lowZoomCompositor = noOpCompositor,
                    tileService = tileService,
                    offlineManagerWrapper = offlineManagerWrapper,
                    offlineBundleRepository = offlineBundleRepository,
                    candidateImageRepository = candidateImageRepository,
                    geotiffRadiusRepository = geotiffRadiusRepository,
                    satelliteRegionStore = satelliteRegionStore,
                    filesDir = filesDir,
                    ioDispatcher = testDispatcher,
                    freeBytes = { Long.MAX_VALUE },
                )

            val progressUpdates = mutableListOf<Data>()
            val outcome =
                orchestrator.run(SURVEY_ID, BUFFER_METERS) { progressUpdates.add(it) }

            assertTrue("Expected failure but got $outcome", outcome is PreDownloadOutcome.Failure)
            assertTrue("Expected progress updates", progressUpdates.isNotEmpty())
            assertTrue("Expected no completed bundle", offlineBundleDao.inserted.isEmpty())
        }

    @Test
    fun noContentTileWritesTransparentXyzCacheMarker() =
        runTest {
            val candidateDao = FakeCandidateDao(listOf(candidate(1L, 0.0, 0.0)))
            val claimDao = FakeClaimDao()
            val surveyDao = FakeSurveyDao(latestTaskCreated = null)
            val tileStore = TileStore(Files.createTempDirectory("tiles").toFile())
            val offlineBundleDao = FakeOfflineBundleDao()
            val offlineBundleRepository =
                OfflineBundleRepository(
                    tileStore,
                    FakeTileManifestDao(),
                    offlineBundleDao,
                    testDispatcher,
                )

            val stage4Service = mock(Stage4Service::class.java)
            `when`(stage4Service.getClaims(SURVEY_ID.toString(), true))
                .thenReturn(
                    ListClaimsResponseDto(
                        listOf(
                            ClaimDto(
                                inferenceResultId = 1L,
                                userId = 2L,
                                username = "me",
                                claimedAt = "2026-01-01T00:00:00Z",
                                isMe = true,
                            ),
                        ),
                    ),
                )
            val claimRepository = ClaimRepository(stage4Service, claimDao, testDispatcher)
            val stage4Repository = mock(Stage4Repository::class.java)
            `when`(stage4Repository.fetchLatestTaskCreated(SURVEY_ID)).thenReturn(null)

            val tileService = mock(TileService::class.java)
            `when`(
                tileService.getCandidateTile(
                    anyLong(),
                    anyLong(),
                    anyInt(),
                    anyInt(),
                    anyInt(),
                ),
            ).thenReturn(Response.success<ResponseBody>(204, null))
            val cropBody =
                byteArrayOf(9, 9, 9)
                    .toResponseBody("image/jpeg".toMediaType())
            `when`(tileService.getCandidateCrop(anyLong()))
                .thenReturn(Response.success(cropBody))

            val offlineManagerWrapper = mock(OfflineManagerWrapper::class.java)
            doAnswer { invocation ->
                val completionCb =
                    invocation.getArgument<(Result<Unit>) -> Unit>(4)
                completionCb(Result.success(Unit))
            }.`when`(offlineManagerWrapper)
                .splitAndDownload(any(), any(), any(), any(), any())

            val filesDir = Files.createTempDirectory("crops").toFile()
            val orchestrator =
                PreDownloadOrchestrator(
                    claimRepository = claimRepository,
                    stage4Repository = stage4Repository,
                    candidateDao = candidateDao,
                    claimDao = claimDao,
                    surveyDao = surveyDao,
                    tileStore = tileStore,
                    lowZoomCompositor = noOpCompositor,
                    tileService = tileService,
                    offlineManagerWrapper = offlineManagerWrapper,
                    offlineBundleRepository = offlineBundleRepository,
                    candidateImageRepository = candidateImageRepository,
                    geotiffRadiusRepository = geotiffRadiusRepository,
                    satelliteRegionStore = satelliteRegionStore,
                    filesDir = filesDir,
                    ioDispatcher = testDispatcher,
                    freeBytes = { Long.MAX_VALUE },
                )

            val outcome = orchestrator.run(SURVEY_ID, BUFFER_METERS) {}

            assertTrue(outcome is PreDownloadOutcome.Success)
            val output = (outcome as PreDownloadOutcome.Success).outputData
            assertTrue(output.getInt(PreDownloadOrchestrator.KEY_TILE_COUNT, 0) > 0)
            assertTrue(tileStore.hasCandidate(SURVEY_ID, 1L))
        }

    @Test
    fun oversizedDeclaredCropResponseIsRejectedWithoutWrite() =
        runTest {
            val candidateDao = FakeCandidateDao(listOf(candidate(1L, 0.0, 0.0)))
            val claimDao = FakeClaimDao()
            val surveyDao = FakeSurveyDao(latestTaskCreated = null)
            val tileStore = TileStore(Files.createTempDirectory("tiles").toFile())
            val offlineBundleDao = FakeOfflineBundleDao()
            val offlineBundleRepository =
                OfflineBundleRepository(
                    tileStore,
                    FakeTileManifestDao(),
                    offlineBundleDao,
                    testDispatcher,
                )

            val stage4Service = mock(Stage4Service::class.java)
            `when`(stage4Service.getClaims(SURVEY_ID.toString(), true))
                .thenReturn(
                    ListClaimsResponseDto(
                        listOf(
                            ClaimDto(
                                inferenceResultId = 1L,
                                userId = 2L,
                                username = "me",
                                claimedAt = "2026-01-01T00:00:00Z",
                                isMe = true,
                            ),
                        ),
                    ),
                )
            val claimRepository =
                ClaimRepository(
                    stage4Service,
                    claimDao,
                    testDispatcher,
                )

            val stage4Repository = mock(Stage4Repository::class.java)
            `when`(stage4Repository.fetchLatestTaskCreated(SURVEY_ID)).thenReturn(null)

            val tileService = mock(TileService::class.java)
            `when`(
                tileService.getCandidateTile(
                    anyLong(),
                    anyLong(),
                    anyInt(),
                    anyInt(),
                    anyInt(),
                ),
            ).thenReturn(
                Response.success(
                    byteArrayOf(1, 2, 3)
                        .toResponseBody("image/png".toMediaType()),
                ),
            )
            val oversizedCrop =
                object : ResponseBody() {
                    override fun contentType(): MediaType? = "image/jpeg".toMediaType()

                    override fun contentLength(): Long = 10L * 1024L * 1024L

                    override fun source(): BufferedSource = Buffer()
                }
            `when`(tileService.getCandidateCrop(anyLong()))
                .thenReturn(Response.success(oversizedCrop))

            val offlineManagerWrapper = mock(OfflineManagerWrapper::class.java)
            doAnswer { invocation ->
                val completionCb =
                    invocation.getArgument<(Result<Unit>) -> Unit>(4)
                completionCb(Result.success(Unit))
            }.`when`(offlineManagerWrapper)
                .splitAndDownload(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )

            val filesDir = Files.createTempDirectory("crops").toFile()
            val orchestrator =
                PreDownloadOrchestrator(
                    claimRepository = claimRepository,
                    stage4Repository = stage4Repository,
                    candidateDao = candidateDao,
                    claimDao = claimDao,
                    surveyDao = surveyDao,
                    tileStore = tileStore,
                    lowZoomCompositor = noOpCompositor,
                    tileService = tileService,
                    offlineManagerWrapper = offlineManagerWrapper,
                    offlineBundleRepository = offlineBundleRepository,
                    candidateImageRepository = candidateImageRepository,
                    geotiffRadiusRepository = geotiffRadiusRepository,
                    satelliteRegionStore = satelliteRegionStore,
                    filesDir = filesDir,
                    ioDispatcher = testDispatcher,
                    freeBytes = { Long.MAX_VALUE },
                )

            val outcome = orchestrator.run(SURVEY_ID, BUFFER_METERS) {}

            assertTrue("Expected failure but got $outcome", outcome is PreDownloadOutcome.Failure)
            assertFalse(
                "Expected oversized crop not written",
                File(filesDir, "crops/$SURVEY_ID/1.jpg").exists(),
            )
        }

    @Test
    @Suppress("SwallowedException")
    fun failureAfterWriteLeavesNoOrphanFiles() =
        runTest {
            val candidateDao = FakeCandidateDao(listOf(candidate(1L, 0.0, 0.0)))
            val claimDao = FakeClaimDao()
            val surveyDao = FakeSurveyDao(latestTaskCreated = null)
            val tileStore = TileStore(Files.createTempDirectory("tiles").toFile())
            val offlineBundleDao = FakeOfflineBundleDao(failOnInsert = true)
            val offlineBundleRepository =
                OfflineBundleRepository(
                    tileStore,
                    FakeTileManifestDao(),
                    offlineBundleDao,
                    testDispatcher,
                )

            val stage4Service = mock(Stage4Service::class.java)
            `when`(stage4Service.getClaims(SURVEY_ID.toString(), true))
                .thenReturn(
                    ListClaimsResponseDto(
                        listOf(
                            ClaimDto(
                                inferenceResultId = 1L,
                                userId = 2L,
                                username = "me",
                                claimedAt = "2026-01-01T00:00:00Z",
                                isMe = true,
                            ),
                        ),
                    ),
                )
            val claimRepository =
                ClaimRepository(
                    stage4Service,
                    claimDao,
                    testDispatcher,
                )

            val stage4Repository = mock(Stage4Repository::class.java)
            `when`(stage4Repository.fetchLatestTaskCreated(SURVEY_ID)).thenReturn(null)

            val tileService = mock(TileService::class.java)
            `when`(
                tileService.getCandidateTile(
                    anyLong(),
                    anyLong(),
                    anyInt(),
                    anyInt(),
                    anyInt(),
                ),
            ).thenReturn(
                Response.success(
                    byteArrayOf(1, 2, 3)
                        .toResponseBody("image/png".toMediaType()),
                ),
            )
            `when`(tileService.getCandidateCrop(anyLong()))
                .thenReturn(
                    Response.success(
                        byteArrayOf(9, 9, 9)
                            .toResponseBody("image/jpeg".toMediaType()),
                    ),
                )

            val offlineManagerWrapper = mock(OfflineManagerWrapper::class.java)
            doAnswer { invocation ->
                val completionCb =
                    invocation.getArgument<(Result<Unit>) -> Unit>(4)
                completionCb(Result.success(Unit))
            }.`when`(offlineManagerWrapper)
                .splitAndDownload(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )

            val filesDir = Files.createTempDirectory("crops").toFile()
            val orchestrator =
                PreDownloadOrchestrator(
                    claimRepository = claimRepository,
                    stage4Repository = stage4Repository,
                    candidateDao = candidateDao,
                    claimDao = claimDao,
                    surveyDao = surveyDao,
                    tileStore = tileStore,
                    lowZoomCompositor = noOpCompositor,
                    tileService = tileService,
                    offlineManagerWrapper = offlineManagerWrapper,
                    offlineBundleRepository = offlineBundleRepository,
                    candidateImageRepository = candidateImageRepository,
                    geotiffRadiusRepository = geotiffRadiusRepository,
                    satelliteRegionStore = satelliteRegionStore,
                    filesDir = filesDir,
                    ioDispatcher = testDispatcher,
                    freeBytes = { Long.MAX_VALUE },
                )

            var thrown = false
            try {
                orchestrator.run(SURVEY_ID, BUFFER_METERS) {}
            } catch (e: IllegalStateException) {
                thrown = true
            }
            assertTrue("Expected pre-insert failure to propagate", thrown)
            assertFalse("Expected survey tiles cleaned up", tileStore.hasSurvey(SURVEY_ID))
            assertFalse(
                "Expected crops cleaned up",
                File(filesDir, "crops/$SURVEY_ID").exists(),
            )
        }

    private class FakeCandidateDao(
        initial: List<CandidateEntity> = emptyList(),
    ) : CandidateDao {
        var candidates: List<CandidateEntity> = initial

        override suspend fun upsertAll(candidates: List<CandidateEntity>) {
            this.candidates = candidates
        }

        override suspend fun upsert(candidate: CandidateEntity) = Unit

        override fun observeCandidatesForSurvey(surveyId: Long): Flow<List<CandidateEntity>> =
            flowOf(candidates)

        override suspend fun getCandidatesForSurvey(surveyId: Long): List<CandidateEntity> =
            candidates

        override suspend fun getById(inferenceResultId: Long): CandidateEntity? =
            candidates.find { it.inferenceResultId == inferenceResultId }

        override suspend fun deleteForSurvey(surveyId: Long) {
            candidates = emptyList()
        }

        override suspend fun deleteAll() {
            candidates = emptyList()
        }
    }

    private class FakeClaimDao : ClaimDao {
        val claims = mutableListOf<ClaimEntity>()

        override suspend fun upsert(claim: ClaimEntity) {
            claims.add(claim)
        }

        override suspend fun upsertAll(claims: List<ClaimEntity>) {
            this.claims.addAll(claims)
        }

        override fun getClaims(
            surveyId: Long,
            onlyActive: Boolean,
        ): Flow<List<ClaimEntity>> =
            flowOf(
                claims.filter {
                    it.surveyId == surveyId && (!onlyActive || it.isActive)
                },
            )

        override suspend fun getByCandidateId(inferenceResultId: Long): ClaimEntity? =
            claims.find { it.inferenceResultId == inferenceResultId }

        override suspend fun countActiveClaimedCandidates(surveyId: Long): Int =
            claims.count { it.surveyId == surveyId && it.isActive && it.isMine }

        override suspend fun releaseClaimsForUser(
            userId: Long,
            candidateIds: List<Long>,
        ) = Unit

        override suspend fun deactivateMineClaimsForSurvey(surveyId: Long) {
            claims.replaceAll { claim ->
                if (claim.surveyId == surveyId && claim.isMine) {
                    claim.copy(isActive = false)
                } else {
                    claim
                }
            }
        }

        override suspend fun deleteForSurvey(surveyId: Long) {
            claims.removeAll { it.surveyId == surveyId }
        }

        override suspend fun deleteAll() {
            claims.clear()
        }
    }

    private class FakeSurveyDao(
        latestTaskCreated: String?,
    ) : SurveyDao {
        var survey: SurveyEntity? =
            SurveyEntity(
                id = SURVEY_ID,
                eventId = "evt",
                description = null,
                created = "2026-01-01T00:00:00Z",
                hasStage4 = true,
                activeSurvey = true,
                tilesetId = null,
                latestTaskCreated = latestTaskCreated,
                baseLat = null,
                baseLon = null,
            )

        override suspend fun upsert(survey: SurveyEntity) {
            this.survey = survey
        }

        override suspend fun upsertAll(surveys: List<SurveyEntity>) {
            this.survey = surveys.firstOrNull()
        }

        override suspend fun getById(id: Long): SurveyEntity? = survey

        override fun observeById(id: Long): Flow<SurveyEntity?> = flowOf(survey)

        override fun observeAllSurveys(): Flow<List<SurveyEntity>> = flowOf(listOfNotNull(survey))

        override suspend fun updateLastViewed(
            id: Long,
            timestamp: Long,
        ) = Unit

        override suspend fun deleteById(id: Long) {
            survey = null
        }

        override suspend fun deleteAll() {
            survey = null
        }
    }

    private class FakeTileManifestDao : TileManifestDao {
        override suspend fun insertAll(tiles: List<TileManifestEntity>) = Unit

        override suspend fun getTilesForSurvey(surveyId: Long): List<TileManifestEntity> =
            emptyList()

        override suspend fun deleteForSurvey(surveyId: Long) = Unit

        override suspend fun deleteAll() = Unit
    }

    private class FakeOfflineBundleDao(
        private val failOnInsert: Boolean = false,
    ) : OfflineBundleDao {
        val inserted = mutableListOf<OfflineBundleEntity>()

        override suspend fun insert(bundle: OfflineBundleEntity): Long {
            if (failOnInsert) {
                error("insert failed")
            }
            inserted.add(bundle)
            return inserted.size.toLong()
        }

        override fun observeLatestBundleForSurvey(surveyId: Long): Flow<OfflineBundleEntity?> =
            flowOf(inserted.lastOrNull())

        override suspend fun deleteForSurvey(surveyId: Long) {
            inserted.removeAll { it.surveyId == surveyId }
        }

        override suspend fun deleteAll() {
            inserted.clear()
        }
    }

    companion object {
        private const val SURVEY_ID = 7L
        private const val BUFFER_METERS = 100f
    }
}
