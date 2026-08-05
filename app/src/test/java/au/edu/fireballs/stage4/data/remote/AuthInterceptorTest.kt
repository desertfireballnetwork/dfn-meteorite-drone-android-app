package au.edu.fireballs.stage4.data.remote

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `login request body is not logged in cleartext by logging interceptor`() {
        val mockWebServer = MockWebServer()
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "/api/surveys/")
                .setHeader("Set-Cookie", "sessionid=test_session_123; Path=/")
                .setBody(""),
        )
        mockWebServer.start()

        val capturedLogMessages = mutableListOf<String>()

        try {
            val testLogger =
                HttpLoggingInterceptor.Logger { message ->
                    capturedLogMessages.add(message)
                }

            val loggingInterceptor =
                HttpLoggingInterceptor(testLogger).apply {
                    level = HttpLoggingInterceptor.Level.BODY
                }

            val selectiveLoggingInterceptor =
                Interceptor { chain ->
                    val request = chain.request()
                    val isAuthEndpoint =
                        request.url.encodedPath.contains(
                            "login",
                            ignoreCase = true,
                        )

                    if (isAuthEndpoint) {
                        val originalLevel = loggingInterceptor.level
                        loggingInterceptor.level = HttpLoggingInterceptor.Level.HEADERS
                        try {
                            chain.proceed(request)
                        } finally {
                            loggingInterceptor.level = originalLevel
                        }
                    } else {
                        chain.proceed(request)
                    }
                }

            val okHttpClient =
                OkHttpClient
                    .Builder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .addInterceptor(selectiveLoggingInterceptor)
                    .addInterceptor(loggingInterceptor)
                    .build()

            val sensitivePassword = "SuperSecretPassword123!"
            val formBody =
                FormBody
                    .Builder()
                    .add("username", "testuser")
                    .add("password", sensitivePassword)
                    .add("csrfmiddlewaretoken", "csrf_token_xyz")
                    .build()

            val loginUrl = mockWebServer.url("/accounts/login/")
            val request =
                Request
                    .Builder()
                    .url(loginUrl)
                    .post(formBody)
                    .build()

            val response = okHttpClient.newCall(request).execute()

            assertEquals(302, response.code)

            val recordedRequest = mockWebServer.takeRequest()
            val requestBodyUtf8 =
                java.net.URLDecoder.decode(
                    recordedRequest.body.readUtf8(),
                    "UTF-8",
                )
            assertTrue(
                "Expected server to receive password",
                requestBodyUtf8.contains(sensitivePassword),
            )

            // Verify credentials were never logged in cleartext or URL-encoded form
            val loggedPasswordInCleartext =
                capturedLogMessages.any { log ->
                    log.contains(sensitivePassword) ||
                        log.contains("SuperSecretPassword123%21") ||
                        log.contains("csrf_token_xyz")
                }

            assertFalse(
                "Expected sensitive credentials to be excluded from logs, but found cleartext in logged output",
                loggedPasswordInCleartext,
            )
        } finally {
            mockWebServer.shutdown()
        }
    }
}
