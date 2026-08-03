package au.edu.fireballs.stage4.data.remote

import au.edu.fireballs.stage4.data.local.Stage4Database
import au.edu.fireballs.stage4.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.HttpUrl
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountManager
    @Inject
    constructor(
        private val cookieJar: PersistentCookieJar,
        private val baseUrl: HttpUrl,
        private val database: Stage4Database,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        fun isSignedIn(): Boolean {
            val cookies = cookieJar.loadForRequest(baseUrl)
            return cookies.any { it.name == "sessionid" && it.value.isNotBlank() }
        }

        suspend fun logout() =
            withContext(ioDispatcher) {
                cookieJar.clear()
                database.clearAllTables()
            }
    }
