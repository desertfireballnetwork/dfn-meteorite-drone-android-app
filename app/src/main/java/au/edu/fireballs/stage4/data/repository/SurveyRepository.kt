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
                        val surveys = body?.surveys?.map { it.toDomain() }.orEmpty()
                        SurveyFetchResult.Success(surveys)
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
    }
