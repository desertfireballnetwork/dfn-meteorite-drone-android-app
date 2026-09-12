package au.edu.fireballs.stage4.data.tiles

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import au.edu.fireballs.stage4.data.remote.LoginRedirectDetector
import au.edu.fireballs.stage4.di.IoDispatcher
import com.mapbox.bindgen.ExpectedFactory
import com.mapbox.common.HttpRequest
import com.mapbox.common.HttpRequestError
import com.mapbox.common.HttpRequestOrResponse
import com.mapbox.common.HttpResponse
import com.mapbox.common.HttpResponseData
import com.mapbox.common.HttpServiceFactory
import com.mapbox.common.HttpServiceInterceptorInterface
import com.mapbox.common.HttpServiceInterceptorRequestContinuation
import com.mapbox.common.HttpServiceInterceptorResponseContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class AuthenticatedTileHttpInterceptor
    @Inject
    constructor(
        private val tileStore: TileStore,
        private val okHttpClient: OkHttpClient,
        @Named("serverUrl") serverUrl: String,
        private val connectivityManager: ConnectivityManager,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : HttpServiceInterceptorInterface {
        private val serverOrigin: HttpUrl = serverUrl.toHttpUrl()
        private val localProvider = LocalFileRasterTileProvider(tileStore)
        private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
        private val inFlight = ConcurrentHashMap<String, Job>()
        private val inFlightCalls = ConcurrentHashMap<String, Call>()

        var onAuthLost: () -> Unit = {}

        fun installCancellationCallback() {
            HttpServiceFactory.setCancellationCallback { _, request ->
                cancel(request.url)
            }
        }

        override fun onRequest(
            request: HttpRequest,
            continuation: HttpServiceInterceptorRequestContinuation,
        ) {
            val tile = parseCandidateTileUrl(request.url)
            if (tile == null) {
                continuation.run(HttpRequestOrResponse(request))
                return
            }
            val job =
                scope.launch {
                    val response = fetchCandidateTile(request, tile)
                    continuation.run(HttpRequestOrResponse(response))
                }
            inFlight[request.url] = job
            job.invokeOnCompletion { inFlight.remove(request.url) }
        }

        override fun onResponse(
            response: HttpResponse,
            continuation: HttpServiceInterceptorResponseContinuation,
        ) {
            continuation.run(response)
        }

        fun cancel(url: String) {
            inFlight.remove(url)?.cancel()
            inFlightCalls.remove(url)?.cancel()
        }

        @Suppress("SwallowedException")
        private suspend fun fetchCandidateTile(
            request: HttpRequest,
            tile: CandidateTile,
        ): HttpResponse {
            val local = readLocal(tile)
            if (isDefinitelyOffline()) {
                return imageResponse(request, local)
            }
            val call = buildCall(request.url)
            inFlightCalls[request.url] = call
            return try {
                val response = executeCall(call)
                processResponse(request, local, response)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                imageResponse(request, local)
            } finally {
                inFlightCalls.remove(request.url)
            }
        }

        private fun processResponse(
            request: HttpRequest,
            local: ByteArray,
            response: Response,
        ): HttpResponse =
            when {
                LoginRedirectDetector.isLoginRedirect(response) ||
                    response.code == HttpURLConnection.HTTP_UNAUTHORIZED ||
                    response.code == HttpURLConnection.HTTP_FORBIDDEN -> {
                    onAuthLost()
                    imageResponse(request, local)
                }

                response.code == HttpURLConnection.HTTP_NO_CONTENT ->
                    imageResponse(request, LocalFileRasterTileProvider.TRANSPARENT_PNG)

                response.isSuccessful && response.code == HttpURLConnection.HTTP_OK -> {
                    val body = response.body
                    val bytes = body?.let { readBoundedBody(it) }
                    if (
                        bytes != null &&
                        isPngContentType(response.header("Content-Type")) &&
                        isValidPng(bytes)
                    ) {
                        imageResponse(request, bytes)
                    } else {
                        imageResponse(request, local)
                    }
                }

                response.code == 408 ||
                    response.code == 429 ||
                    response.code in 500..599 -> imageResponse(request, local)

                else -> imageResponse(request, local)
            }

        private fun readLocal(tile: CandidateTile): ByteArray {
            val localY = (1 shl tile.z) - 1 - tile.y
            return localProvider.tile(tile.surveyId, tile.candidateId, tile.z, tile.x, localY)
        }

        private fun isDefinitelyOffline(): Boolean {
            val network = connectivityManager.activeNetwork ?: return true
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return true
            return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        private fun buildCall(url: String): Call {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .get()
                    .build()
            return okHttpClient.newCall(request)
        }

        private suspend fun executeCall(call: Call): Response =
            suspendCancellableCoroutine { continuation ->
                call.enqueue(
                    object : Callback {
                        override fun onFailure(
                            call: Call,
                            e: IOException,
                        ) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(e)
                            }
                        }

                        override fun onResponse(
                            call: Call,
                            response: Response,
                        ) {
                            if (continuation.isActive) {
                                continuation.resume(response)
                            } else {
                                response.close()
                            }
                        }
                    },
                )
                continuation.invokeOnCancellation { call.cancel() }
            }

        private fun readBoundedBody(body: ResponseBody): ByteArray? {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(READ_BUFFER_BYTES)
            body.byteStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    if (output.size() + read > TileStore.MAX_TILE_BYTES) {
                        return null
                    }
                    output.write(buffer, 0, read)
                }
            }
            return output.toByteArray()
        }

        private fun isPngContentType(contentType: String?): Boolean =
            contentType
                ?.substringBefore(';')
                ?.trim()
                ?.equals("image/png", ignoreCase = true) == true

        private fun isValidPng(bytes: ByteArray): Boolean {
            if (bytes.size < PNG_SIGNATURE.size) {
                return false
            }
            return PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }
        }

        private fun parseCandidateTileUrl(url: String): CandidateTile? {
            val httpUrl = url.toHttpUrlOrNull() ?: return null
            if (
                httpUrl.scheme != serverOrigin.scheme ||
                httpUrl.host != serverOrigin.host ||
                httpUrl.port != serverOrigin.port
            ) {
                return null
            }
            val segments = httpUrl.pathSegments
            val normalized =
                if (segments.lastOrNull().isNullOrEmpty()) {
                    segments.dropLast(1)
                } else {
                    segments
                }
            if (normalized.size != 6 || normalized[0] != CANDIDATE_TILE_PATH) {
                return null
            }
            val surveyId = normalized[1].toLongOrNull() ?: return null
            val candidateId = normalized[2].toLongOrNull() ?: return null
            val z = normalized[3].toIntOrNull() ?: return null
            val x = normalized[4].toIntOrNull() ?: return null
            val y = normalized[5].toIntOrNull() ?: return null
            return CandidateTile(surveyId, candidateId, z, x, y)
        }

        private fun imageResponse(
            request: HttpRequest,
            bytes: ByteArray,
        ): HttpResponse {
            val headers =
                HashMap<String, String>().apply {
                    put("Content-Type", "image/png")
                    put("Content-Length", bytes.size.toString())
                }
            val data = HttpResponseData(headers, HttpURLConnection.HTTP_OK, bytes)
            val result = ExpectedFactory.createValue<HttpRequestError, HttpResponseData>(data)
            return HttpResponse(0L, request, result)
        }

        private data class CandidateTile(
            val surveyId: Long,
            val candidateId: Long,
            val z: Int,
            val x: Int,
            val y: Int,
        )

        companion object {
            private const val CANDIDATE_TILE_PATH = "image_geotiff_candidate_tile"
            private const val READ_BUFFER_BYTES = 8 * 1024
            private val PNG_SIGNATURE =
                byteArrayOf(
                    0x89.toByte(),
                    0x50.toByte(),
                    0x4E.toByte(),
                    0x47.toByte(),
                    0x0D.toByte(),
                    0x0A.toByte(),
                    0x1A.toByte(),
                    0x0A.toByte(),
                )
        }
    }
