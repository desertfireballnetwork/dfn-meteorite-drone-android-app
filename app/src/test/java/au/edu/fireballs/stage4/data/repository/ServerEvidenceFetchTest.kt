package au.edu.fireballs.stage4.data.repository

import android.content.Context
import au.edu.fireballs.stage4.data.local.dao.PendingPhotoUploadDao
import au.edu.fireballs.stage4.data.remote.EvidenceService
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

@OptIn(ExperimentalCoroutinesApi::class)
class ServerEvidenceFetchTest {
    private lateinit var mockWebServer: MockWebServer
    private lateinit var repository: EvidencePhotoRepository
    private val context: Context = mockk()
    private val dao: PendingPhotoUploadDao = mockk()
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

        val service = retrofit.create(EvidenceService::class.java)
        repository =
            EvidencePhotoRepository(
                context,
                dao,
                service,
                testDispatcher,
            )
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `fetchServerEvidence returns photos on success`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "photos": [
                            {
                              "id": 123,
                              "captured_at": "2026-08-27T09:00:00Z",
                              "created": "2026-08-27T09:01:00Z",
                              "user_id": 42,
                              "username": "operator"
                            }
                          ]
                        }
                        """.trimIndent(),
                    ),
            )

            val result = repository.fetchServerEvidence(7L, 101L)

            assertTrue("Expected Success but got $result", result is EvidenceFetchResult.Success)
            val success = result as EvidenceFetchResult.Success
            assertEquals(1, success.photos.size)
            val photo = success.photos.single()
            assertEquals(123L, photo.id)
            assertEquals("2026-08-27T09:00:00Z", photo.capturedAt)
            assertEquals("2026-08-27T09:01:00Z", photo.created)
            assertEquals(42L, photo.userId)
            assertEquals("operator", photo.username)
        }

    @Test
    fun `fetchServerEvidence returns empty list on empty photos`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"photos": []}"""),
            )

            val result = repository.fetchServerEvidence(7L, 101L)

            assertTrue("Expected Success but got $result", result is EvidenceFetchResult.Success)
            assertTrue((result as EvidenceFetchResult.Success).photos.isEmpty())
        }

    @Test
    fun `fetchServerEvidence uses survey and inference result ids in path`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"photos": []}"""),
            )

            repository.fetchServerEvidence(7L, 101L)

            val request = mockWebServer.takeRequest()
            assertEquals(
                "/api/stage4/surveys/7/candidates/101/evidence/",
                request.path,
            )
        }

    @Test
    fun `fetchServerEvidence maps login redirect to AuthExpired`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "/login"),
            )
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"photos": []}"""),
            )

            val result = repository.fetchServerEvidence(7L, 101L)

            assertEquals(EvidenceFetchResult.AuthExpired, result)
        }

    @Test
    fun `fetchServerEvidence maps HTTP 401 to AuthExpired`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(MockResponse().setResponseCode(401))

            assertEquals(EvidenceFetchResult.AuthExpired, repository.fetchServerEvidence(7L, 101L))
        }

    @Test
    fun `fetchServerEvidence maps HTTP 403 to access denied Error`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(MockResponse().setResponseCode(403))

            val result = repository.fetchServerEvidence(7L, 101L)

            val error = result as? EvidenceFetchResult.Error
            assertTrue("Expected Error but got $result", error != null)
            assertTrue(
                "Expected access denied message but got ${error?.message}",
                error?.message?.contains("don't have access") == true,
            )
        }

    @Test
    fun `fetchServerEvidence maps HTTP 404 to not found Error`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(MockResponse().setResponseCode(404))

            val result = repository.fetchServerEvidence(7L, 101L)

            val error = result as? EvidenceFetchResult.Error
            assertTrue("Expected Error but got $result", error != null)
            assertTrue(
                "Expected not found message but got ${error?.message}",
                error?.message?.contains("No evidence") == true,
            )
        }

    @Test
    fun `fetchServerEvidence maps connection failure to NetworkError`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(
                MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START),
            )

            assertEquals(
                EvidenceFetchResult.NetworkError,
                repository.fetchServerEvidence(7L, 101L),
            )
        }
}
