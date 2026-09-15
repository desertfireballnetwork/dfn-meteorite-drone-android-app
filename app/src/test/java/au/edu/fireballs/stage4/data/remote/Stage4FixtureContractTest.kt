package au.edu.fireballs.stage4.data.remote

import au.edu.fireballs.stage4.data.remote.fixture.FixtureContract
import au.edu.fireballs.stage4.data.remote.fixture.FixtureLoader
import au.edu.fireballs.stage4.data.remote.fixture.MockServerContract
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.net.URLEncoder

@OptIn(ExperimentalCoroutinesApi::class)
abstract class Stage4FixtureContractTest {
    protected lateinit var mockWebServer: MockWebServer
    protected lateinit var stage4Service: Stage4Service
    protected lateinit var evidenceService: EvidenceService
    protected lateinit var moshi: Moshi
    protected lateinit var loader: FixtureLoader
    protected lateinit var harness: MockServerContract
    protected val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        moshi =
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
        loader = FixtureLoader()
        harness = MockServerContract(mockWebServer)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    protected fun executeRaw(contract: FixtureContract): okhttp3.Response {
        harness.enqueue(contract)
        val request = buildRawRequest(contract)
        return OkHttpClient().newCall(request).execute()
    }

    private fun buildRawRequest(contract: FixtureContract): Request {
        val url = mockWebServer.url(contract.path)
        val builder = Request.Builder().url(url)
        when (contract.method) {
            "GET" -> builder.get()
            "POST" -> builder.post(rawBody(contract))
        }
        return builder.build()
    }

    private fun rawBody(contract: FixtureContract): okhttp3.RequestBody {
        val body = contract.requestBody as? Map<*, *> ?: return ByteArray(0).toRequestBody()
        return when (contract.requestContentType) {
            "application/json" ->
                moshi
                    .adapter(Any::class.java)
                    .toJson(body)
                    .toRequestBody("application/json".toMediaType())
            "application/x-www-form-urlencoded" ->
                encodeForm(body)
                    .toRequestBody("application/x-www-form-urlencoded".toMediaType())
            else -> ByteArray(0).toRequestBody()
        }
    }

    private fun encodeForm(body: Map<*, *>): String =
        body
            .entries
            .joinToString("&") { (key, value) ->
                val encodedKey = URLEncoder.encode(key.toString(), "UTF-8")
                val encodedValue = URLEncoder.encode(value.toString(), "UTF-8")
                "$encodedKey=$encodedValue"
            }
}
