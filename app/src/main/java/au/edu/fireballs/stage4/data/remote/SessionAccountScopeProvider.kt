package au.edu.fireballs.stage4.data.remote

import au.edu.fireballs.stage4.data.tiles.AccountScopeProvider
import okhttp3.HttpUrl
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionAccountScopeProvider
    @Inject
    constructor(
        private val cookieJar: PersistentCookieJar,
        private val baseUrl: HttpUrl,
    ) : AccountScopeProvider {
        override fun currentScope(): String {
            val sessionId =
                cookieJar
                    .loadForRequest(baseUrl)
                    .firstOrNull { it.name == "sessionid" }
                    ?.value
                    .orEmpty()
            if (sessionId.isBlank()) {
                return UNSIGNED_SCOPE
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(sessionId.toByteArray())
            val hex = digest.joinToString("") { "%02x".format(it) }
            return "session-${hex.take(SCOPE_HASH_CHARS)}"
        }

        companion object {
            const val UNSIGNED_SCOPE = "unsigned"
            private const val SCOPE_HASH_CHARS = 16
        }
    }
