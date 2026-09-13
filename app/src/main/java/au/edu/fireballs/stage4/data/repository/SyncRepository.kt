package au.edu.fireballs.stage4.data.repository

import au.edu.fireballs.stage4.data.local.LocalDecisionEntity
import au.edu.fireballs.stage4.data.local.PendingPhotoUploadEntity
import au.edu.fireballs.stage4.data.local.dao.LocalDecisionDao
import au.edu.fireballs.stage4.data.local.dao.PendingPhotoUploadDao
import au.edu.fireballs.stage4.data.remote.EvidenceService
import au.edu.fireballs.stage4.data.remote.LoginRedirectDetector
import au.edu.fireballs.stage4.data.remote.Stage4Service
import au.edu.fireballs.stage4.di.IoDispatcher
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.time.Instant
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface PhotoUploadResult {
    data object Success : PhotoUploadResult

    data object CrossCampaign : PhotoUploadResult

    data object AuthExpired : PhotoUploadResult

    data object NetworkError : PhotoUploadResult

    data class Error(
        val reason: String,
    ) : PhotoUploadResult
}

sealed interface VerdictPostResult {
    data object Success : VerdictPostResult

    data object ClaimRequired : VerdictPostResult

    data object AlreadyCompleted : VerdictPostResult

    data object AuthExpired : VerdictPostResult

    data object NetworkError : VerdictPostResult

    data class Error(
        val reason: String,
    ) : VerdictPostResult
}

