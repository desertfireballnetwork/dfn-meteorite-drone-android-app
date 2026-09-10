package au.edu.fireballs.stage4.data.tiles

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
        suspend fun deleteBundle(surveyId: Long) =
            withContext(ioDispatcher) {
                runCatching { tileStore.deleteSurveyTiles(surveyId) }
                runCatching { tileManifestDao.deleteForSurvey(surveyId) }
                runCatching { offlineBundleDao.deleteForSurvey(surveyId) }
            }

        suspend fun deleteAll() =
            withContext(ioDispatcher) {
                runCatching { tileStore.deleteAll() }
                runCatching { tileManifestDao.deleteAll() }
                runCatching { offlineBundleDao.deleteAll() }
            }
    }
