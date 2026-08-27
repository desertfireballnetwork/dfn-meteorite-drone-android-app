package au.edu.fireballs.stage4.data.repository

import au.edu.fireballs.stage4.data.remote.Stage4Service
import au.edu.fireballs.stage4.data.remote.dto.Stage4StateDto
import au.edu.fireballs.stage4.di.IoDispatcher
import au.edu.fireballs.stage4.domain.model.Stage4State
import au.edu.fireballs.stage4.domain.model.toDomain
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface Stage4FetchResult {
    data class Success(
        val state: Stage4State,
    ) : Stage4FetchResult

    data class Error(
        val message: String? = null,
    ) : Stage4FetchResult

    data object AuthExpired : Stage4FetchResult

    data object NetworkError : Stage4FetchResult
}

@Singleton
class Stage4Repository
    @Inject
    constructor(
        private val stage4Service: Stage4Service,
        private val moshi: Moshi,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun getCandidatesState(surveyId: Long): Stage4FetchResult =
            withContext(ioDispatcher) {
                try {
                    resolveFetchResult(stage4Service.getCandidates(surveyId.toString()))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    Stage4FetchResult.NetworkError
                } catch (e: HttpException) {
                    if (e.code() == HttpURLConnection.HTTP_UNAUTHORIZED) {
                        Stage4FetchResult.AuthExpired
                    } else {
                        Stage4FetchResult.Error(e.message())
                    }
                } catch (e: Exception) {
                    Stage4FetchResult.Error(e.localizedMessage ?: "An unexpected error occurred")
                }
            }

        private suspend fun resolveFetchResult(
            response: Response<ResponseBody>,
        ): Stage4FetchResult {
            if (isLoginRedirect(response)) {
                return Stage4FetchResult.AuthExpired
            }
            return if (response.isSuccessful) {
                parseBody(response.body())
            } else {
                errorForHttpCode(response.code())
            }
        }

        private fun isLoginRedirect(response: Response<ResponseBody>): Boolean {
            val requestUrl = response.raw().request.url
            var current = response.raw()
            while (true) {
                if (current.code == HttpURLConnection.HTTP_MOVED_TEMP) {
                    val location = current.header("Location")
                    if (location != null && isLoginLocation(location, requestUrl)) {
                        return true
                    }
                }
                val prior = current.priorResponse ?: return false
                current = prior
            }
        }

        private fun isLoginLocation(
            location: String,
            requestUrl: HttpUrl,
        ): Boolean {
            val resolved = requestUrl.resolve(location)
            if (resolved != null) {
                return resolved.encodedPath.contains("login", ignoreCase = true)
            }
            val pathOnly = location.substringBefore('?').substringBefore('#')
            return pathOnly.contains("login", ignoreCase = true)
        }

        private suspend fun parseBody(body: ResponseBody?): Stage4FetchResult {
            val parseError = Stage4FetchResult.Error("Response body was empty or malformed.")
            if (body == null) {
                return parseError
            }
            val state = parseCandidates(body)?.toDomain() ?: return parseError
            return Stage4FetchResult.Success(state)
        }

        private fun errorForHttpCode(code: Int): Stage4FetchResult =
            when (code) {
                HttpURLConnection.HTTP_UNAUTHORIZED -> Stage4FetchResult.AuthExpired
                HttpURLConnection.HTTP_FORBIDDEN ->
                    Stage4FetchResult.Error("You don't have access to this survey")
                HttpURLConnection.HTTP_NOT_FOUND ->
                    Stage4FetchResult.Error(
                        "No Stage 4 candidates task is available for this survey.",
                    )
                else ->
                    Stage4FetchResult.Error("Server returned code: $code")
            }

        private fun parseCandidates(body: ResponseBody): Stage4StateDto? =
            moshi
                .adapter(Stage4StateDto::class.java)
                .fromJson(body.string())
    }
