package au.edu.fireballs.stage4.data.remote

import androidx.annotation.StringRes
import au.edu.fireballs.stage4.R
import au.edu.fireballs.stage4.data.repository.SelectedSurveyRepository
import au.edu.fireballs.stage4.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.io.IOException
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AuthResult {
    data object Success : AuthResult

    data class Failure(
        val message: String? = null,
        @param:StringRes val messageResId: Int? = null,
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
        private val selectedSurveyRepository: SelectedSurveyRepository,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun login(
            username: String,
            password: String,
        ): AuthResult =
            withContext(ioDispatcher) {
                try {
                    val formResponse = authService.loginForm()
                    val htmlBody = formResponse.body()?.string().orEmpty()
                    val csrfToken = getCsrfTokenFromCookies() ?: parseCsrfTokenFromHtml(htmlBody)
                    if (csrfToken.isBlank()) {
                        return@withContext AuthResult.Failure(messageResId = R.string.login_failed)
                    }

                    val loginResponse =
                        authService.login(
                            username = username,
                            password = password,
                            csrfToken = csrfToken,
                            next = Endpoints.NEXT_AFTER_LOGIN,
                        )

                    // loginResponse body is closed upon exit in the finally
                    try {
                        val code = loginResponse.code()
                        val rawResponse = loginResponse.raw()

                        // Extract Location header from current response or prior redirect response
                        val redirectLocation =
                            loginResponse.headers()["Location"]
                                ?: rawResponse.priorResponse?.header("Location")

                        // Check if a NEW sessionid cookie was actually issued in this response sequence
                        val receivedInMainResponse =
                            loginResponse
                                .headers()
                                .values("Set-Cookie")
                                .any { it.contains("${AuthConstants.SESSION_COOKIE_NAME}=") }

                        val receivedInPriorResponse =
                            rawResponse.priorResponse
                                ?.headers
                                ?.values("Set-Cookie")
                                ?.any { it.contains("${AuthConstants.SESSION_COOKIE_NAME}=") } ==
                                true

                        val receivedNewSessionCookie =
                            receivedInMainResponse || receivedInPriorResponse
                        val isRedirectToExpectedTarget =
                            redirectLocation?.contains(Endpoints.NEXT_AFTER_LOGIN) == true

                        // Verify status code + target location + new session cookie
                        if ((code == 302 || rawResponse.priorResponse?.code == 302) &&
                            isRedirectToExpectedTarget &&
                            receivedNewSessionCookie
                        ) {
                            selectedSurveyRepository.setUsername(username)
                            AuthResult.Success
                        } else if (code == 200) { // 200 OK with HTML error message
                            val responseHtml = loginResponse.body()?.string().orEmpty()
                            val errorMessage = parseDjangoErrorMessage(responseHtml)
                            if (errorMessage != null) {
                                AuthResult.Failure(message = errorMessage)
                            } else {
                                AuthResult.Failure(messageResId = R.string.login_failed)
                            }
                        } else {
                            AuthResult.Failure(messageResId = R.string.login_failed)
                        }
                    } finally {
                        loginResponse.body()?.close()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    AuthResult.NetworkError
                } catch (e: Exception) {
                    AuthResult.Failure(
                        message = "An unexpected error occurred: ${e.localizedMessage}",
                    )
                }
            }

        private fun getCsrfTokenFromCookies(): String? =
            cookieJar
                .loadForRequest(baseUrl)
                .firstOrNull {
                    it.name == AuthConstants.CSRF_COOKIE_NAME
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
