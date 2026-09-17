package au.edu.fireballs.stage4.data.repository

import au.edu.fireballs.stage4.data.local.dao.CandidateDao
import au.edu.fireballs.stage4.data.local.dao.ClaimDao
import au.edu.fireballs.stage4.data.tiles.SatelliteRegionStore
import au.edu.fireballs.stage4.data.tiles.TileStore
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreDownloadStoragePreflight
    @Inject
    constructor(
        private val candidateDao: CandidateDao,
        private val claimDao: ClaimDao,
        private val tileStore: TileStore,
        private val candidateImageRepository: CandidateImageRepository,
        private val satelliteRegionStore: SatelliteRegionStore,
        private val storageCoordinator: StorageCoordinator,
    ) {
        suspend fun evaluate(
            surveyId: Long,
            bufferRadiusMeters: Double,
            geotiffRadiusMeters: Double,
        ): PreDownloadPreflightResult {
            val activeMine =
                claimDao
                    .getClaims(surveyId, onlyActive = true)
                    .first()
                    .filter { it.isMine && it.isActive }
                    .map { it.inferenceResultId }
                    .toSet()
            val candidates =
                candidateDao
                    .getCandidatesForSurvey(surveyId)
                    .filter { it.inferenceResultId in activeMine }
                    .mapNotNull { candidate ->
                        val lat = candidate.geoCentroidLat
                        val lon = candidate.geoCentroidLon
                        if (lat == null || lon == null) {
                            null
                        } else {
                            PreDownloadTargetCandidate(
                                candidate.inferenceResultId,
                                lat,
                                lon,
                            )
                        }
                    }

            var geotiffPresentCount = 0
            var geotiffMissingCount = 0
            for (candidate in candidates) {
                val tiles =
                    PreDownloadTargetPlanner.tilesForCandidate(
                        candidate,
                        geotiffRadiusMeters,
                    )
                for (tile in tiles) {
                    if (
                        tileStore.contains(
                            surveyId,
                            candidate.inferenceResultId,
                            tile.z,
                            tile.x,
                            tile.y,
                        )
                    ) {
                        geotiffPresentCount++
                    } else {
                        geotiffMissingCount++
                    }
                }
            }

            val cropPresentCount =
                candidates.count { candidate ->
                    candidateImageRepository.getLocalCropImageFile(
                        surveyId,
                        candidate.inferenceResultId,
                    ) != null
                }
            val clusters =
                PreDownloadTargetPlanner.cluster(
                    candidates,
                    bufferRadiusMeters,
                )
            val satelliteTargets =
                PreDownloadTargetPlanner.satelliteTargets(
                    clusters,
                    bufferRadiusMeters,
                )
            var satellitePresentCount = 0
            for (target in satelliteTargets) {
                if (satelliteRegionStore.contains(surveyId, target.signature)) {
                    satellitePresentCount++
                }
            }

            val inventory =
                PreDownloadInventory(
                    geotiffPresentCount = geotiffPresentCount,
                    geotiffMissingCount = geotiffMissingCount,
                    cropPresentCount = cropPresentCount,
                    cropMissingCount = candidates.size - cropPresentCount,
                    satellitePresentCount = satellitePresentCount,
                    satelliteMissingCount =
                        satelliteTargets.size - satellitePresentCount,
                )
            val snapshot = storageCoordinator.snapshot()
            return PreDownloadSpaceCalculator.evaluate(
                inventory,
                snapshot.availableVolumeBytes,
                snapshot.totalVolumeBytes,
            )
        }
    }
