package au.edu.fireballs.stage4.data.remote

import okhttp3.CookieJar
import okhttp3.HttpUrl
import retrofit2.HttpException
import java.io.IOException
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AuthResult {
    data object Success : AuthResult

    data class Failure(
        val message: String,
    ) : AuthResult

    data object NetworkError : AuthResult
}

@Singleton
class AuthRepository
    @Inject
    constructor(
        private val authService: AuthService,
        private val cookieJar: CookieJar,
        private val baseUrl: HttpUrl,
    ) {
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun login(
            username: String,
            password: String,
        ): AuthResult {
            return try {
                val formResponse = authService.loginForm()
                val htmlBody = formResponse.body()?.string().orEmpty()
                val csrfToken = getCsrfTokenFromCookies() ?: parseCsrfTokenFromHtml(htmlBody)
                if (csrfToken.isBlank()) {
                    return AuthResult.Failure("Unable to retrieve CSRF security token")
                }

                val loginResponse =
                    authService.login(
                        username = username,
                        password = password,
                        csrfToken = csrfToken,
                        next = Endpoints.NEXT_AFTER_LOGIN,
                    )

                val code = loginResponse.code()
                val rawResponse = loginResponse.raw()

                if (code == 302 || rawResponse.priorResponse?.code == 302) { // 302 redirect is ok
                    AuthResult.Success
                } else if (code == 200) { // 200 OK with HTML error message
                    val responseHtml = loginResponse.body()?.string().orEmpty()
                    val errorMessage = parseDjangoErrorMessage(responseHtml) ?: "Login failed"
                    AuthResult.Failure(errorMessage)
                } else {
                    AuthResult.Failure("Login failed (Server error $code)")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                AuthResult.NetworkError
            } catch (e: HttpException) {
                AuthResult.Failure("HTTP error ${e.code()}: ${e.message()}")
            } catch (e: Exception) {
                AuthResult.Failure("An unexpected error occurred: ${e.localizedMessage}")
            }
        }

        private fun getCsrfTokenFromCookies(): String? =
            cookieJar
                .loadForRequest(baseUrl)
                .firstOrNull {
                    it.name == "csrftoken"
                }?.value

        private fun parseCsrfTokenFromHtml(html: String): String {
            val regex = """name=["']csrfmiddlewaretoken["']\s+value=["']([^"']+)["']""".toRegex()
            return regex.find(html)?.groupValues?.get(1) ?: ""
        }

        private fun parseDjangoErrorMessage(html: String): String? {
            val alertRegex =
                """<div[^>]*class=["'][^"']*alert-danger[^"']*["'][^>]*>(.*?)</div>""".toRegex(
                    RegexOption.DOT_MATCHES_ALL,
                )
            val match = alertRegex.find(html)
            return match
                ?.groupValues
                ?.get(1)
                ?.replace("""<[^>]*>""".toRegex(), "")
                ?.trim()
        }
    }
