package au.edu.fireballs.stage4.data.remote

import au.edu.fireballs.stage4.data.local.Stage4Database
import au.edu.fireballs.stage4.data.tiles.OfflineRegionWrapper
import au.edu.fireballs.stage4.data.tiles.TileStore
import au.edu.fireballs.stage4.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class AccountManager
    @Inject
    constructor(
        private val cookieJar: PersistentCookieJar,
        private val baseUrl: HttpUrl,
        private val database: Stage4Database,
        private val tileStore: TileStore,
        private val offlineRegionWrapper: OfflineRegionWrapper,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        fun isSignedIn(): Boolean {
            val cookies = cookieJar.loadForRequest(baseUrl)
            return cookies.any { it.name == "sessionid" && it.value.isNotBlank() }
        }

        suspend fun logout(): Unit =
            withContext(ioDispatcher) {
                var primary: Throwable? = null
                try {
                    suspendCancellableCoroutine { continuation ->
                        offlineRegionWrapper.purgeAllRegions { result ->
                            result.fold(
                                onSuccess = { continuation.resume(Unit) },
                                onFailure = {
                                    primary = it
                                    continuation.resume(Unit)
                                },
                            )
                        }
                    }
                } finally {
                    val cleanups =
                        listOf<() -> Unit>(
                            { cookieJar.clear() },
                            { tileStore.deleteAll() },
                            { database.clearAllTables() },
                        )
                    cleanups.forEach { cleanup ->
                        runCatching { cleanup() }.exceptionOrNull()?.let { error ->
                            if (primary == null) {
                                primary = error
                            } else {
                                primary?.addSuppressed(error)
                            }
                        }
                    }
                }
                primary?.let { throw it }
            }
    }
