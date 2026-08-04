package au.edu.fireballs.stage4.data.remote

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.net.HttpURLConnection

class AuthRepositoryTest {
    private lateinit var mockWebServer: MockWebServer
    private lateinit var authRepository: AuthRepository
    private lateinit var cookieJar: CookieJar
    private lateinit var baseUrl: HttpUrl
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        baseUrl = mockWebServer.url("/")

        cookieJar =
            object : CookieJar {
                private val cookies = mutableListOf<okhttp3.Cookie>()

                override fun saveFromResponse(
                    url: HttpUrl,
                    cookies: List<okhttp3.Cookie>,
                ) {
                    this.cookies.addAll(cookies)
                }

                override fun loadForRequest(url: HttpUrl): List<okhttp3.Cookie> = cookies
            }

        val okHttpClient =
            OkHttpClient
                .Builder()
                .cookieJar(cookieJar)
                .followRedirects(false)
                .build()

        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

        val retrofit =
            Retrofit
                .Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()

        val authService = retrofit.create(AuthService::class.java)
        authRepository = AuthRepository(authService, cookieJar, baseUrl, testDispatcher)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `login success with CSRF extraction and 302 redirect`() =
        runTest {
            enqueueLoginForm()

            val postResponse =
                MockResponse()
                    .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
                    .addHeader("Location", "/api/surveys/")
                    .addHeader(
                        "Set-Cookie",
                        "${AuthConstants.SESSION_COOKIE_NAME}=mock_session_id_456; Path=/",
                    )

            mockWebServer.enqueue(postResponse)

            val result = authRepository.login("testuser", "correctpassword")

            assertTrue(result is AuthResult.Success)

            val getRequest = mockWebServer.takeRequest()
            assertEquals("/accounts/login/", getRequest.path)
            assertEquals("GET", getRequest.method)

            val postRequest = mockWebServer.takeRequest()
            assertEquals("/accounts/login/", postRequest.path)
            assertEquals("POST", postRequest.method)

            val postBody = postRequest.body.readUtf8()
            assertTrue(postBody.contains("username=testuser"))
            assertTrue(postBody.contains("password=correctpassword"))
            assertTrue(postBody.contains("csrfmiddlewaretoken=mock_csrf_token_123"))

            val cookies = cookieJar.loadForRequest(baseUrl)
            assertNotNull(cookies.firstOrNull { it.name == AuthConstants.SESSION_COOKIE_NAME })
            assertNotNull(cookies.firstOrNull { it.name == AuthConstants.CSRF_COOKIE_NAME })
        }

    @Test
    fun `login failure with 200 response and HTML error parsing`() =
        runTest {
            enqueueLoginForm()

            val htmlWithError =
                """
                <html>
                    <body>
                        <div class="alert alert-danger">Please enter a correct username and password.</div>
                    </body>
                </html>
                """.trimIndent()

            val postResponse =
                MockResponse()
                    .setResponseCode(HttpURLConnection.HTTP_OK)
                    .setBody(htmlWithError)

            mockWebServer.enqueue(postResponse)

            val result = authRepository.login("testuser", "wrongpassword")

            assertTrue(result is AuthResult.Failure)
            val failureResult = result as AuthResult.Failure
            assertEquals("Please enter a correct username and password.", failureResult.message)
        }

    @Test
    fun `login network error returns AuthResult NetworkError`() =
        runTest {
            mockWebServer.shutdown()
            val result = authRepository.login("testuser", "password")
            assertTrue(result is AuthResult.NetworkError)
        }

    private fun enqueueLoginForm(
        csrfToken: String = "mock_csrf_token_123",
        responseCode: Int = HttpURLConnection.HTTP_OK,
    ) {
        val getResponse =
            MockResponse()
                .setResponseCode(responseCode)
                .addHeader("Set-Cookie", "${AuthConstants.CSRF_COOKIE_NAME}=$csrfToken; Path=/")
                .setBody(
                    """<html><body><input type="hidden" name="csrfmiddlewaretoken" value="$csrfToken"></body></html>""",
                )
        mockWebServer.enqueue(getResponse)
    }
}
