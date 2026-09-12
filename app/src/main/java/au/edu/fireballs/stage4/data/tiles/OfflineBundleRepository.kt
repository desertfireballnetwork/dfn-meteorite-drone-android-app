package au.edu.fireballs.stage4.data.tiles

import au.edu.fireballs.stage4.data.local.OfflineBundleEntity
import au.edu.fireballs.stage4.data.local.dao.OfflineBundleDao
import au.edu.fireballs.stage4.data.local.dao.TileManifestDao
import au.edu.fireballs.stage4.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OfflineBundleRepository
    @Inject
    constructor(
        private val tileStore: TileStore,
        private val tileManifestDao: TileManifestDao,
        private val offlineBundleDao: OfflineBundleDao,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        suspend fun insertBundle(bundle: OfflineBundleEntity): Long =
            withContext(ioDispatcher) {
                offlineBundleDao.insert(bundle)
            }

        suspend fun deleteBundle(surveyId: Long) =
            withContext(ioDispatcher) {
                val cleanups =
                    listOf<suspend () -> Unit>(
                        { tileStore.deleteSurveyTiles(surveyId) },
                        { tileManifestDao.deleteForSurvey(surveyId) },
                        { offlineBundleDao.deleteForSurvey(surveyId) },
                    )
                runCleanups(cleanups)
            }

        suspend fun deleteAll() =
            withContext(ioDispatcher) {
                val cleanups =
                    listOf<suspend () -> Unit>(
                        { tileStore.deleteAll() },
                        { tileManifestDao.deleteAll() },
                        { offlineBundleDao.deleteAll() },
                    )
                runCleanups(cleanups)
            }

        private suspend fun runCleanups(cleanups: List<suspend () -> Unit>) {
            var failure: Throwable? = null
            cleanups.forEach { cleanup ->
                runCatching { cleanup() }.exceptionOrNull()?.let { error ->
                    if (failure == null) {
                        failure = error
                    } else {
                        failure?.addSuppressed(error)
                    }
                }
            }
            failure?.let { throw it }
        }
    }
