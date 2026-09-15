package au.edu.fireballs.stage4.data.remote

import au.edu.fireballs.stage4.data.remote.dto.ClaimRequestDto
import au.edu.fireballs.stage4.data.remote.dto.ClaimResponseDto
import au.edu.fireballs.stage4.data.remote.dto.EvidenceListResponseDto
import au.edu.fireballs.stage4.data.remote.dto.ListClaimsResponseDto
import au.edu.fireballs.stage4.data.remote.dto.ReleaseRequestDto
import au.edu.fireballs.stage4.data.remote.dto.ReleaseResponseDto
import au.edu.fireballs.stage4.data.remote.dto.SurveyListResponseDto
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
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

    @FormUrlEncoded
    @POST("survey/{survey_id}/stage4/set_car_location/")
    suspend fun setCarLocation(
        @Path("survey_id") surveyId: String,
        @Field("latitude") latitude: String,
        @Field("longitude") longitude: String,
    ): Response<ResponseBody>

    @FormUrlEncoded
    @POST("survey/{survey_id}/stage4/response/")
    suspend fun postStage4Response(
        @Path("survey_id") surveyId: String,
        @Field("inference_result") inferenceResult: String,
        @Field("is_meteorite") isMeteorite: String,
        @Field("detection_tag") detectionTagId: String?,
    ): Response<ResponseBody>
}

interface EvidenceService {
    @Multipart
    @POST("api/stage4/surveys/{survey_id}/evidence/")
    suspend fun uploadEvidence(
        @Path("survey_id") surveyId: String,
        @Part file: MultipartBody.Part,
        @Part("inference_result_id") irId: RequestBody,
    ): Response<ResponseBody>

    @GET("api/stage4/surveys/{survey_id}/candidates/{inference_result_id}/evidence/")
    suspend fun listEvidencePhotos(
        @Path("survey_id") surveyId: String,
        @Path("inference_result_id") inferenceResultId: String,
    ): Response<EvidenceListResponseDto>

    @GET("api/stage4/evidence/{photo_id}/")
    suspend fun viewEvidencePhoto(
        @Path("photo_id") photoId: Long,
    ): Response<ResponseBody>
}
