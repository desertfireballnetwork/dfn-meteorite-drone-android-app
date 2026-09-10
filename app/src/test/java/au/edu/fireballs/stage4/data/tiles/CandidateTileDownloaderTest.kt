package au.edu.fireballs.stage4.data.tiles

import au.edu.fireballs.stage4.data.remote.TileService
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.nio.file.Files

class CandidateTileDownloaderTest {
    private lateinit var server: MockWebServer
    private lateinit var downloader: CandidateTileDownloader
    private lateinit var store: TileStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val service =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(MoshiConverterFactory.create())
                .build()
                .create(TileService::class.java)
        store = TileStore(Files.createTempDirectory("tiles").toFile())
        downloader =
            CandidateTileDownloader(
                service,
                store,
                kotlinx.coroutines.Dispatchers.Unconfined,
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun downloadsTilesToXyzPaths() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "image/png")
                    .setBody("tile-bytes")
                    .setResponseCode(200),
            )
            server.enqueue(MockResponse().setResponseCode(204))

            val result =
                downloader.downloadCandidateTiles(
                    surveyId = 1,
                    candidateId = 2,
                    centroidLat = 0.0,
                    centroidLon = 0.0,
                    bufferMeters = 100f,
                    minZoom = 0,
                    maxZoom = 0,
                )

            assertTrue(result is TileDownloadResult.Success)
            assertEquals(1, (result as TileDownloadResult.Success).tileCount)
            assertTrue(store.contains(1, 2, 0, 0, 0))
        }

    @Test
    fun retriesTransientHttpThenSucceeds() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "image/png")
                    .setBody("tile-bytes")
                    .setResponseCode(200),
            )

            val result =
                downloader.downloadCandidateTiles(
                    surveyId = 1,
                    candidateId = 2,
                    centroidLat = 0.0,
                    centroidLon = 0.0,
                    bufferMeters = 100f,
                    minZoom = 0,
                    maxZoom = 0,
                )

            assertTrue(result is TileDownloadResult.Success)
            assertEquals(1, (result as TileDownloadResult.Success).tileCount)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun returnsAuthExpiredOn401() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(401))

            val result =
                downloader.downloadCandidateTiles(
                    surveyId = 1,
                    candidateId = 2,
                    centroidLat = 0.0,
                    centroidLon = 0.0,
                    bufferMeters = 100f,
                    minZoom = 0,
                    maxZoom = 0,
                )

            assertEquals(TileDownloadResult.AuthExpired, result)
        }

    @Test
    fun returnsPermanentHttpOn404() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(404))

            val result =
                downloader.downloadCandidateTiles(
                    surveyId = 1,
                    candidateId = 2,
                    centroidLat = 0.0,
                    centroidLon = 0.0,
                    bufferMeters = 100f,
                    minZoom = 0,
                    maxZoom = 0,
                )

            assertTrue(result is TileDownloadResult.PermanentHttp)
        }

    @Test
    fun returnsStorageErrorWhenWriteFails() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "image/png")
                    .setBody("tile-bytes")
                    .setResponseCode(200),
            )
            val fullStore =
                TileStore(
                    Files.createTempDirectory("full").toFile(),
                    quotaBytes = 1,
                )
            val downloaderWithFullStore =
                CandidateTileDownloader(
                    Retrofit
                        .Builder()
                        .baseUrl(server.url("/"))
                        .addConverterFactory(MoshiConverterFactory.create())
                        .build()
                        .create(TileService::class.java),
                    fullStore,
                    kotlinx.coroutines.Dispatchers.Unconfined,
                )

            val result =
                downloaderWithFullStore.downloadCandidateTiles(
                    surveyId = 1,
                    candidateId = 2,
                    centroidLat = 0.0,
                    centroidLon = 0.0,
                    bufferMeters = 100f,
                    minZoom = 0,
                    maxZoom = 0,
                )

            assertTrue(result is TileDownloadResult.StorageError)
        }

    @Test
    fun returnsPermanentHttpForNonImageContentType() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/html")
                    .setBody("<html></html>")
                    .setResponseCode(200),
            )

            val result =
                downloader.downloadCandidateTiles(
                    surveyId = 1,
                    candidateId = 2,
                    centroidLat = 0.0,
                    centroidLon = 0.0,
                    bufferMeters = 100f,
                    minZoom = 0,
                    maxZoom = 0,
                )

            assertTrue(result is TileDownloadResult.PermanentHttp)
        }

    @Test
    fun returnsAuthExpiredOnLoginRedirect() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader(
                        "Location",
                        "/accounts/login/?next=/image_geotiff_candidate_tile/1/2/0/0/0/",
                    ),
            )
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/html")
                    .setBody("<html>login</html>")
                    .setResponseCode(200),
            )

            val result =
                downloader.downloadCandidateTiles(
                    surveyId = 1,
                    candidateId = 2,
                    centroidLat = 0.0,
                    centroidLon = 0.0,
                    bufferMeters = 100f,
                    minZoom = 0,
                    maxZoom = 0,
                )

            assertEquals(TileDownloadResult.AuthExpired, result)
        }
}
