package au.edu.fireballs.stage4.data.repository

import au.edu.fireballs.stage4.data.local.CandidateEntity
import au.edu.fireballs.stage4.data.local.ClaimEntity
import au.edu.fireballs.stage4.data.local.dao.CandidateDao
import au.edu.fireballs.stage4.data.local.dao.ClaimDao
import au.edu.fireballs.stage4.data.tiles.SatelliteRegionStore
import au.edu.fireballs.stage4.data.tiles.TileStore
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import java.io.File

class PreDownloadStoragePreflightTest {
    private val candidateDao = mock<CandidateDao>()
    private val claimDao = mock<ClaimDao>()
    private val tileStore = mock<TileStore>()
    private val candidateImageRepository = mock<CandidateImageRepository>()
    private val satelliteRegionStore = mock<SatelliteRegionStore>()
    private val storageCoordinator = mock<StorageCoordinator>()
    private val service =
        PreDownloadStoragePreflight(
            candidateDao,
            claimDao,
            tileStore,
            candidateImageRepository,
            satelliteRegionStore,
            storageCoordinator,
        )

    @Test
    fun `empty inventory is allowed and uses snapshot capacity`() =
        runTest {
            stubCandidates(emptyList())
            stubSnapshot(10L * GIB, 20L * GIB)

            val result = service.evaluate(SURVEY_ID, 100.0, 10.0, false)

            assertTrue(result is PreDownloadPreflightResult.Allowed)
            val estimate = result.estimate()
            assertEquals(emptyInventory(), estimate.inventory)
            assertEquals(10L * GIB, estimate.availableBytes)
            assertEquals(20L * GIB, estimate.totalVolumeBytes)
            verify(storageCoordinator).snapshot()
            verifyNoMoreInteractions(storageCoordinator)
        }

    @Test
    fun `partially present inventory reports counts and insufficient space`() =
        runTest {
            val candidates = listOf(candidate(1L), candidate(2L, lon = 145.01))
            stubCandidates(candidates)
            val target =
                PreDownloadTargetCandidate(
                    candidates.first().inferenceResultId,
                    candidates.first().geoCentroidLat!!,
                    candidates.first().geoCentroidLon!!,
                )
            val firstTile =
                PreDownloadTargetPlanner.tilesForCandidate(target, 10.0).first()
            whenever(
                tileStore.contains(
                    eq(SURVEY_ID),
                    eq(1L),
                    eq(firstTile.z),
                    eq(firstTile.x),
                    eq(firstTile.y),
                ),
            ).thenReturn(true)
            whenever(
                candidateImageRepository.getLocalCropImageFile(SURVEY_ID, 1L),
            ).thenReturn(File("crop"))
            whenever(satelliteRegionStore.contains(eq(SURVEY_ID), any()))
                .thenReturn(false)
            stubSnapshot(0L, 20L * GIB)

            val result = service.evaluate(SURVEY_ID, 100.0, 10.0, false)

            assertTrue(result is PreDownloadPreflightResult.InsufficientDeviceSpace)
            val inventory = result.estimate().inventory
            val totalTiles = candidates.sumOf { entity -> tileCount(entity) }
            assertEquals(1, inventory.geotiffPresentCount)
            assertEquals(totalTiles - 1, inventory.geotiffMissingCount)
            assertEquals(1, inventory.cropPresentCount)
            assertEquals(1, inventory.cropMissingCount)
            assertEquals(0, inventory.satellitePresentCount)
            assertEquals(2, inventory.satelliteMissingCount)
            verify(storageCoordinator).snapshot()
            verifyNoMoreInteractions(storageCoordinator)
        }

    @Test
    fun replacementRequiredCountsPresentContentAsMissing() =
        runTest {
            val candidates = listOf(candidate(1L), candidate(2L, lon = 145.01))
            stubCandidates(candidates)
            whenever(tileStore.contains(eq(SURVEY_ID), eq(1L), any(), any(), any()))
                .thenReturn(true)
            whenever(
                candidateImageRepository.getLocalCropImageFile(SURVEY_ID, 1L),
            ).thenReturn(File("crop"))
            whenever(satelliteRegionStore.contains(eq(SURVEY_ID), any()))
                .thenReturn(true)
            stubSnapshot(10L * GIB, 20L * GIB)

            val result =
                service.evaluate(
                    SURVEY_ID,
                    100.0,
                    10.0,
                    replacementRequired = true,
                )

            val inventory = result.estimate().inventory
            val totalTiles = candidates.sumOf { entity -> tileCount(entity) }
            assertEquals(0, inventory.geotiffPresentCount)
            assertEquals(totalTiles, inventory.geotiffMissingCount)
            assertEquals(0, inventory.cropPresentCount)
            assertEquals(candidates.size, inventory.cropMissingCount)
            assertEquals(0, inventory.satellitePresentCount)
            assertEquals(candidates.size, inventory.satelliteMissingCount)
        }

