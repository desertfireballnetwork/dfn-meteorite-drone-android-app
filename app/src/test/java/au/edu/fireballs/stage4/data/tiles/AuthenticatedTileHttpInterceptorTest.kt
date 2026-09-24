package au.edu.fireballs.stage4.data.tiles

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.mapbox.common.HttpMethod
import com.mapbox.common.HttpRequest
import com.mapbox.common.HttpRequestOrResponse
import com.mapbox.common.HttpResponseData
import com.mapbox.common.HttpServiceInterceptorRequestContinuation
import com.mapbox.common.NetworkRestriction
import com.mapbox.common.SdkInformation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files
import java.util.Base64
import java.util.HashMap
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class AuthenticatedTileHttpInterceptorTest {
    private val fakeCompositor =
        object : LowZoomCompositor {
            override fun compose(
                surveyId: Long,
                candidateId: Long,
                sourceTiles: List<TileCoord>,
                parent: TileCoord,
            ): ByteArray = FAKE_COMPOSITE_BYTES

            override fun parentTiles(
                sourceTiles: List<TileCoord>,
                zoom: Int,
            ): List<TileCoord> = emptyList()
        }

    private lateinit var server: MockWebServer
    private lateinit var store: TileStore
    private lateinit var cookieJar: InMemoryCookieJar
    private lateinit var client: OkHttpClient
    private lateinit var interceptor: AuthenticatedTileHttpInterceptor

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = TileStore(Files.createTempDirectory("tiles").toFile())
        cookieJar = InMemoryCookieJar()
        client = OkHttpClient.Builder().cookieJar(cookieJar).build()
        interceptor = newInterceptor(client, onlineConnectivityManager())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun unmatchedRequestPassedThroughUnchanged() {
        val url = unmatchedUrl()
        val result = onRequest(url)
        assertTrue(result.isHttpRequest())
        assertEquals(url, result.getHttpRequest().getUrl())
    }

    @Test
    fun outOfRangeZoomIsPassedThroughUnchanged() {
        val url = server.url("/image_geotiff_candidate_tile/1/2/10/100/50/").toString()
        val result = onRequest(url)
        assertTrue(result.isHttpRequest())
        assertEquals(url, result.getHttpRequest().getUrl())
    }

    @Test
    fun lowZoomTransparentStoreHitFallsBackToComposite() {
        val childX = 887_826
        val childY = 433_111
        val parentZoom = 18
        val scale = 1 shl (20 - parentZoom)
        val parentX = childX / scale
        val parentXyzY = childY / scale
        val parentTmsY = (1 shl parentZoom) - 1 - parentXyzY
        store.write(1, 2, 20, childX, childY, opaquePng())
        store.write(
            1,
            2,
            parentZoom,
            parentX,
            parentXyzY,
            LocalFileRasterTileProvider.TRANSPARENT_PNG,
        )

        val result =
            onRequest(
                candidateUrl(z = parentZoom, x = parentX, y = parentTmsY),
                newInterceptor(client, offlineConnectivityManager()),
            )

        val bytes = responseData(result).getData()
        assertArrayEquals(FAKE_COMPOSITE_BYTES, bytes)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun lowZoomTmsYIsConvertedToXyzParentBeforeCompositing() {
        val xyzChildY = 433_111
        store.write(
            1,
            2,
            20,
            887_826,
            xyzChildY,
            LocalFileRasterTileProvider.TRANSPARENT_PNG,
        )
        val parentZoom = 18
        val scale = 1 shl (20 - parentZoom)
        val parentX = 887_826 / scale
        val parentXyzY = xyzChildY / scale
        val parentTmsY = (1 shl parentZoom) - 1 - parentXyzY

        val result =
            onRequest(
                candidateUrl(
                    z = parentZoom,
                    x = parentX,
                    y = parentTmsY,
                ),
                newInterceptor(client, offlineConnectivityManager()),
            )

        assertArrayEquals(FAKE_COMPOSITE_BYTES, responseData(result).getData())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun outOfRangeCoordinateIsPassedThroughUnchanged() {
        val url = server.url("/image_geotiff_candidate_tile/1/2/20/2000000/50/").toString()
        val result = onRequest(url)
        assertTrue(result.isHttpRequest())
        assertEquals(url, result.getHttpRequest().getUrl())
    }

    @Test
    fun matching200PngCarriesSessionCookie() {
        val buffer = okio.Buffer()
        buffer.write(LocalFileRasterTileProvider.TRANSPARENT_PNG)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "image/png")
                .setBody(buffer),
        )
        cookieJar.cookies
            .getOrPut(server.hostName) { mutableListOf() }
            .add(sessionCookie())

        val result = onRequest(candidateUrl())

        val recorded = server.takeRequest()
        assertEquals("sessionid=abc123", recorded.getHeader("Cookie"))
        assertArrayEquals(
            LocalFileRasterTileProvider.TRANSPARENT_PNG,
            responseData(result).getData(),
        )
        assertEquals(200, responseData(result).getCode())
    }

    @Test
    fun offlineLocalHitReturnsLocalPngWithZeroRequests() {
        val local = seedLocalTile()
        val offlineInterceptor = newInterceptor(client, offlineConnectivityManager())
        val result = onRequest(candidateUrl(), offlineInterceptor)
        assertArrayEquals(local, responseData(result).getData())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun offlineLocalMissReturnsTransparentPngWithZeroRequests() {
        val offlineInterceptor = newInterceptor(client, offlineConnectivityManager())
        val result = onRequest(candidateUrl(), offlineInterceptor)
        assertArrayEquals(
            LocalFileRasterTileProvider.TRANSPARENT_PNG,
            responseData(result).getData(),
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun http204ReturnsTransparencyEvenWithLocalHit() {
        seedLocalTile()
        server.enqueue(MockResponse().setResponseCode(204))
        val result = onRequest(candidateUrl())
        assertArrayEquals(
            LocalFileRasterTileProvider.TRANSPARENT_PNG,
            responseData(result).getData(),
        )
    }

    @Test
    fun loginRedirectWithLocalHitReturnsLocalPng() {
        val local = seedLocalTile()
        enqueueLoginRedirect()
        val result = onRequest(candidateUrl())
        assertArrayEquals(local, responseData(result).getData())
    }

    @Test
    fun loginRedirectWithLocalMissReturnsTransparency() {
        enqueueLoginRedirect()
        val result = onRequest(candidateUrl())
        assertArrayEquals(
            LocalFileRasterTileProvider.TRANSPARENT_PNG,
            responseData(result).getData(),
        )
    }

    @Test
    fun finalLoginResponseWithLocalHitReturnsLocalPng() {
        val local = seedLocalTile()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html>login page</html>"),
        )
        val result = onRequest(candidateUrl())
        assertArrayEquals(local, responseData(result).getData())
    }

    @Test
    fun http401WithLocalHitReturnsLocalPng() {
        val local = seedLocalTile()
        server.enqueue(MockResponse().setResponseCode(401))
        val result = onRequest(candidateUrl())
        assertArrayEquals(local, responseData(result).getData())
    }

    @Test
    fun http401WithLocalMissReturnsTransparency() {
        server.enqueue(MockResponse().setResponseCode(401))
        val result = onRequest(candidateUrl())
        assertArrayEquals(
            LocalFileRasterTileProvider.TRANSPARENT_PNG,
            responseData(result).getData(),
        )
    }

    @Test
    fun http403WithLocalHitReturnsLocalPng() {
        val local = seedLocalTile()
        server.enqueue(MockResponse().setResponseCode(403))
        val result = onRequest(candidateUrl())
        assertArrayEquals(local, responseData(result).getData())
    }

    @Test
    fun http403WithLocalMissReturnsTransparency() {
        server.enqueue(MockResponse().setResponseCode(403))
        val result = onRequest(candidateUrl())
        assertArrayEquals(
            LocalFileRasterTileProvider.TRANSPARENT_PNG,
            responseData(result).getData(),
        )
    }

    @Test
    fun http408WithLocalHitReturnsLocalPng() {
        val local = seedLocalTile()
        server.enqueue(MockResponse().setResponseCode(408))
        val result = onRequest(candidateUrl())
        assertArrayEquals(local, responseData(result).getData())
    }

    @Test
    fun http408WithLocalMissReturnsTransparency() {
        server.enqueue(MockResponse().setResponseCode(408))
        val result = onRequest(candidateUrl())
        assertArrayEquals(
            LocalFileRasterTileProvider.TRANSPARENT_PNG,
            responseData(result).getData(),
        )
    }

    @Test
    fun http429WithLocalHitReturnsLocalPng() {
        val local = seedLocalTile()
        server.enqueue(MockResponse().setResponseCode(429))
        val result = onRequest(candidateUrl())
        assertArrayEquals(local, responseData(result).getData())
    }

    @Test
    fun http429WithLocalMissReturnsTransparency() {
        server.enqueue(MockResponse().setResponseCode(429))
        val result = onRequest(candidateUrl())
        assertArrayEquals(
            LocalFileRasterTileProvider.TRANSPARENT_PNG,
            responseData(result).getData(),
        )
    }

    @Test
    fun http5xxWithLocalHitReturnsLocalPng() {
        val local = seedLocalTile()
        server.enqueue(MockResponse().setResponseCode(503))
        val result = onRequest(candidateUrl())
        assertArrayEquals(local, responseData(result).getData())
    }

    @Test
    fun http5xxWithLocalMissReturnsTransparency() {
        server.enqueue(MockResponse().setResponseCode(500))
        val result = onRequest(candidateUrl())
        assertArrayEquals(
            LocalFileRasterTileProvider.TRANSPARENT_PNG,
            responseData(result).getData(),
        )
    }

    @Test
    fun timeoutWithLocalHitReturnsLocalPng() {
        val local = seedLocalTile()
        val slowClient =
            OkHttpClient
                .Builder()
                .readTimeout(100, TimeUnit.MILLISECONDS)
                .cookieJar(cookieJar)
                .build()
        val slowInterceptor = newInterceptor(slowClient, onlineConnectivityManager())
        server.enqueue(
            MockResponse()
                .setBodyDelay(2, TimeUnit.SECONDS)
                .setHeader("Content-Type", "image/png")
                .setBody("tile"),
        )
        val result = onRequest(candidateUrl(), slowInterceptor)
        assertArrayEquals(local, responseData(result).getData())
    }

    @Test
    fun timeoutWithLocalMissReturnsTransparency() {
        val slowClient =
            OkHttpClient
                .Builder()
                .readTimeout(100, TimeUnit.MILLISECONDS)
                .cookieJar(cookieJar)
                .build()
        val slowInterceptor = newInterceptor(slowClient, onlineConnectivityManager())
        server.enqueue(
            MockResponse()
                .setBodyDelay(2, TimeUnit.SECONDS)
                .setHeader("Content-Type", "image/png")
                .setBody("tile"),
        )
        val result = onRequest(candidateUrl(), slowInterceptor)
        assertArrayEquals(
            LocalFileRasterTileProvider.TRANSPARENT_PNG,
            responseData(result).getData(),
        )
    }

    @Test
    fun dnsFailureWithLocalHitReturnsLocalPng() {
        val local = seedLocalTile()
        val dnsInterceptor =
            newInterceptor(
                client,
                onlineConnectivityManager(),
                "http://nonexistent.invalid/",
            )
        val result =
            onRequest(
                "http://nonexistent.invalid/image_geotiff_candidate_tile/1/2/20/100/50/",
                dnsInterceptor,
            )
        assertArrayEquals(local, responseData(result).getData())
    }

    @Test
    fun dnsFailureWithLocalMissReturnsTransparency() {
        val dnsInterceptor =
            newInterceptor(
                client,
                onlineConnectivityManager(),
                "http://nonexistent.invalid/",
            )
        val result =
            onRequest(
                "http://nonexistent.invalid/image_geotiff_candidate_tile/1/2/20/100/50/",
                dnsInterceptor,
            )
        assertArrayEquals(
            LocalFileRasterTileProvider.TRANSPARENT_PNG,
            responseData(result).getData(),
        )
    }

    @Test
    fun connectionFailureWithLocalHitReturnsLocalPng() {
        val local = seedLocalTile()
        val deadServer = MockWebServer()
        deadServer.start()
        val deadUrl = deadServer.url("/").toString()
        deadServer.shutdown()
        val connInterceptor =
            newInterceptor(
                client,
                onlineConnectivityManager(),
                deadUrl,
            )
        val result =
            onRequest(
                deadUrl + "image_geotiff_candidate_tile/1/2/20/100/50/",
                connInterceptor,
            )
        assertArrayEquals(local, responseData(result).getData())
    }

    @Test
    fun connectionFailureWithLocalMissReturnsTransparency() {
        val deadServer = MockWebServer()
        deadServer.start()
        val deadUrl = deadServer.url("/").toString()
        deadServer.shutdown()
        val connInterceptor =
            newInterceptor(
                client,
                onlineConnectivityManager(),
                deadUrl,
            )
        val result =
            onRequest(
                deadUrl + "image_geotiff_candidate_tile/1/2/20/100/50/",
                connInterceptor,
            )
        assertArrayEquals(
            LocalFileRasterTileProvider.TRANSPARENT_PNG,
            responseData(result).getData(),
        )
    }

    @Test
    fun empty200WithLocalHitReturnsLocalPng() {
        val local = seedLocalTile()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "image/png"),
        )
        val result = onRequest(candidateUrl())
        assertArrayEquals(local, responseData(result).getData())
    }

    @Test
    fun empty200WithLocalMissReturnsTransparency() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "image/png"),
        )
        val result = onRequest(candidateUrl())
        assertArrayEquals(
            LocalFileRasterTileProvider.TRANSPARENT_PNG,
            responseData(result).getData(),
        )
    }

    @Test
    fun invalidContentTypeWithLocalHitReturnsLocalPng() {
        val local = seedLocalTile()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html>not an image</html>"),
        )
        val result = onRequest(candidateUrl())
        assertArrayEquals(local, responseData(result).getData())
    }

    @Test
    fun invalidContentTypeWithLocalMissReturnsTransparency() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html>not an image</html>"),
        )
        val result = onRequest(candidateUrl())
        assertArrayEquals(
            LocalFileRasterTileProvider.TRANSPARENT_PNG,
            responseData(result).getData(),
        )
    }

    @Test
    fun malformedPngWithLocalHitReturnsLocalPng() {
        val local = seedLocalTile()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "image/png")
                .setBody("not-a-valid-png"),
        )
        val result = onRequest(candidateUrl())
        assertArrayEquals(local, responseData(result).getData())
    }

    @Test
    fun malformedPngWithLocalMissReturnsTransparency() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "image/png")
                .setBody("not-a-valid-png"),
        )
        val result = onRequest(candidateUrl())
        assertArrayEquals(
            LocalFileRasterTileProvider.TRANSPARENT_PNG,
            responseData(result).getData(),
        )
    }

    @Test
    fun oversizedBodyWithLocalHitReturnsLocalPng() {
        val local = seedLocalTile()
        val oversized = ByteArray(TileStore.MAX_TILE_BYTES + 1) { 1 }
        val buffer = okio.Buffer()
        buffer.write(oversized)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "image/png")
                .setBody(buffer),
        )
        val result = onRequest(candidateUrl())
        assertArrayEquals(local, responseData(result).getData())
    }

    @Test
    fun oversizedBodyWithLocalMissReturnsTransparency() {
        val oversized = ByteArray(TileStore.MAX_TILE_BYTES + 1) { 1 }
        val buffer = okio.Buffer()
        buffer.write(oversized)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "image/png")
                .setBody(buffer),
        )
        val result = onRequest(candidateUrl())
        assertArrayEquals(
            LocalFileRasterTileProvider.TRANSPARENT_PNG,
            responseData(result).getData(),
        )
    }

    @Test
    @Suppress("SwallowedException")
    fun cancellationCancelsUnderlyingOkHttpCall() =
        runBlocking {
            val capturedCalls = mutableListOf<Call>()
            val cancellableClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        capturedCalls.add(chain.call())
                        chain.proceed(chain.request())
                    }.build()
            server.enqueue(
                MockResponse()
                    .setHeadersDelay(2, TimeUnit.SECONDS)
                    .setBodyDelay(2, TimeUnit.SECONDS)
                    .setHeader("Content-Type", "image/png")
                    .setBody("tile"),
            )
            val cancellableInterceptor =
                newInterceptor(cancellableClient, onlineConnectivityManager())
            val url = candidateUrl()
            val request =
                HttpRequest
                    .Builder()
                    .method(HttpMethod.GET)
                    .url(url)
                    .headers(HashMap())
                    .networkRestriction(NetworkRestriction.NONE)
                    .sdkInformation(SdkInformation("test", "1.0", "test"))
                    .build()

            val continuation = CapturingContinuation()
            cancellableInterceptor.onRequest(request, continuation)
            while (capturedCalls.isEmpty()) {
                delay(10)
            }
            cancellableInterceptor.cancel(url)
            assertTrue(capturedCalls.first().isCanceled())
            val continuationDelivered =
                try {
                    withTimeout(1_000) { continuation.result.await() }
                    true
                } catch (e: TimeoutCancellationException) {
                    false
                }
            assertFalse("Expected no continuation delivery after cancel", continuationDelivered)
        }

    @Test
    fun cancelReachesAllConcurrentSameUrlRequests() =
        runBlocking {
            val capturedCalls = mutableListOf<Call>()
            val cancellableClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        capturedCalls.add(chain.call())
                        chain.proceed(chain.request())
                    }.build()
            server.enqueue(
                MockResponse()
                    .setBodyDelay(2, TimeUnit.SECONDS)
                    .setHeader("Content-Type", "image/png")
                    .setBody("tile"),
            )
            server.enqueue(
                MockResponse()
                    .setBodyDelay(2, TimeUnit.SECONDS)
                    .setHeader("Content-Type", "image/png")
                    .setBody("tile"),
            )
            val cancellableInterceptor =
                newInterceptor(cancellableClient, onlineConnectivityManager())
            val url = candidateUrl()
            val request =
                HttpRequest
                    .Builder()
                    .method(HttpMethod.GET)
                    .url(url)
                    .headers(HashMap())
                    .networkRestriction(NetworkRestriction.NONE)
                    .sdkInformation(SdkInformation("test", "1.0", "test"))
                    .build()

            cancellableInterceptor.onRequest(request, CapturingContinuation())
            cancellableInterceptor.onRequest(request, CapturingContinuation())
            while (capturedCalls.size < 2) {
                delay(10)
            }
            cancellableInterceptor.cancel(url)
            assertTrue(capturedCalls.all { it.isCanceled() })
        }

    @Test
    fun cleartextOriginAcceptedInDebug() {
        val httpInterceptor =
            newInterceptor(
                client,
                onlineConnectivityManager(),
                "http://example.com/",
            )
        assertNotNull(httpInterceptor)
    }

    private fun newInterceptor(
        okHttpClient: OkHttpClient,
        connectivityManager: ConnectivityManager,
        baseUrl: String = server.url("/").toString(),
    ): AuthenticatedTileHttpInterceptor =
        AuthenticatedTileHttpInterceptor(
            store,
            okHttpClient,
            baseUrl,
            connectivityManager,
            Dispatchers.Unconfined,
            fakeCompositor,
        )

    private fun candidateUrl(
        surveyId: Long = 1,
        candidateId: Long = 2,
        z: Int = 20,
        x: Int = 100,
        y: Int = 50,
    ): String =
        server
            .url("/image_geotiff_candidate_tile/$surveyId/$candidateId/$z/$x/$y/")
            .toString()

    private fun unmatchedUrl(): String = server.url("/styles/mapbox-standard/style.json").toString()

    private fun onRequest(
        url: String,
        target: AuthenticatedTileHttpInterceptor = interceptor,
    ): HttpRequestOrResponse =
        runBlocking {
            val request =
                HttpRequest
                    .Builder()
                    .method(HttpMethod.GET)
                    .url(url)
                    .headers(HashMap())
                    .networkRestriction(NetworkRestriction.NONE)
                    .sdkInformation(SdkInformation("test", "1.0", "test"))
                    .build()
            val continuation = CapturingContinuation()
            target.onRequest(request, continuation)
            continuation.result.await()
        }

    private fun responseData(result: HttpRequestOrResponse): HttpResponseData {
        assertTrue(result.isHttpResponse())
        val response = result.getHttpResponse()
        assertTrue(response.getResult().isValue())
        return response.getResult().getValue()!!
    }

    private fun opaquePng(): ByteArray =
        Base64
            .getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAF")
            .plus(Base64.getDecoder().decode("gAIBQfH3WQAAAABJRU5ErkJggg=="))

    private fun seedLocalTile(): ByteArray {
        val bytes = byteArrayOf(9, 8, 7, 6, 5)
        val flippedY = (1 shl 20) - 1 - 50
        store.write(1, 2, 20, 100, flippedY, bytes)
        return bytes
    }

    private fun enqueueLoginRedirect() {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader(
                    "Location",
                    "/accounts/login/?next=/image_geotiff_candidate_tile/1/2/20/100/50/",
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html>login page</html>"),
        )
    }

    private fun sessionCookie(): Cookie =
        Cookie
            .Builder()
            .name("sessionid")
            .value("abc123")
            .domain(server.hostName)
            .path("/")
            .build()

    private fun onlineConnectivityManager(): ConnectivityManager {
        val manager = mock<ConnectivityManager>()
        val network = mock<Network>()
        val capabilities = mock<NetworkCapabilities>()
        whenever(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            .thenReturn(true)
        whenever(manager.activeNetwork).thenReturn(network)
        whenever(manager.getNetworkCapabilities(network)).thenReturn(capabilities)
        return manager
    }

    private fun offlineConnectivityManager(): ConnectivityManager {
        val manager = mock<ConnectivityManager>()
        whenever(manager.activeNetwork).thenReturn(null)
        return manager
    }

    companion object {
        private val FAKE_COMPOSITE_BYTES = byteArrayOf(1, 2, 3)
    }

    private class CapturingContinuation : HttpServiceInterceptorRequestContinuation {
        val result = CompletableDeferred<HttpRequestOrResponse>()

        override fun run(value: HttpRequestOrResponse) {
            result.complete(value)
        }
    }

    private class InMemoryCookieJar : CookieJar {
        val cookies = mutableMapOf<String, MutableList<Cookie>>()

        override fun saveFromResponse(
            url: HttpUrl,
            cookies: List<Cookie>,
        ) {
            this.cookies.getOrPut(url.host) { mutableListOf() }.addAll(cookies)
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            cookies[url.host]?.filter { it.matches(url) } ?: emptyList()
    }
}
