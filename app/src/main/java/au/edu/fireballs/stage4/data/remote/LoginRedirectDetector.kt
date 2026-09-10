package au.edu.fireballs.stage4.data.remote

import okhttp3.HttpUrl
import retrofit2.Response
import java.net.HttpURLConnection

object LoginRedirectDetector {
    fun isLoginRedirect(response: Response<*>): Boolean {
        val requestUrl = response.raw().request.url
        var current = response.raw()
        while (true) {
            if (current.code == HttpURLConnection.HTTP_MOVED_TEMP) {
                val location = current.header("Location")
                if (location != null && isLoginLocation(location, requestUrl)) {
                    return true
                }
            }
            val prior = current.priorResponse ?: return false
            current = prior
        }
    }

    private fun isLoginLocation(
        location: String,
        requestUrl: HttpUrl,
    ): Boolean {
        val resolved = requestUrl.resolve(location)
        if (resolved != null) {
            return resolved.encodedPath.contains("login", ignoreCase = true)
        }
        val pathOnly = location.substringBefore('?').substringBefore('#')
        return pathOnly.contains("login", ignoreCase = true)
    }
}
