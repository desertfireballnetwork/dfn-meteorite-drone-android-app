package au.edu.fireballs.stage4.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Message
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import au.edu.fireballs.stage4.data.local.CandidateEntity
import au.edu.fireballs.stage4.data.local.Stage4Database
import au.edu.fireballs.stage4.data.local.SurveyEntity
import au.edu.fireballs.stage4.data.remote.Stage4Service
import au.edu.fireballs.stage4.data.remote.TileService
import au.edu.fireballs.stage4.data.repository.CandidateImageRepository
import au.edu.fireballs.stage4.data.repository.ClaimRepository
import au.edu.fireballs.stage4.data.repository.Stage4Repository
import au.edu.fireballs.stage4.data.tiles.GeotiffRadiusRepository
import au.edu.fireballs.stage4.data.tiles.LowZoomTileCompositor
import au.edu.fireballs.stage4.data.tiles.OfflineBundleRepository
import au.edu.fireballs.stage4.data.tiles.OfflineManagerWrapper
import au.edu.fireballs.stage4.data.tiles.OfflineRegionHandle
import au.edu.fireballs.stage4.data.tiles.OfflineRegionSource
import au.edu.fireballs.stage4.data.tiles.OfflineRegionWrapper
import au.edu.fireballs.stage4.data.tiles.RoomSatelliteRegionStore
import au.edu.fireballs.stage4.data.tiles.TileStore
import com.mapbox.maps.AsyncOperationResultCallback
import com.mapbox.maps.OfflineRegionDownloadState
import com.mapbox.maps.OfflineRegionObserver
import com.mapbox.maps.OfflineRegionStatus
import com.mapbox.maps.OfflineRegionTilePyramidDefinition
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class PreDownloadWorkerE2eTest {
    private lateinit var mockWebServer: MockWebServer
    private lateinit var database: Stage4Database
    private lateinit var tileStore: TileStore
    private lateinit var cropsDir: File

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        mockWebServer.dispatcher = dispatcher()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database =
            Room
                .inMemoryDatabaseBuilder(context, Stage4Database::class.java)
                .build()
        tileStore = TileStore(Files.createTempDirectory("tiles").toFile())
        cropsDir = Files.createTempDirectory("crops").toFile()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        database.close()
    }

    @Test
    fun preDownloadWritesTilesCropsAndBundle() =
        runBlocking {
            seedSurvey()
            seedCandidates()
            val ioDispatcher = Dispatchers.IO
            val claimRepository =
                ClaimRepository(
                    stage4Service(),
                    database.claimDao(),
                    ioDispatcher,
                )
            val stage4Repository =
                Stage4Repository(
                    stage4Service(),
                    moshi(),
                    database.surveyDao(),
                    database.candidateDao(),
                    ioDispatcher,
                )
            val offlineBundleRepository =
                OfflineBundleRepository(
                    tileStore,
                    database.tileManifestDao(),
                    database.offlineBundleDao(),
                    ioDispatcher,
                )
            val offlineManagerWrapper =
                OfflineManagerWrapper(
                    OfflineRegionWrapper(
                        source = FakeOfflineRegionSource(),
                        mainHandler = immediateHandler(),
                    ),
                )
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val candidateImageRepository =
                CandidateImageRepository(
                    context,
                    SERVER_URL,
                )
            val geotiffRadiusRepository =
                GeotiffRadiusRepository(
                    context.getSharedPreferences(
                        "e2e_geotiff_radius",
                        Context.MODE_PRIVATE,
                    ),
                )
            val satelliteRegionStore =
                RoomSatelliteRegionStore(database.satelliteRegionDao())
            val orchestrator =
                PreDownloadOrchestrator(
                    claimRepository = claimRepository,
                    stage4Repository = stage4Repository,
                    candidateDao = database.candidateDao(),
                    claimDao = database.claimDao(),
                    surveyDao = database.surveyDao(),
                    tileStore = tileStore,
                    lowZoomCompositor = LowZoomTileCompositor(tileStore),
                    tileService = tileService(),
                    offlineManagerWrapper = offlineManagerWrapper,
                    offlineBundleRepository = offlineBundleRepository,
                    candidateImageRepository = candidateImageRepository,
                    geotiffRadiusRepository = geotiffRadiusRepository,
                    satelliteRegionStore = satelliteRegionStore,
                    filesDir = cropsDir,
                    ioDispatcher = ioDispatcher,
                )

            val outcome = orchestrator.run(SURVEY_ID, BUFFER_METERS) {}

            assertTrue("Expected success but got $outcome", outcome is PreDownloadOutcome.Success)

            CANDIDATE_IDS.forEach { candidateId ->
                assertTrue(tileStore.hasCandidate(SURVEY_ID, candidateId))
                val candidateDir =
                    tileStore
                        .surveyTilesDirectory(SURVEY_ID)
                        .resolve(candidateId.toString())
                val tileFiles = candidateDir.walkTopDown().filter { it.isFile }.toList()
                assertTrue("No tiles for candidate $candidateId", tileFiles.isNotEmpty())
                assertTrue(
                    "Missing crop for candidate $candidateId",
                    File(cropsDir, "$SURVEY_ID/$candidateId.jpg").isFile,
                )
            }

            val bundle =
                database
                    .offlineBundleDao()
                    .observeLatestBundleForSurvey(SURVEY_ID)
                    .first()
            assertNotNull("Expected an offline bundle row", bundle)
            bundle?.let {
                assertTrue(it.totalBytes > 0)
                assertTrue(it.tileCount > 0)
                assertTrue(it.satelliteRegionCount > 0)
                assertEquals(CANDIDATE_IDS.size, it.candidateCount)
                assertEquals(BUFFER_METERS, it.bufferMeters)
            }
        }

    private suspend fun seedSurvey() {
        database.surveyDao().upsert(
            SurveyEntity(
                id = SURVEY_ID,
                eventId = "evt-9",
                description = null,
                created = "2026-09-01T00:00:00Z",
                hasStage4 = true,
                activeSurvey = true,
                tilesetId = null,
                latestTaskCreated = null,
                baseLat = null,
                baseLon = null,
            ),
        )
    }

    private suspend fun seedCandidates() {
        CANDIDATE_IDS.forEachIndexed { index, id ->
            database.candidateDao().upsert(
                candidate(id, CANDIDATE_LATS[index], CANDIDATE_LONS[index]),
            )
        }
    }

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

    private fun stage4Service(): Stage4Service = retrofit().create(Stage4Service::class.java)

    private fun tileService(): TileService = retrofit().create(TileService::class.java)

    private fun moshi(): Moshi =
        Moshi
            .Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

    private fun retrofit(): Retrofit =
        Retrofit
            .Builder()
            .baseUrl(mockWebServer.url("/"))
            .addConverterFactory(
                MoshiConverterFactory.create(moshi()),
            ).build()

    private fun dispatcher(): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path =
                    request.requestUrl?.encodedPath
                        ?: return MockResponse().setResponseCode(404)
                return when {
                    path.endsWith("/claims/") -> jsonResponse(claimsJson())

                    path.startsWith("/image_geotiff_candidate_tile/") ->
                        MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "image/png")
                            .setBody(Buffer().write(PNG_BYTES))

                    path.startsWith("/image_survey_cropped/") ->
                        MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "image/jpeg")
                            .setBody(Buffer().write(JPG_BYTES))

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

    private fun jsonResponse(body: String): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private fun claimsJson(): String {
        val claims =
            CANDIDATE_IDS.joinToString(",") { id ->
                """
                {
                  "inference_result_id": $id,
                  "user_id": 2,
                  "username": "me",
                  "full_name": "Me",
                  "claimed_at": "2026-09-01T00:00:00Z",
                  "is_me": true
                }
                """.trimIndent()
            }
        return """{"claims": [$claims]}"""
    }

    private fun immediateHandler(): Handler =
        object : Handler(Looper.getMainLooper()) {
            override fun sendMessageAtTime(
                msg: Message,
                uptimeMillis: Long,
            ): Boolean {
                msg.callback?.run()
                return true
            }
        }

    private class FakeOfflineRegionSource : OfflineRegionSource {
        override fun createOfflineRegion(
            definition: OfflineRegionTilePyramidDefinition,
            callback: (Result<OfflineRegionHandle>) -> Unit,
        ) {
            callback(Result.success(FakeOfflineRegionHandle()))
        }

        override fun getOfflineRegions(callback: (Result<List<OfflineRegionHandle>>) -> Unit) {
            callback(Result.success(emptyList()))
        }
    }

    private class FakeOfflineRegionHandle : OfflineRegionHandle {
        private var observer: OfflineRegionObserver? = null

        override val identifier: Long = 1L

        override fun setOfflineRegionObserver(observer: OfflineRegionObserver) {
            this.observer = observer
        }

        override fun setOfflineRegionDownloadState(state: OfflineRegionDownloadState) {
            if (state == OfflineRegionDownloadState.ACTIVE) {
                observer?.statusChanged(COMPLETED_STATUS)
            }
        }

        override fun purge(callback: AsyncOperationResultCallback) = Unit
    }

    companion object {
        private const val SERVER_URL = "https://example.test/"
        private const val SURVEY_ID = 7L
        private const val BUFFER_METERS = 100f
        private val CANDIDATE_IDS = listOf(1L, 2L, 3L, 4L, 5L)
        private val CANDIDATE_LATS = listOf(-31.95, -31.20, -30.70, -32.10, -31.50)
        private val CANDIDATE_LONS = listOf(115.86, 116.00, 116.50, 116.30, 115.50)
        private val PNG_BYTES =
            byteArrayOf(
                0x89.toByte(),
                0x50.toByte(),
                0x4E.toByte(),
                0x47.toByte(),
            )
        private val JPG_BYTES =
            byteArrayOf(
                0xFF.toByte(),
                0xD8.toByte(),
                0xFF.toByte(),
                0xE0.toByte(),
            )
        private val COMPLETED_STATUS =
            OfflineRegionStatus(
                OfflineRegionDownloadState.ACTIVE,
                1,
                1,
                1,
                1,
                1,
                1,
                true,
            )
    }
}
