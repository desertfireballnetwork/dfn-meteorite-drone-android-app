package au.edu.fireballs.stage4.data.remote

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginRedirectDetectorTest {
    @Test
    fun finalLoginUrlIsDetected() {
        val response =
            response(
                "https://find.gfo.rocks/accounts/login/?next=/image_geotiff_candidate_tile/1/2/20/100/50/",
            )
        assertTrue(LoginRedirectDetector.isLoginRedirect(response))
    }

    @Test
    fun candidateTileUrlIsNotLogin() {
        val response =
            response("https://find.gfo.rocks/image_geotiff_candidate_tile/1/2/20/100/50/")
        assertFalse(LoginRedirectDetector.isLoginRedirect(response))
    }

    @Test
    fun redirectToLoginIsDetected() {
        val response =
            response(
                "https://find.gfo.rocks/image_geotiff_candidate_tile/1/2/20/100/50/",
                code = 302,
                location = "/accounts/login/?next=/image_geotiff_candidate_tile/1/2/20/100/50/",
            )
        assertTrue(LoginRedirectDetector.isLoginRedirect(response))
    }

    private fun response(
        url: String,
        code: Int = 200,
        location: String? = null,
    ): Response {
        val builder =
            Response
                .Builder()
                .request(Request.Builder().url(url).build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("OK")
                .body(ResponseBody.create(null, ""))
        if (location != null) {
            builder.header("Location", location)
        }
        return builder.build()
    }
}
