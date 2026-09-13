package au.edu.fireballs.stage4.data.repository

import au.edu.fireballs.stage4.data.remote.AuthInterceptor
import au.edu.fireballs.stage4.data.remote.Stage4Service
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.CancellationException

@OptIn(ExperimentalCoroutinesApi::class)
class SurveyRepositoryTest {
    private lateinit var mockWebServer: MockWebServer
    private lateinit var repository: SurveyRepository
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        val moshi =
            Moshi
                .Builder()
                .addLast(KotlinJsonAdapterFactory())
                .build()

        val retrofit =
            Retrofit
                .Builder()
                .baseUrl(mockWebServer.url("/"))
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()

        val service = retrofit.create(Stage4Service::class.java)
        repository = SurveyRepository(service, testDispatcher)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `getSurveys parses representative payload with nullables correctly`() =
        runTest(testDispatcher) {
            val jsonPayload =
                """
                {
                  "surveys": [
                    {
                      "id": 42,
                      "event_id": "DN240703-02",
                      "description": null,
                      "created": "2026-04-08T10:15:00Z",
                      "created_iso": "2026-04-08T10:15:00Z",
                      "has_stage4": true,
                      "is_active": false
                    }
                  ]
                }
                """.trimIndent()

            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(jsonPayload))

            val result = repository.getSurveys()

            assertTrue("Expected Success but got $result", result is SurveyFetchResult.Success)

            val surveys = (result as SurveyFetchResult.Success).surveys
            assertEquals(1, surveys.size)
            assertEquals(42L, surveys[0].id)
            assertEquals("", surveys[0].description)
            assertTrue(surveys[0].hasStage4)
        }

    @Test
    fun `getSurveys handles valid empty array payload`() =
        runTest {
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"surveys":[]}"""))

            val result = repository.getSurveys()
            assertTrue(result is SurveyFetchResult.Success)
            assertTrue((result as SurveyFetchResult.Success).surveys.isEmpty())
        }

    @Test
    fun `getSurveys handles HTTP 302 redirect to login as AuthExpired`() =
        runTest {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "https://example.com/login"),
            )

            val result = repository.getSurveys()
            assertEquals(SurveyFetchResult.AuthExpired, result)
        }

    @Test
    fun `getSurveys handles HTTP 401 and 403 as AuthExpired`() =
        runTest {
            mockWebServer.enqueue(MockResponse().setResponseCode(401))
            assertEquals(SurveyFetchResult.AuthExpired, repository.getSurveys())

            mockWebServer.enqueue(MockResponse().setResponseCode(403))
            assertEquals(SurveyFetchResult.AuthExpired, repository.getSurveys())
        }

    @Test
    fun `getSurveys propagates CancellationException`() =
        runTest {
            val deferred =
                async(testDispatcher) {
                    repository.getSurveys()
                }
            deferred.cancel(CancellationException("Test cancelled"))

            runCatching { deferred.await() }
                .onSuccess { error("Should have thrown CancellationException") }
                .onFailure { assertTrue(it is CancellationException) }
        }

    @Test
    fun `setCarLocation returns Success on OK response`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("OK"))

            val result = repository.setCarLocation(7L, -25.0, 134.0)

            assertEquals(SetCarLocationResult.Success, result)

            val request = mockWebServer.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/survey/7/stage4/set_car_location/", request.path)
            val body = request.body.readUtf8()
            assertTrue(body.contains("latitude"))
            assertTrue(body.contains("longitude"))
        }

    @Test
    fun `setCarLocation returns Error on invalid coordinates`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(
                MockResponse().setResponseCode(400).setBody("Invalid coordinates"),
            )

            val result = repository.setCarLocation(7L, -25.0, 134.0)

            assertEquals(SetCarLocationResult.Error("Invalid coordinates"), result)
        }

    @Test
    fun `setCarLocation maps connection failure to NetworkError`() =
        runTest(testDispatcher) {
            val deadServer = MockWebServer()
            val deadUrl = deadServer.url("/")
            deadServer.shutdown()

            val moshi =
                Moshi
                    .Builder()
                    .addLast(KotlinJsonAdapterFactory())
                    .build()
            val retrofit =
                Retrofit
                    .Builder()
                    .baseUrl(deadUrl)
                    .addConverterFactory(MoshiConverterFactory.create(moshi))
                    .build()
            val deadRepository =
                SurveyRepository(
                    stage4Service = retrofit.create(Stage4Service::class.java),
                    ioDispatcher = testDispatcher,
                )

            val result = deadRepository.setCarLocation(7L, -25.0, 134.0)

            assertEquals(SetCarLocationResult.NetworkError, result)
        }

    @Test
    fun `setCarLocation handles HTTP 302 redirect to login as AuthExpired`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "https://example.com/login"),
            )

            val result = repository.setCarLocation(7L, -25.0, 134.0)

            assertEquals(SetCarLocationResult.AuthExpired, result)
        }

    @Test
    fun `setCarLocation sends X-CSRFToken through the authenticated client`() =
        runTest(testDispatcher) {
            val cookieJar = FakeCookieJar()
            val authInterceptor = AuthInterceptor(cookieJar, "https://find.gfo.rocks")
            val client =
                OkHttpClient
                    .Builder()
                    .cookieJar(cookieJar)
                    .addInterceptor(authInterceptor)
                    .build()
            val moshi =
                Moshi
                    .Builder()
                    .addLast(KotlinJsonAdapterFactory())
                    .build()
            val retrofit =
                Retrofit
                    .Builder()
                    .baseUrl(mockWebServer.url("/"))
                    .client(client)
                    .addConverterFactory(MoshiConverterFactory.create(moshi))
                    .build()
            val authenticatedRepository =
                SurveyRepository(
                    stage4Service = retrofit.create(Stage4Service::class.java),
                    ioDispatcher = testDispatcher,
                )

            cookieJar.saveFromResponse(
                mockWebServer.url("/"),
                listOf(
                    Cookie
                        .Builder()
                        .name("csrftoken")
                        .value("test-csrf-secret")
                        .domain(mockWebServer.url("/").host)
                        .build(),
                ),
            )

            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("OK"))

            val result = authenticatedRepository.setCarLocation(7L, -25.0, 134.0)

            assertEquals(SetCarLocationResult.Success, result)

            val request = mockWebServer.takeRequest()
            assertEquals("test-csrf-secret", request.getHeader("X-CSRFToken"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("latitude"))
            assertTrue(body.contains("longitude"))
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
