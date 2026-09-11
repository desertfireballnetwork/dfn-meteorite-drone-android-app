package au.edu.fireballs.stage4.data.repository

import au.edu.fireballs.stage4.data.local.CandidateEntity
import au.edu.fireballs.stage4.data.local.SurveyEntity
import au.edu.fireballs.stage4.data.local.dao.CandidateDao
import au.edu.fireballs.stage4.data.local.dao.SurveyDao
import au.edu.fireballs.stage4.data.remote.LoginRedirectDetector
import au.edu.fireballs.stage4.data.remote.Stage4Service
import au.edu.fireballs.stage4.data.remote.dto.Stage4StateDto
import au.edu.fireballs.stage4.di.IoDispatcher
import au.edu.fireballs.stage4.domain.model.BoundingBox
import au.edu.fireballs.stage4.domain.model.DetectionTag
import au.edu.fireballs.stage4.domain.model.GeoCoordinate
import au.edu.fireballs.stage4.domain.model.ImageDims
import au.edu.fireballs.stage4.domain.model.SizeMetres
import au.edu.fireballs.stage4.domain.model.Stage4Candidate
import au.edu.fireballs.stage4.domain.model.Stage4State
import au.edu.fireballs.stage4.domain.model.Stage4Survey
import au.edu.fireballs.stage4.domain.model.UserLocation
import au.edu.fireballs.stage4.domain.model.toDomain
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
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
        val isOffline: Boolean = false,
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
        private val surveyDao: SurveyDao,
        private val candidateDao: CandidateDao,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        private val surveyedAreasAdapter =
            moshi.adapter<List<List<List<Double>>>>(
                Types.newParameterizedType(
                    List::class.java,
                    Types.newParameterizedType(
                        List::class.java,
                        Types.newParameterizedType(List::class.java, Double::class.javaObjectType),
                    ),
                ),
            )
        private val detectionTagsAdapter =
            moshi.adapter<List<DetectionTag>>(
                Types.newParameterizedType(
                    List::class.java,
                    DetectionTag::class.java,
                ),
            )
        private val userLocationsAdapter =
            moshi.adapter<List<UserLocation>>(
                Types.newParameterizedType(
                    List::class.java,
                    UserLocation::class.java,
                ),
            )
        private val geoAreaAdapter =
            moshi.adapter<List<List<Double>>>(
                Types.newParameterizedType(
                    List::class.java,
                    Types.newParameterizedType(List::class.java, Double::class.javaObjectType),
                ),
            )

        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun getCandidatesState(surveyId: Long): Stage4FetchResult =
            withContext(ioDispatcher) {
                try {
                    val result =
                        resolveFetchResult(
                            stage4Service.getCandidates(surveyId.toString()),
                        )
                    if (result is Stage4FetchResult.Success) {
                        persistState(result.state)
                    }
                    result
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    // load from Room db if offline
                    loadFromRoom(surveyId) ?: Stage4FetchResult.NetworkError
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

        private suspend fun persistState(state: Stage4State) {
            val existingSurvey = surveyDao.getById(state.survey.id)
            val surveyEntity =
                SurveyEntity(
                    id = state.survey.id,
                    eventId = state.survey.eventId,
                    description = state.survey.description ?: existingSurvey?.description,
                    created =
                        state.survey.created.takeIf { it.isNotEmpty() }
                            ?: existingSurvey?.created.orEmpty(),
                    hasStage4 = existingSurvey?.hasStage4 ?: true,
                    activeSurvey = existingSurvey?.activeSurvey ?: true,
                    tilesetId = state.survey.tilesetId ?: existingSurvey?.tilesetId,
                    latestTaskCreated =
                        state.latestTaskCreated.ifEmpty {
                            existingSurvey
                                ?.latestTaskCreated
                        },
                    baseLat = state.base?.latitude ?: existingSurvey?.baseLat,
                    baseLon = state.base?.longitude ?: existingSurvey?.baseLon,
                    surveyedAreasJson = surveyedAreasAdapter.toJson(state.surveyedAreas),
                    detectionTagsJson = detectionTagsAdapter.toJson(state.detectionTags),
                    userLocationsJson = userLocationsAdapter.toJson(state.userLocations),
                    showGeolocationAccuracyCircle = state.showGeolocationAccuracyCircle,
                )
            surveyDao.upsert(surveyEntity)

            val candidates =
                mutableListOf<CandidateEntity>().apply {
                    addAll(state.unprocessedCandidates.map { it.toEntity(state.survey.id, 0) })
                    addAll(state.yesMeteorites.map { it.toEntity(state.survey.id, 1) })
                    addAll(state.noMeteorites.map { it.toEntity(state.survey.id, 2) })
                }
            candidateDao.replaceForSurvey(state.survey.id, candidates)
        }

        private suspend fun loadFromRoom(surveyId: Long): Stage4FetchResult? {
            val surveyEntity = surveyDao.getById(surveyId) ?: return null
            val candidateEntities = candidateDao.getCandidatesForSurvey(surveyId)

            val state =
                Stage4State(
                    survey =
                        Stage4Survey(
                            id = surveyEntity.id,
                            eventId = surveyEntity.eventId,
                            tilesetId = surveyEntity.tilesetId,
                            description = surveyEntity.description,
                            created = surveyEntity.created,
                        ),
                    base =
                        if (surveyEntity.baseLat != null && surveyEntity.baseLon != null) {
                            GeoCoordinate(surveyEntity.baseLat, surveyEntity.baseLon)
                        } else {
                            null
                        },
                    surveyedAreas =
                        surveyEntity.surveyedAreasJson?.let {
                            surveyedAreasAdapter.fromJson(it)
                        } ?: emptyList(),
                    unprocessedCandidates =
                        candidateEntities
                            .filter { it.serverVerdict == 0 }
                            .map {
                                it.toDomain()
                            },
                    yesMeteorites =
                        candidateEntities
                            .filter { it.serverVerdict == 1 }
                            .map {
                                it.toDomain()
                            },
                    noMeteorites =
                        candidateEntities
                            .filter { it.serverVerdict == 2 }
                            .map {
                                it.toDomain()
                            },
                    detectionTags =
                        surveyEntity.detectionTagsJson?.let {
                            detectionTagsAdapter.fromJson(it)
                        } ?: emptyList(),
                    userLocations =
                        surveyEntity.userLocationsJson?.let {
                            userLocationsAdapter.fromJson(it)
                        } ?: emptyList(),
                    showGeolocationAccuracyCircle = surveyEntity.showGeolocationAccuracyCircle,
                    latestTaskCreated = surveyEntity.latestTaskCreated ?: "",
                )
            return Stage4FetchResult.Success(state, isOffline = true)
        }

        private fun CandidateEntity.toDomain(): Stage4Candidate =
            Stage4Candidate(
                inferenceResultId = inferenceResultId,
                imageId = imageId,
                imageFilename = imageFilename,
                imageDims = ImageDims(w = imageWidth, h = imageHeight),
                geoCentroid =
                    if (geoCentroidLat != null && geoCentroidLon != null) {
                        GeoCoordinate(latitude = geoCentroidLat, longitude = geoCentroidLon)
                    } else {
                        null
                    },
                geoArea =
                    geoAreaJson.let {
                        geoAreaAdapter.fromJson(it)
                    },
                box =
                    BoundingBox(
                        x = boxX,
                        y = boxY,
                        w = boxW,
                        h = boxH,
                    ),
                confidence = confidence.toDouble(),
                sizeM =
                    if (sizeMw != null && sizeMh != null) {
                        SizeMetres(
                            w = sizeMw.toDouble(),
                            h = sizeMh.toDouble(),
                        )
                    } else {
                        null
                    },
                claimedByMe = isClaimedByMe,
                claimedByOther = isClaimedByOther,
            )

        private fun Stage4Candidate.toEntity(
            surveyId: Long,
            verdict: Int,
        ): CandidateEntity =
            CandidateEntity(
                inferenceResultId = inferenceResultId,
                surveyId = surveyId,
                imageId = imageId,
                imageFilename = imageFilename,
                imageWidth = imageDims.w,
                imageHeight = imageDims.h,
                geoCentroidLat = geoCentroid?.latitude,
                geoCentroidLon = geoCentroid?.longitude,
                geoAreaJson =
                    geoArea?.let {
                        geoAreaAdapter.toJson(it)
                    } ?: "[]",
                boxX = box.x,
                boxY = box.y,
                boxW = box.w,
                boxH = box.h,
                confidence = confidence.toFloat(),
                sizeMw = sizeM?.w?.toFloat(),
                sizeMh = sizeM?.h?.toFloat(),
                isClaimedByMe = claimedByMe,
                isClaimedByOther = claimedByOther,
                claimOwnerUsername = null, // Not in Stage4Candidate
                serverVerdict = verdict,
            )

        private suspend fun resolveFetchResult(
            response: Response<ResponseBody>,
        ): Stage4FetchResult {
            if (LoginRedirectDetector.isLoginRedirect(response)) {
                return Stage4FetchResult.AuthExpired
            }
            return if (response.isSuccessful) {
                parseBody(response.body())
            } else {
                errorForHttpCode(response.code())
            }
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

        suspend fun getLocalLatestTaskCreated(surveyId: Long): String? =
            withContext(ioDispatcher) {
                surveyDao.getById(surveyId)?.latestTaskCreated
            }

        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun fetchLatestTaskCreated(surveyId: Long): String? =
            withContext(ioDispatcher) {
                try {
                    val response = stage4Service.getCandidates(surveyId.toString())
                    if (LoginRedirectDetector.isLoginRedirect(response)) {
                        null
                    } else if (response.isSuccessful) {
                        (parseBody(response.body()) as? Stage4FetchResult.Success)
                            ?.state
                            ?.latestTaskCreated
                    } else {
                        null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
    }