    fun `fully present inventory has no missing items`() =
        runTest {
            val candidates = listOf(candidate(1L))
            stubCandidates(candidates)
            whenever(tileStore.contains(eq(SURVEY_ID), eq(1L), any(), any(), any()))
                .thenReturn(true)
            whenever(
                candidateImageRepository.getLocalCropImageFile(SURVEY_ID, 1L),
            ).thenReturn(File("crop"))
            whenever(satelliteRegionStore.contains(eq(SURVEY_ID), any()))
                .thenReturn(true)
            stubSnapshot(10L * GIB, 20L * GIB)

            val result = service.evaluate(SURVEY_ID, 100.0, 10.0, false)

            assertTrue(result is PreDownloadPreflightResult.Allowed)
            val inventory = result.estimate().inventory
            assertEquals(tileCount(candidates.single()), inventory.geotiffPresentCount)
            assertEquals(0, inventory.geotiffMissingCount)
            assertEquals(1, inventory.cropPresentCount)
            assertEquals(0, inventory.cropMissingCount)
            assertEquals(1, inventory.satellitePresentCount)
            assertEquals(0, inventory.satelliteMissingCount)
            verify(storageCoordinator).snapshot()
            verifyNoMoreInteractions(storageCoordinator)
        }

    @Test
    fun `identical target state yields equal reports`() =
        runTest {
            val candidates = listOf(candidate(1L), candidate(2L, lon = 145.01))
            stubCandidates(candidates)
            whenever(tileStore.contains(eq(SURVEY_ID), any(), any(), any(), any()))
                .thenReturn(false)
            whenever(candidateImageRepository.getLocalCropImageFile(eq(SURVEY_ID), any()))
                .thenReturn(null)
            whenever(satelliteRegionStore.contains(eq(SURVEY_ID), any()))
                .thenReturn(false)
            stubSnapshot(10L * GIB, 20L * GIB)

            val first = service.evaluate(SURVEY_ID, 100.0, 10.0, false)
            val second = service.evaluate(SURVEY_ID, 100.0, 10.0, false)
            val firstEstimate = first.estimate()
            val secondEstimate = second.estimate()

            assertEquals(first, second)
            assertEquals(
                firstEstimate.incrementalRequiredBytes,
                secondEstimate.incrementalRequiredBytes,
            )
            assertEquals(firstEstimate.availableBytes, secondEstimate.availableBytes)
            assertEquals(firstEstimate.reserveBytes, secondEstimate.reserveBytes)
            assertEquals(
                firstEstimate.expectedRemainingBytes,
                secondEstimate.expectedRemainingBytes,
            )
        }

    @Test
    fun `replacement accounting credits confidently deletable local bytes`() =
        runTest {
            val available = 2L * GIB + 100L * MIB
            stubSnapshot(available, 10L * GIB)
            val classification =
                ReplacementClassification(
                    retained = emptySet(),
                    missing = missingCrops(1),
                    obsolete = emptySet(),
                    clearCommands = emptyList(),
                    confidentlyDeletableBytes = 10L * MIB,
                )

            val result = service.evaluateReplacement(classification)

            assertTrue(result is PreDownloadPreflightResult.Allowed)
            val estimate = result.estimate()
            assertEquals(1L * MIB, estimate.incrementalRequiredBytes)
            assertEquals(10L * MIB, estimate.confidentlyDeletableBytes)
            assertEquals(
                available + 10L * MIB - 1L * MIB,
                estimate.expectedRemainingBytes,
            )
            verify(storageCoordinator).snapshot()
            verifyNoMoreInteractions(storageCoordinator)
        }

    @Test
    fun `replacement accounting counts retained items as present`() =
        runTest {
            val retained = missingCrops(1)
            val missing = missingCrops(1, offset = 1)
            val classification =
                ReplacementClassification(
                    retained = retained,
                    missing = missing,
                    obsolete = emptySet(),
                    clearCommands = emptyList(),
                    confidentlyDeletableBytes = 0L,
                )
            stubSnapshot(10L * GIB, 10L * GIB)

            val result = service.evaluateReplacement(classification)

            val inventory = result.estimate().inventory
            assertEquals(0, inventory.geotiffPresentCount)
            assertEquals(1, inventory.cropPresentCount)
            assertEquals(1, inventory.cropMissingCount)
            assertEquals(1L * MIB, result.estimate().incrementalRequiredBytes)
        }

