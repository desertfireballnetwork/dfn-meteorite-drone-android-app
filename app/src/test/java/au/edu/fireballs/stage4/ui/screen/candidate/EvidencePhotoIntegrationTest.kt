package au.edu.fireballs.stage4.ui.screen.candidate

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import au.edu.fireballs.stage4.data.remote.EvidenceService
import au.edu.fireballs.stage4.data.remote.PersistentCookieJar
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EvidencePhotoIntegrationTest {
    private lateinit var mockWebServer: MockWebServer
    private lateinit var cookieJar: PersistentCookieJar
    private lateinit var evidenceService: EvidenceService
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs =
            context.getSharedPreferences("evidence_photo_integration_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        cookieJar = PersistentCookieJar(context, prefs)

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
                .baseUrl(mockWebServer.url("/"))
                .client(okHttpClient)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()

        evidenceService = retrofit.create(EvidenceService::class.java)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    private fun seedSessionCookie() {
        val url: HttpUrl = mockWebServer.url("/")
        val cookie =
            Cookie
                .Builder()
                .name("sessionid")
                .value("mock_session_value")
                .domain(url.host)
                .path("/")
                .build()
        cookieJar.saveFromResponse(url, listOf(cookie))
    }

    private fun jpegBytes(
        width: Int,
        height: Int,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        bitmap.recycle()
        return stream.toByteArray()
    }

    @Test
    fun `full image request carries session cookie and decodes jpeg without redirect`() =
        runTest(testDispatcher) {
            seedSessionCookie()
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "image/jpeg")
                    .setBody(Buffer().write(jpegBytes(320, 240))),
            )

            val response = evidenceService.viewEvidencePhoto(photoId = 123L)

            val request = mockWebServer.takeRequest()
            assertEquals("/api/stage4/evidence/123/", request.path)
            val cookieHeader = request.getHeader("Cookie")
            assertNotNull("expected session cookie on request", cookieHeader)
            assertTrue(
                "expected sessionid in cookie header but got $cookieHeader",
                cookieHeader?.contains("sessionid=mock_session_value") == true,
            )

            assertEquals(200, response.code())
            assertEquals("image/jpeg", response.headers()["Content-Type"])
            assertTrue(response.body() != null)

            val body = response.body()!!.bytes()
            val options =
                BitmapFactory
                    .Options()
                    .apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(body, 0, body.size, options)
            assertEquals(320, options.outWidth)
            assertEquals(240, options.outHeight)
        }

    @Test
    fun `full image request without session cookie is not redirected`() =
        runTest(testDispatcher) {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "image/jpeg")
                    .setBody(Buffer().write(jpegBytes(16, 16))),
            )

            val response = evidenceService.viewEvidencePhoto(photoId = 7L)

            val request = mockWebServer.takeRequest()
            assertEquals("/api/stage4/evidence/7/", request.path)
            assertEquals(200, response.code())
            assertEquals("image/jpeg", response.headers()["Content-Type"])
        }
}