@Singleton
class SyncRepository
    @Inject
    constructor(
        private val evidenceService: EvidenceService,
        private val stage4Service: Stage4Service,
        private val pendingPhotoUploadDao: PendingPhotoUploadDao,
        private val localDecisionDao: LocalDecisionDao,
        private val moshi: Moshi,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        private val uploadIdAdapter: JsonAdapter<UploadId> = moshi.adapter(UploadId::class.java)
        private val conflictAdapter: JsonAdapter<Map<String, String>> =
            moshi.adapter(
                Types.newParameterizedType(
                    Map::class.java,
                    String::class.java,
                    String::class.java,
                ),
            )

        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun uploadPhoto(
            pendingPhoto: PendingPhotoUploadEntity,
            surveyId: Long,
        ): PhotoUploadResult =
            withContext(ioDispatcher) {
                try {
                    val file = File(pendingPhoto.localFilePath)
                    if (!file.exists()) {
                        pendingPhotoUploadDao.markFailed(
                            pendingPhoto.rowId,
                            REASON_FILE_MISSING,
                        )
                        return@withContext PhotoUploadResult.Error(REASON_FILE_MISSING)
                    }
                    val filePart =
                        MultipartBody.Part.createFormData(
                            "file",
                            file.name,
                            file.readBytes().toRequestBody("image/jpeg".toMediaType()),
                        )
                    val inferenceResultId =
                        pendingPhoto.inferenceResultId
                            .toString()
                            .toRequestBody("text/plain".toMediaType())
                    val response =
                        evidenceService.uploadEvidence(
                            surveyId = surveyId.toString(),
                            file = filePart,
                            irId = inferenceResultId,
                        )
                    when {
                        LoginRedirectDetector.isLoginRedirect(response) ->
                            PhotoUploadResult.AuthExpired
                        response.code() == HttpURLConnection.HTTP_CREATED -> {
                            val serverPhotoId = response.body()?.let { parseUploadId(it) }
                            if (serverPhotoId != null) {
                                pendingPhotoUploadDao.markUploaded(
                                    pendingPhoto.rowId,
                                    serverPhotoId,
                                )
                                PhotoUploadResult.Success
                            } else {
                                pendingPhotoUploadDao.markFailed(
                                    pendingPhoto.rowId,
                                    "Missing upload id",
                                )
                                PhotoUploadResult.Error("Missing upload id")
                            }
                        }
                        response.code() == HttpURLConnection.HTTP_FORBIDDEN -> {
                            pendingPhotoUploadDao.markFailed(
                                pendingPhoto.rowId,
                                REASON_CROSS_CAMPAIGN,
                            )
                            PhotoUploadResult.CrossCampaign
                        }
                        response.code() == HttpURLConnection.HTTP_UNAUTHORIZED ->
                            PhotoUploadResult.AuthExpired
                        else -> {
                            pendingPhotoUploadDao.markFailed(
                                pendingPhoto.rowId,
                                "Server returned code: ${response.code()}",
                            )
                            PhotoUploadResult.Error("Server returned code: ${response.code()}")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    PhotoUploadResult.NetworkError
                } catch (e: Exception) {
                    pendingPhotoUploadDao.markFailed(
                        pendingPhoto.rowId,
                        e.localizedMessage ?: "An unexpected error occurred",
                    )
                    PhotoUploadResult.Error(e.localizedMessage ?: "An unexpected error occurred")
                }
            }

        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        private fun parseUploadId(body: ResponseBody): Long? =
            try {
                uploadIdAdapter.fromJson(body.string())?.id
            } catch (e: Exception) {
                null
            }

        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        private fun parseConflictStatus(body: String): String? =
            try {
                conflictAdapter.fromJson(body)?.get("status")
            } catch (e: Exception) {
                null
            }

        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun postVerdict(
            decision: LocalDecisionEntity,
            surveyId: Long,
        ): VerdictPostResult =
            withContext(ioDispatcher) {
                try {
                    val response =
                        stage4Service.postStage4Response(
                            surveyId = surveyId.toString(),
                            inferenceResult = decision.inferenceResultId.toString(),
                            isMeteorite = if (decision.verdict) "true" else "false",
                            detectionTagId = decision.detectionTagId?.toString(),
                        )
                    when {
                        LoginRedirectDetector.isLoginRedirect(response) ->
                            VerdictPostResult.AuthExpired
                        response.code() == HttpURLConnection.HTTP_OK -> {
                            localDecisionDao.markSynced(
                                decision.inferenceResultId,
                                Instant.now().toString(),
                            )
                            VerdictPostResult.Success
                        }
                        response.code() == HttpURLConnection.HTTP_FORBIDDEN -> {
                            val body = response.errorBody()?.string().orEmpty()
                            if (body.trim() == CLAIM_REQUIRED_BODY) {
                                localDecisionDao.markFailed(
                                    decision.inferenceResultId,
                                    REASON_CLAIM_REQUIRED,
                                )
                                VerdictPostResult.ClaimRequired
                            } else {
                                localDecisionDao.markFailed(
                                    decision.inferenceResultId,
                                    "Forbidden: $body",
                                )
                                VerdictPostResult.Error("Forbidden: $body")
                            }
                        }
                        response.code() == HttpURLConnection.HTTP_CONFLICT -> {
                            val body = response.errorBody()?.string().orEmpty()
                            val status = parseConflictStatus(body)
                            if (status == STATUS_ALREADY_COMPLETED) {
                                localDecisionDao.markSynced(
                                    decision.inferenceResultId,
                                    Instant.now().toString(),
                                )
                                VerdictPostResult.AlreadyCompleted
                            } else {
                                localDecisionDao.markFailed(
                                    decision.inferenceResultId,
                                    "Conflict: $body",
                                )
                                VerdictPostResult.Error("Conflict: $body")
                            }
                        }
                        response.code() == HttpURLConnection.HTTP_UNAUTHORIZED ->
                            VerdictPostResult.AuthExpired
                        else -> {
                            localDecisionDao.markFailed(
                                decision.inferenceResultId,
                                "Server returned code: ${response.code()}",
                            )
                            VerdictPostResult.Error("Server returned code: ${response.code()}")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    VerdictPostResult.NetworkError
                } catch (e: HttpException) {
                    if (e.code() == HttpURLConnection.HTTP_UNAUTHORIZED) {
                        VerdictPostResult.AuthExpired
                    } else {
                        localDecisionDao.markFailed(
                            decision.inferenceResultId,
                            e.message() ?: "Verdict post failed",
                        )
                        VerdictPostResult.Error(e.message() ?: "Verdict post failed")
                    }
                } catch (e: Exception) {
                    localDecisionDao.markFailed(
                        decision.inferenceResultId,
                        e.localizedMessage ?: "An unexpected error occurred",
                    )
                    VerdictPostResult.Error(e.localizedMessage ?: "An unexpected error occurred")
                }
            }

        private companion object {
            const val REASON_CROSS_CAMPAIGN = "cross_campaign"
            const val REASON_CLAIM_REQUIRED = "claim_required"
            const val CLAIM_REQUIRED_BODY = "claim-required"
            const val REASON_FILE_MISSING = "file_missing"
            const val STATUS_ALREADY_COMPLETED = "already_completed"
        }
    }

private data class UploadId(
    val id: Long? = null,
)
