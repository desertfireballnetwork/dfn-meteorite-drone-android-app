package au.edu.fireballs.stage4.data.remote

import au.edu.fireballs.stage4.data.remote.dto.ClaimRequestDto
import au.edu.fireballs.stage4.data.remote.dto.ClaimResponseDto
import au.edu.fireballs.stage4.data.remote.dto.ListClaimsResponseDto
import au.edu.fireballs.stage4.data.remote.dto.ReleaseRequestDto
import au.edu.fireballs.stage4.data.remote.dto.ReleaseResponseDto
import au.edu.fireballs.stage4.data.remote.dto.SurveyListResponseDto
import au.edu.fireballs.stage4.data.remote.dto.UploadEvidenceResponseDto
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface Stage4Service {
    @GET("api/surveys/")
    suspend fun getSurveys(): Response<SurveyListResponseDto>

    @GET("api/stage4/surveys/{survey_id}/candidates/")
    suspend fun getCandidates(
        @Path("survey_id") surveyId: String,
    ): Response<ResponseBody>

    @POST("api/stage4/surveys/{survey_id}/claims/claim/")
    suspend fun claimCandidate(
        @Path("survey_id") surveyId: String,
        @Body body: ClaimRequestDto,
    ): ClaimResponseDto

    @POST("api/stage4/surveys/{survey_id}/claims/release/")
    suspend fun releaseCandidate(
        @Path("survey_id") surveyId: String,
        @Body body: ReleaseRequestDto,
    ): ReleaseResponseDto

    @GET("api/stage4/surveys/{survey_id}/claims/")
    suspend fun getClaims(
        @Path("survey_id") surveyId: String,
        @Query("mine") mine: Boolean? = null,
    ): ListClaimsResponseDto
}

interface EvidenceService {
    @Multipart
    @POST("api/stage4/surveys/{survey_id}/evidence/")
    suspend fun uploadEvidence(
        @Path("survey_id") surveyId: String,
        @Part file: MultipartBody.Part,
        @Part("inference_result_id") irId: RequestBody,
        @Part("captured_at") capturedAt: RequestBody,
    ): UploadEvidenceResponseDto
}
