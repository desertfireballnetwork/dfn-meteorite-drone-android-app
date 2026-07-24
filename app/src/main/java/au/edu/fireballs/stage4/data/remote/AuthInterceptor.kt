package au.edu.fireballs.stage4.data.remote

import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(
    private val cookieJar: CookieJar,
    private val productionServerUrl: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val method = originalRequest.method.uppercase()

        if (method == "GET" || method == "HEAD") {
            return chain.proceed(originalRequest)
        }

        val builder = originalRequest.newBuilder()

        val cookies = cookieJar.loadForRequest(originalRequest.url)
        val csrfCookie = cookies.firstOrNull { it.name.equals(CSRF_COOKIE_NAME, ignoreCase = true) }

        if (csrfCookie != null) {
            builder.header(CSRF_HEADER_NAME, csrfCookie.value)
        }

        if (productionServerUrl.isNotBlank()) {
            builder.header(ORIGIN_HEADER_NAME, productionServerUrl)
        }

        return chain.proceed(builder.build())
    }

    companion object {
        const val CSRF_COOKIE_NAME = "csrftoken"
        const val CSRF_HEADER_NAME = "X-CSRFToken"
        const val ORIGIN_HEADER_NAME = "Origin"
    }
}