    @Test
    fun `pending mapbox deletion receives zero freed space credit`() =
        runTest {
            val obsoleteSatellite =
                PreDownloadTargetKey.Satellite(SURVEY_ID, "src", "sat:old")
            val missingSatellite =
                PreDownloadTargetKey.Satellite(SURVEY_ID, "src", "sat:new")
            val classification =
                ReplacementClassifier.classify(
                    target =
                        PreDownloadTargetSet(
                            emptyList(),
                            emptyList(),
                            listOf(missingSatellite),
                        ),
                    owned =
                        listOf(
                            PreDownloadOwnedPayload(
                                obsoleteSatellite,
                                valid = false,
                                measuredDeletableBytes = 50L * MIB,
                            ),
                        ),
                )
            stubSnapshot(2L * GIB, 0L)

            val result = service.evaluateReplacement(classification)

            assertEquals(0L, result.estimate().confidentlyDeletableBytes)
            assertEquals(
                2L * GIB - 50L * MIB,
                result.estimate().expectedRemainingBytes,
            )
        }

    @Test
    fun `replacement preflight crosses reserve boundary at exact value`() =
        runTest {
            val reserve = PreDownloadSpaceCalculator.MINIMUM_RESERVE_BYTES
            val required = PreDownloadSpaceCalculator.CROP_ESTIMATE_BYTES
            val deletable = 4L * MIB
            val available = reserve + required - deletable
            val classification =
                ReplacementClassification(
                    retained = emptySet(),
                    missing = missingCrops(1),
                    obsolete = emptySet(),
                    clearCommands = emptyList(),
                    confidentlyDeletableBytes = deletable,
                )

            stubSnapshot(available, 0L)
            val equal = service.evaluateReplacement(classification)
            assertTrue(equal is PreDownloadPreflightResult.Allowed)
            assertEquals(reserve, equal.estimate().expectedRemainingBytes)

            stubSnapshot(available - 1L, 0L)
            val below = service.evaluateReplacement(classification)
            assertTrue(below is PreDownloadPreflightResult.InsufficientDeviceSpace)

            stubSnapshot(available + 1L, 0L)
            val above = service.evaluateReplacement(classification)
            assertTrue(above is PreDownloadPreflightResult.Allowed)
        }

    private suspend fun stubCandidates(candidates: List<CandidateEntity>) {
        whenever(candidateDao.getCandidatesForSurvey(SURVEY_ID)).thenReturn(candidates)
        val claims = candidates.map { claim(it.inferenceResultId) }
        whenever(claimDao.getClaims(SURVEY_ID, onlyActive = true))
            .thenReturn(flowOf(claims))
    }

    private suspend fun stubSnapshot(
        availableBytes: Long,
        totalBytes: Long,
    ) {
        whenever(storageCoordinator.snapshot())
            .thenReturn(
                StorageUsageSnapshot(
                    availableVolumeBytes = availableBytes,
                    totalVolumeBytes = totalBytes,
                    geotiffBytes = 0L,
                    candidateCropBytes = 0L,
                    evidenceBytes = 0L,
                    ownedTempCacheBytes = 0L,
                    mapboxRegionCount = 0,
                    mapboxBytes = 0L,
                ),
            )
    }

    private fun candidate(
        id: Long,
        lat: Double = -37.0,
        lon: Double = 145.0,
    ): CandidateEntity =
        mock {
            on { inferenceResultId }.thenReturn(id)
            on { geoCentroidLat }.thenReturn(lat)
            on { geoCentroidLon }.thenReturn(lon)
        }

    private fun claim(id: Long): ClaimEntity =
        mock {
            on { inferenceResultId }.thenReturn(id)
            on { isMine }.thenReturn(true)
            on { isActive }.thenReturn(true)
        }

    private fun tileCount(candidate: CandidateEntity): Int =
        PreDownloadTargetPlanner
            .tilesForCandidate(
                PreDownloadTargetCandidate(
                    candidate.inferenceResultId,
                    candidate.geoCentroidLat!!,
                    candidate.geoCentroidLon!!,
                ),
                10.0,
            ).size

    private fun PreDownloadPreflightResult.estimate(): PreDownloadSpaceEstimate =
        when (this) {
            is PreDownloadPreflightResult.Allowed -> estimate
            is PreDownloadPreflightResult.InsufficientDeviceSpace -> estimate
            PreDownloadPreflightResult.StorageOperationActive ->
                error("Unexpected storage operation")
        }

    private fun emptyInventory(): PreDownloadInventory = PreDownloadInventory(0, 0, 0, 0, 0, 0)

    private fun missingCrops(
        count: Int,
        offset: Int = 0,
    ): Set<PreDownloadTargetKey> =
        (1..count)
            .map { index ->
                val candidateId = (index + offset).toLong()
                PreDownloadTargetKey.Crop(
                    surveyId = SURVEY_ID,
                    candidateId = candidateId,
                    sourceVersion = "src",
                    requestSignature = "crop:$SURVEY_ID:$candidateId",
                )
            }.toSet()

    private companion object {
        const val SURVEY_ID = 7L
        const val MIB = 1024L * 1024L
        const val GIB = 1024L * 1024L * 1024L
    }
}
