package au.edu.fireballs.stage4.data.repository

import au.edu.fireballs.stage4.data.mapper.toDomain
import au.edu.fireballs.stage4.data.remote.Stage4Service
import au.edu.fireballs.stage4.di.IoDispatcher
import au.edu.fireballs.stage4.domain.model.Survey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import java.net.HttpURLConnection
import java.util.Locale
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SurveyFetchResult {
    data class Success(
        val surveys: List<Survey>,
    ) : SurveyFetchResult

    data class Error(
        val message: String? = null,
    ) : SurveyFetchResult

    data object AuthExpired : SurveyFetchResult

    data object NetworkError : SurveyFetchResult
}

sealed interface SetCarLocationResult {
    data object Success : SetCarLocationResult

    data class Error(
        val message: String,
    ) : SetCarLocationResult

    data object AuthExpired : SetCarLocationResult

    data object NetworkError : SetCarLocationResult
}

@Singleton
class SurveyRepository
    @Inject
    constructor(
        private val stage4Service: Stage4Service,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun getSurveys(): SurveyFetchResult =
            withContext(ioDispatcher) {
                try {
                    val response = stage4Service.getSurveys()
                    val rawResponse = response.raw()

                    val isRedirect =
                        response.code() == HttpURLConnection.HTTP_MOVED_TEMP ||
                            rawResponse.priorResponse?.code == HttpURLConnection.HTTP_MOVED_TEMP

                    val redirectTarget =
                        response.headers()["Location"]
                            ?: rawResponse.priorResponse?.header("Location")

                    if (isRedirect && redirectTarget?.contains("login") == true) {
                        return@withContext SurveyFetchResult.AuthExpired
                    }

                    if (response.isSuccessful) {
                        val body = response.body()
                        if (body == null) {
                            SurveyFetchResult.Error("Response body was empty or malformed.")
                        } else {
                            val surveys = body.surveys.map { it.toDomain() }
                            SurveyFetchResult.Success(surveys)
                        }
                    } else {
                        if (response.code() == HttpURLConnection.HTTP_UNAUTHORIZED ||
                            response.code() == HttpURLConnection.HTTP_FORBIDDEN
                        ) {
                            SurveyFetchResult.AuthExpired
                        } else {
                            SurveyFetchResult.Error("Server returned code: ${response.code()}")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    SurveyFetchResult.NetworkError
                } catch (e: HttpException) {
                    if (e.code() == HttpURLConnection.HTTP_UNAUTHORIZED) {
                        SurveyFetchResult.AuthExpired
                    } else {
                        SurveyFetchResult.Error(e.message())
                    }
                } catch (e: Exception) {
                    SurveyFetchResult.Error(e.localizedMessage ?: "An unexpected error occurred")
                }
            }

        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun setCarLocation(
            surveyId: Long,
            latitude: Double,
            longitude: Double,
        ): SetCarLocationResult =
            withContext(ioDispatcher) {
                try {
                    val latStr = String.format(Locale.US, "%.6f", latitude)
                    val lonStr = String.format(Locale.US, "%.6f", longitude)
                    val response = stage4Service.setCarLocation(surveyId.toString(), latStr, lonStr)
                    val rawResponse = response.raw()

                    val isRedirect =
                        response.code() == HttpURLConnection.HTTP_MOVED_TEMP ||
                            rawResponse.priorResponse?.code == HttpURLConnection.HTTP_MOVED_TEMP

                    val redirectTarget =
                        response.headers()["Location"]
                            ?: rawResponse.priorResponse?.header("Location")

                    if (isRedirect && redirectTarget?.contains("login") == true) {
                        return@withContext SetCarLocationResult.AuthExpired
                    }

                    if (response.isSuccessful) {
                        val body = response.body()?.string()
                        if (body == "OK") {
                            SetCarLocationResult.Success
                        } else {
                            SetCarLocationResult.Error("Unexpected response body.")
                        }
                    } else {
                        when (response.code()) {
                            HttpURLConnection.HTTP_BAD_REQUEST ->
                                SetCarLocationResult.Error("Invalid coordinates")

                            HttpURLConnection.HTTP_UNAUTHORIZED,
                            HttpURLConnection.HTTP_FORBIDDEN,
                            -> SetCarLocationResult.AuthExpired

                            else ->
                                SetCarLocationResult.Error(
                                    "Server returned code: ${response.code()}",
                                )
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    SetCarLocationResult.NetworkError
                } catch (e: HttpException) {
                    if (e.code() == HttpURLConnection.HTTP_UNAUTHORIZED) {
                        SetCarLocationResult.AuthExpired
                    } else {
                        SetCarLocationResult.Error(e.message())
                    }
                } catch (e: Exception) {
                    SetCarLocationResult.Error(e.localizedMessage ?: "An unexpected error occurred")
                }
            }
    }
