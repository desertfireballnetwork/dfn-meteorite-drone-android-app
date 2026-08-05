package au.edu.fireballs.stage4.data.remote

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST

interface AuthService {
    @GET(Endpoints.LOGIN_PATH)
    suspend fun loginForm(): Response<ResponseBody>

    @FormUrlEncoded
    @POST(Endpoints.LOGIN_PATH)
    suspend fun login(
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("csrfmiddlewaretoken") csrfToken: String,
        @Field("next") next: String,
    ): Response<ResponseBody>
}
