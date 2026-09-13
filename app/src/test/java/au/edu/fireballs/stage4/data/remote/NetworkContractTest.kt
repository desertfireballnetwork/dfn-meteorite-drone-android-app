package au.edu.fireballs.stage4.data.remote

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkContractTest {
    private lateinit var mockWebServer: MockWebServer
    private lateinit var stage4Service: Stage4Service
    private lateinit var evidenceService: EvidenceService
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

        stage4Service = retrofit.create(Stage4Service::class.java)
        evidenceService = retrofit.create(EvidenceService::class.java)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `postStage4Response sends inference_result is_meteorite and detection_tag fields`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("OK"))

            val response =
                stage4Service.postStage4Response(
                    surveyId = "7",
                    inferenceResult = "101",
                    isMeteorite = "true",
                    detectionTagId = "3",
                )

            assertEquals(200, response.code())

            val request = mockWebServer.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/survey/7/stage4/response/", request.path)
            val body = request.body.readUtf8()
            assertTrue(body.contains("inference_result=101"))
            assertTrue(body.contains("is_meteorite=true"))
            assertTrue(body.contains("detection_tag=3"))
        }

    @Test
    fun `postStage4Response omits detection_tag when null`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("OK"))

            val response =
                stage4Service.postStage4Response(
                    surveyId = "7",
                    inferenceResult = "101",
                    isMeteorite = "false",
                    detectionTagId = null,
                )

            assertEquals(200, response.code())

            val request = mockWebServer.takeRequest()
            val body = request.body.readUtf8()
            assertTrue(body.contains("inference_result=101"))
            assertTrue(body.contains("is_meteorite=false"))
            assertFalse(body.contains("detection_tag"))
        }

    @Test
    fun `uploadEvidence does not send captured_at part`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(201)
                    .setBody("""{"id": 123, "success": true}"""),
            )

            val file = File.createTempFile("evidence", ".jpg")
            val filePart =
                MultipartBody.Part.createFormData(
                    "file",
                    file.name,
                    file.readBytes().toRequestBody("image/jpeg".toMediaType()),
                )
            val irId = "99".toRequestBody("text/plain".toMediaType())

            val response = evidenceService.uploadEvidence("7", filePart, irId)

            assertEquals(201, response.code())
            assertTrue(response.body()?.string()?.contains("\"id\"") == true)

            val request = mockWebServer.takeRequest()
            val body = request.body.readUtf8()
            assertTrue(body.contains("name=\"file\""))
            assertTrue(body.contains("name=\"inference_result_id\""))
            assertFalse(body.contains("captured_at"))
            file.delete()
        }
}
