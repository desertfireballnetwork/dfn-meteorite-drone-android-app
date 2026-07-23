package au.edu.fireballs.stage4.data.remote

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AuthInterceptorTest {
    private lateinit var mockWebServer: MockWebServer
    private lateinit var testCookieJar: FakeCookieJar
    private lateinit var okHttpClient: OkHttpClient

    private val prodUrl = "https://find.gfo.rocks"

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        testCookieJar = FakeCookieJar()
        val authInterceptor = AuthInterceptor(testCookieJar, prodUrl)

        okHttpClient =
            OkHttpClient
                .Builder()
                .cookieJar(testCookieJar)
                .addInterceptor(authInterceptor)
                .build()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `intercept POST adds CSRF and Origin headers when CSRF cookie exists`() {
        val baseUrl = mockWebServer.url("/")
        testCookieJar.saveFromResponse(
            baseUrl,
            listOf(
                Cookie
                    .Builder()
                    .name("csrftoken")
                    .value("test-csrf-secret")
                    .domain(baseUrl.host)
                    .build(),
            ),
        )

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val request =
            Request
                .Builder()
                .url(baseUrl)
                .post("".toRequestBody(null))
                .build()

        okHttpClient.newCall(request).execute()

        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("test-csrf-secret", recordedRequest.getHeader("X-CSRFToken"))
        assertEquals(prodUrl, recordedRequest.getHeader("Origin"))
    }

    @Test
    fun `intercept GET does not add CSRF or Origin headers`() {
        val baseUrl = mockWebServer.url("/")
        testCookieJar.saveFromResponse(
            baseUrl,
            listOf(
                Cookie
                    .Builder()
                    .name("csrftoken")
                    .value("test-csrf-secret")
                    .domain(baseUrl.host)
                    .build(),
            ),
        )

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val request =
            Request
                .Builder()
                .url(baseUrl)
                .get()
                .build()

        okHttpClient.newCall(request).execute()

        val recordedRequest = mockWebServer.takeRequest()
        assertNull(recordedRequest.getHeader("X-CSRFToken"))
        assertNull(recordedRequest.getHeader("Origin"))
    }

    private class FakeCookieJar : CookieJar {
        private val storage = mutableListOf<Cookie>()

        override fun saveFromResponse(
            url: HttpUrl,
            cookies: List<Cookie>,
        ) {
            storage.addAll(cookies)
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = storage.filter { it.matches(url) }
    }
}
