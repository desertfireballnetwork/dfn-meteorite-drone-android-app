package au.edu.fireballs.stage4.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import au.edu.fireballs.stage4.data.local.dao.CandidateDao
import au.edu.fireballs.stage4.data.local.dao.ClaimDao
import au.edu.fireballs.stage4.data.local.dao.SurveyDao
import au.edu.fireballs.stage4.data.remote.TileService
import au.edu.fireballs.stage4.data.repository.CandidateImageRepository
import au.edu.fireballs.stage4.data.repository.ClaimRepository
import au.edu.fireballs.stage4.data.repository.OfflineWorkingSetRepository
import au.edu.fireballs.stage4.data.repository.PreDownloadStoragePreflight
import au.edu.fireballs.stage4.data.repository.Stage4Repository
import au.edu.fireballs.stage4.data.repository.StorageCoordinator
import au.edu.fireballs.stage4.data.tiles.BufferRadiusRepository
import au.edu.fireballs.stage4.data.tiles.GeotiffRadiusRepository
import au.edu.fireballs.stage4.data.tiles.LowZoomTileCompositor
import au.edu.fireballs.stage4.data.tiles.OfflineManagerWrapper
import au.edu.fireballs.stage4.data.tiles.SatelliteRegionStore
import au.edu.fireballs.stage4.data.tiles.TileStore
import au.edu.fireballs.stage4.di.IoDispatcher
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher

@HiltWorker
class PreDownloadWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val claimRepository: ClaimRepository,
        private val stage4Repository: Stage4Repository,
        private val candidateDao: CandidateDao,
        private val claimDao: ClaimDao,
        private val surveyDao: SurveyDao,
        private val tileStore: TileStore,
        private val tileService: TileService,
        private val offlineManagerWrapper: OfflineManagerWrapper,
        private val bufferRadiusRepository: BufferRadiusRepository,
        private val candidateImageRepository: CandidateImageRepository,
        private val geotiffRadiusRepository: GeotiffRadiusRepository,
        private val satelliteRegionStore: SatelliteRegionStore,
        private val storageCoordinator: StorageCoordinator,
        private val workingSetRepository: OfflineWorkingSetRepository,
        private val preflight: PreDownloadStoragePreflight,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            val surveyId = inputData.getLong(KEY_SURVEY_ID, -1L)
            if (surveyId < 0L) {
                return Result.failure()
            }
            val bufferMeters =
                inputData.getFloat(
                    KEY_BUFFER_METERS,
                    bufferRadiusRepository.getBufferRadiusMeters(),
                )
            val geotiffRadiusMeters =
                inputData.getFloat(
                    KEY_GEOTIFF_RADIUS_METERS,
                    geotiffRadiusRepository.getRadiusMeters(),
                )
            geotiffRadiusRepository.setRadiusMeters(geotiffRadiusMeters)
            val orchestrator =
                PreDownloadOrchestrator(
                    claimRepository = claimRepository,
                    stage4Repository = stage4Repository,
                    candidateDao = candidateDao,
                    claimDao = claimDao,
                    surveyDao = surveyDao,
                    tileStore = tileStore,
                    lowZoomCompositor = LowZoomTileCompositor(tileStore),
                    tileService = tileService,
                    offlineManagerWrapper = offlineManagerWrapper,
                    candidateImageRepository = candidateImageRepository,
                    geotiffRadiusRepository = geotiffRadiusRepository,
                    satelliteRegionStore = satelliteRegionStore,
                    storageCoordinator = storageCoordinator,
                    workingSetRepository = workingSetRepository,
                    preflight = preflight,
                    ioDispatcher = ioDispatcher,
                )
            return when (
                val outcome =
                    orchestrator.run(surveyId, bufferMeters) { setProgress(it) }
            ) {
                is PreDownloadOutcome.Success -> Result.success(outcome.outputData)
                is PreDownloadOutcome.Failure -> Result.failure(outcome.outputData)
            }
        }

        companion object {
            const val KEY_SURVEY_ID = "surveyId"
            const val KEY_BUFFER_METERS = "bufferMeters"
            const val KEY_GEOTIFF_RADIUS_METERS = "geotiffRadiusMeters"
        }
    }
