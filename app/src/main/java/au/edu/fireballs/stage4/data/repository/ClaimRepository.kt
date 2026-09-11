package au.edu.fireballs.stage4.data.repository

import au.edu.fireballs.stage4.data.local.ClaimEntity
import au.edu.fireballs.stage4.data.local.dao.ClaimDao
import au.edu.fireballs.stage4.data.remote.Stage4Service
import au.edu.fireballs.stage4.data.remote.dto.ClaimRequestDto
import au.edu.fireballs.stage4.data.remote.dto.ReleaseRequestDto
import au.edu.fireballs.stage4.di.IoDispatcher
import au.edu.fireballs.stage4.domain.model.Claim
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ClaimResult {
    data class Claimed(
        val claimed: List<Long>,
        val alreadyClaimed: List<Long>,
    ) : ClaimResult

    data class Released(
        val released: List<Long>,
    ) : ClaimResult

    data class Listed(
        val claims: List<Claim>,
    ) : ClaimResult

    data class Refreshed(
        val count: Int,
    ) : ClaimResult

    data class Error(
        val message: String? = null,
    ) : ClaimResult

    data object AuthExpired : ClaimResult

    data object NetworkError : ClaimResult
}

@Singleton
class ClaimRepository
    @Inject
    constructor(
        private val stage4Service: Stage4Service,
        private val claimDao: ClaimDao,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        private val surveyIdFlow = MutableStateFlow<Long?>(null)

        val surveyId: StateFlow<Long?> = surveyIdFlow.asStateFlow()

        fun setSurveyId(surveyId: Long) {
            surveyIdFlow.value = surveyId
        }

        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun claim(ids: List<Long>): ClaimResult =
            withContext(ioDispatcher) {
                val surveyId = surveyIdFlow.value
                if (surveyId == null) {
                    return@withContext ClaimResult.Error("No survey selected")
                }
                try {
                    val response =
                        stage4Service.claimCandidate(
                            surveyId = surveyId.toString(),
                            body = ClaimRequestDto(ids),
                        )
                    ClaimResult.Claimed(
                        claimed = response.claimed,
                        alreadyClaimed = response.alreadyClaimed,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    ClaimResult.NetworkError
                } catch (e: HttpException) {
                    if (e.code() == HttpURLConnection.HTTP_UNAUTHORIZED) {
                        ClaimResult.AuthExpired
                    } else {
                        ClaimResult.Error(e.message())
                    }
                } catch (e: Exception) {
                    ClaimResult.Error(e.localizedMessage ?: "An unexpected error occurred")
                }
            }

        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun release(ids: List<Long>): ClaimResult =
            withContext(ioDispatcher) {
                val surveyId = surveyIdFlow.value
                if (surveyId == null) {
                    return@withContext ClaimResult.Error("No survey selected")
                }
                try {
                    val response =
                        stage4Service.releaseCandidate(
                            surveyId = surveyId.toString(),
                            body = ReleaseRequestDto(ids),
                        )
                    ClaimResult.Released(released = response.released)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    ClaimResult.NetworkError
                } catch (e: HttpException) {
                    if (e.code() == HttpURLConnection.HTTP_UNAUTHORIZED) {
                        ClaimResult.AuthExpired
                    } else {
                        ClaimResult.Error(e.message())
                    }
                } catch (e: Exception) {
                    ClaimResult.Error(e.localizedMessage ?: "An unexpected error occurred")
                }
            }

        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun listClaims(mine: Boolean? = null): ClaimResult =
            withContext(ioDispatcher) {
                val surveyId = surveyIdFlow.value
                if (surveyId == null) {
                    return@withContext ClaimResult.Error("No survey selected")
                }
                try {
                    val response =
                        stage4Service.getClaims(
                            surveyId = surveyId.toString(),
                            mine = mine,
                        )
                    ClaimResult.Listed(
                        claims =
                            response.claims.map {
                                Claim(
                                    inferenceResultId = it.inferenceResultId,
                                    userId = it.userId,
                                    username = it.username,
                                    fullName = it.fullName,
                                    claimedAt = it.claimedAt,
                                    isMe = it.isMe,
                                )
                            },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    ClaimResult.NetworkError
                } catch (e: HttpException) {
                    if (e.code() == HttpURLConnection.HTTP_UNAUTHORIZED) {
                        ClaimResult.AuthExpired
                    } else {
                        ClaimResult.Error(e.message())
                    }
                } catch (e: Exception) {
                    ClaimResult.Error(e.localizedMessage ?: "An unexpected error occurred")
                }
            }

        suspend fun countActiveClaimedCandidates(surveyId: Long): Int =
            withContext(ioDispatcher) {
                claimDao.countActiveClaimedCandidates(surveyId)
            }

        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun refreshClaimsToRoom(surveyId: Long): ClaimResult =
            withContext(ioDispatcher) {
                try {
                    val response =
                        stage4Service.getClaims(
                            surveyId = surveyId.toString(),
                            mine = true,
                        )
                    val entities =
                        response.claims.map {
                            ClaimEntity(
                                inferenceResultId = it.inferenceResultId,
                                surveyId = surveyId,
                                userId = it.userId,
                                username = it.username.orEmpty(),
                                claimedAt = it.claimedAt.orEmpty(),
                                isMine = true,
                                isActive = true,
                            )
                        }
                    claimDao.deactivateMineClaimsForSurvey(surveyId)
                    claimDao.upsertAll(entities)
                    ClaimResult.Refreshed(count = entities.size)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    ClaimResult.NetworkError
                } catch (e: HttpException) {
                    if (e.code() == HttpURLConnection.HTTP_UNAUTHORIZED) {
                        ClaimResult.AuthExpired
                    } else {
                        ClaimResult.Error(e.message())
                    }
                } catch (e: Exception) {
                    ClaimResult.Error(e.localizedMessage ?: "An unexpected error occurred")
                }
            }
    }
