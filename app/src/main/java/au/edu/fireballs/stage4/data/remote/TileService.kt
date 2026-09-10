package au.edu.fireballs.stage4.data.remote

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

interface TileService {
    @GET("image_geotiff_candidate_tile/{survey_id}/{inference_result_id}/{z}/{x}/{y}/")
    suspend fun getCandidateTile(
        @Path("survey_id") surveyId: Long,
        @Path("inference_result_id") inferenceResultId: Long,
        @Path("z") z: Int,
        @Path("x") x: Int,
        @Path("y") y: Int,
    ): Response<ResponseBody>
}
