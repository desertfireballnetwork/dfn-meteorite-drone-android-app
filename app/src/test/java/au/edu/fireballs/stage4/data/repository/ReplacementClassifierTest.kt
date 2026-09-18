package au.edu.fireballs.stage4.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplacementClassifierTest {
    @Test
    fun `partial overlap partitions retained missing and obsolete`() {
        val retainedTile = tile(candidateId = 1L)
        val missingTile = tile(candidateId = 2L)
        val retainedCrop = crop(candidateId = 1L)
        val missingSatellite = satellite(signature = "sat:1:18-22")
        val obsoleteTile = tile(candidateId = 9L)
        val obsoleteCrop = crop(candidateId = 8L)
        val obsoleteSatellite = satellite(signature = "sat:7:18-22")
        val target =
            PreDownloadTargetSet(
                geotiffTiles = listOf(retainedTile, missingTile),
                crops = listOf(retainedCrop),
                satellites = listOf(missingSatellite),
            )

        val result =
            ReplacementClassifier.classify(
                target,
                listOf(
                    PreDownloadOwnedPayload(
                        retainedTile,
                        valid = true,
                        measuredDeletableBytes = 10L,
                    ),
                    PreDownloadOwnedPayload(retainedCrop, valid = true),
                    PreDownloadOwnedPayload(
                        obsoleteTile,
                        valid = true,
                        measuredDeletableBytes = 20L,
                    ),
                    PreDownloadOwnedPayload(obsoleteCrop, valid = false),
                    PreDownloadOwnedPayload(obsoleteSatellite, valid = false),
                ),
            )

        assertEquals(setOf(retainedTile, retainedCrop), result.retained)
        assertEquals(setOf(missingTile, missingSatellite), result.missing)
        assertEquals(
            setOf(
                PreDownloadPruneKey.Geotiff(SURVEY_ID, 9L, ZOOM, X, Y),
                PreDownloadPruneKey.Crop(SURVEY_ID, 8L),
                PreDownloadPruneKey.Satellite(SURVEY_ID, "sat:7:18-22"),
            ),
            result.obsolete,
        )
        assertTrue(result.clearCommands.isEmpty())
        assertTrue(result.hasOverlap)
    }

    @Test
    fun `cross survey candidate id never retained`() {
        val targetTile = tile(surveyId = 1L, candidateId = 1L)
        val ownedTile = tile(surveyId = 2L, candidateId = 1L)
        val target =
            PreDownloadTargetSet(
                geotiffTiles = listOf(targetTile),
                crops = emptyList(),
                satellites = emptyList(),
            )

        val result =
            ReplacementClassifier.classify(
                target,
                listOf(PreDownloadOwnedPayload(ownedTile, valid = true)),
            )

        assertTrue(result.retained.isEmpty())
        assertEquals(setOf(targetTile), result.missing)
        assertEquals(
            setOf(PreDownloadPruneKey.Geotiff(2L, 1L, ZOOM, X, Y)),
            result.obsolete,
        )
    }

    @Test
    fun `source version mismatch prevents reuse`() {
        assertTileMismatch(target = tile(sourceVersion = "v2"), owned = tile(sourceVersion = "v1"))
    }

    @Test
    fun `radius mismatch prevents reuse`() {
        assertTileMismatch(target = tile(radiusMetres = 25.0), owned = tile(radiusMetres = 10.0))
    }

    @Test
    fun `zoom mismatch prevents reuse`() {
        assertTileMismatch(target = tile(zoom = 21), owned = tile(zoom = 20))
    }

    @Test
    fun `coordinate mismatch prevents reuse`() {
        assertTileMismatch(target = tile(x = X), owned = tile(x = X + 1))
        assertTileMismatch(target = tile(y = Y), owned = tile(y = Y + 1))
    }

    @Test
    fun `tile kind mismatch prevents reuse`() {
        assertTileMismatch(target = tile(kind = "GEOTIFF"), owned = tile(kind = "DERIVED"))
    }

    @Test
    fun `expected format mismatch prevents reuse`() {
        assertTileMismatch(
            target = tile(expectedFormat = "PNG"),
            owned = tile(expectedFormat = "WEBP"),
        )
    }

    @Test
    fun `crop request signature mismatch prevents reuse`() {
        val targetCrop = crop(requestSignature = "crop:1:1")
        val ownedCrop = crop(requestSignature = "crop:1:2")
        val result =
            ReplacementClassifier.classify(
                target = PreDownloadTargetSet(emptyList(), listOf(targetCrop), emptyList()),
                owned = listOf(PreDownloadOwnedPayload(ownedCrop, valid = true)),
            )

        assertTrue(result.retained.isEmpty())
        assertEquals(setOf(targetCrop), result.missing)
        assertEquals(setOf(PreDownloadPruneKey.Crop(SURVEY_ID, 1L)), result.obsolete)
    }

    @Test
    fun `satellite signature mismatch prevents reuse`() {
        val targetSatellite = satellite(signature = "sat:1:18-22")
        val ownedSatellite = satellite(signature = "sat:2:18-22")
        val result =
            ReplacementClassifier.classify(
                target = PreDownloadTargetSet(emptyList(), emptyList(), listOf(targetSatellite)),
                owned = listOf(PreDownloadOwnedPayload(ownedSatellite, valid = true)),
            )

        assertTrue(result.retained.isEmpty())
        assertEquals(setOf(targetSatellite), result.missing)
        assertEquals(
            setOf(PreDownloadPruneKey.Satellite(SURVEY_ID, "sat:2:18-22")),
            result.obsolete,
        )
    }

    @Test
    fun `invalid matching payload is missing and obsolete`() {
        val targetTile = tile(candidateId = 4L)
        val result =
            ReplacementClassifier.classify(
                target = PreDownloadTargetSet(listOf(targetTile), emptyList(), emptyList()),
                owned =
                    listOf(
                        PreDownloadOwnedPayload(
                            targetTile,
                            valid = false,
                            measuredDeletableBytes = 123L,
                        ),
                    ),
            )

        assertEquals(setOf(targetTile), result.missing)
        assertTrue(result.retained.isEmpty())
        assertEquals(
            setOf(PreDownloadPruneKey.Geotiff(SURVEY_ID, 4L, ZOOM, X, Y)),
            result.obsolete,
        )
        assertEquals(123L, result.confidentlyDeletableBytes)
    }

    @Test
    fun `zero overlap emits clear command only for owners with obsolete content`() {
        val result =
            ReplacementClassifier.classify(
                target =
                    PreDownloadTargetSet(
                        geotiffTiles = listOf(tile(candidateId = 1L)),
                        crops = listOf(crop(candidateId = 1L)),
                        satellites = listOf(satellite(signature = "sat:1:18-22")),
                    ),
                owned =
                    listOf(
                        PreDownloadOwnedPayload(
                            tile(candidateId = 1L, sourceVersion = "old"),
                            valid = true,
                        ),
                    ),
            )

        assertEquals(listOf(PreDownloadClearCommand.ClearGeotiffs), result.clearCommands)
    }

    @Test
    fun `zero overlap emits all clear commands when every owner is obsolete`() {
        val result =
            ReplacementClassifier.classify(
                target =
                    PreDownloadTargetSet(
                        geotiffTiles = listOf(tile(candidateId = 1L)),
                        crops = listOf(crop(candidateId = 1L)),
                        satellites = listOf(satellite(signature = "sat:1:18-22")),
                    ),
                owned =
                    listOf(
                        PreDownloadOwnedPayload(tile(candidateId = 2L), valid = true),
                        PreDownloadOwnedPayload(crop(candidateId = 2L), valid = true),
                        PreDownloadOwnedPayload(satellite(signature = "sat:2:18-22"), valid = true),
                    ),
            )

        assertEquals(
            listOf(
                PreDownloadClearCommand.ClearGeotiffs,
                PreDownloadClearCommand.ClearCrops,
                PreDownloadClearCommand.ClearSatelliteRegions,
            ),
            result.clearCommands,
        )
    }

    @Test
    fun `partial overlap emits no clear commands`() {
        val shared = tile(candidateId = 1L)
        val result =
            ReplacementClassifier.classify(
                target = PreDownloadTargetSet(listOf(shared), emptyList(), emptyList()),
                owned =
                    listOf(
                        PreDownloadOwnedPayload(shared, valid = true),
                        PreDownloadOwnedPayload(tile(candidateId = 2L), valid = true),
                    ),
            )

        assertTrue(result.clearCommands.isEmpty())
        assertTrue(result.hasOverlap)
    }

    @Test
    fun `fresh target with nothing owned has no clear commands`() {
        val result =
            ReplacementClassifier.classify(
                target =
                    PreDownloadTargetSet(
                        listOf(tile(candidateId = 1L)),
                        emptyList(),
                        emptyList(),
                    ),
                owned = emptyList(),
            )

        assertFalse(result.hasOverlap)
        assertTrue(result.clearCommands.isEmpty())
        assertTrue(result.retained.isEmpty())
        assertEquals(1, result.missing.size)
    }

    @Test
    fun `satellite bytes receive zero freed space credit`() {
        val obsoleteSatellite = satellite(signature = "sat:2:18-22")
        val result =
            ReplacementClassifier.classify(
                target = PreDownloadTargetSet(emptyList(), emptyList(), emptyList()),
                owned =
                    listOf(
                        PreDownloadOwnedPayload(
                            obsoleteSatellite,
                            valid = false,
                            measuredDeletableBytes = 50L * 1024L * 1024L,
                        ),
                    ),
            )

        assertEquals(0L, result.confidentlyDeletableBytes)
    }

    @Test
    fun `unknown measured length receives zero freed space credit`() {
        val result =
            ReplacementClassifier.classify(
                target = PreDownloadTargetSet(emptyList(), emptyList(), emptyList()),
                owned =
                    listOf(
                        PreDownloadOwnedPayload(tile(candidateId = 2L), valid = false),
                        PreDownloadOwnedPayload(
                            crop(candidateId = 2L),
                            valid = false,
                            measuredDeletableBytes = 4096L,
                        ),
                    ),
            )

        assertEquals(4096L, result.confidentlyDeletableBytes)
    }

    @Test
    fun `retained and missing are disjoint and exhaustive over the target`() {
        val tiles = listOf(tile(candidateId = 1L), tile(candidateId = 2L), tile(candidateId = 3L))
        val crops = listOf(crop(candidateId = 1L), crop(candidateId = 2L))
        val satellites = listOf(satellite(signature = "sat:1:18-22"))
        val target = PreDownloadTargetSet(tiles, crops, satellites)

        val result =
            ReplacementClassifier.classify(
                target,
                listOf(
                    PreDownloadOwnedPayload(tiles[0], valid = true),
                    PreDownloadOwnedPayload(crops[1], valid = true),
                ),
            )

        assertTrue(result.retained.intersect(result.missing).isEmpty())
        assertEquals(target.all.toSet(), result.retained + result.missing)
        assertEquals(2, result.retained.size)
        assertEquals(4, result.missing.size)
    }

    @Test
    fun `classification derives replacement inventory counts`() {
        val retainedTile = tile(candidateId = 1L)
        val missingTile = tile(candidateId = 2L)
        val retainedCrop = crop(candidateId = 1L)
        val missingSatellite = satellite(signature = "sat:1:18-22")
        val result =
            ReplacementClassifier.classify(
                target =
                    PreDownloadTargetSet(
                        geotiffTiles = listOf(retainedTile, missingTile),
                        crops = listOf(retainedCrop),
                        satellites = listOf(missingSatellite),
                    ),
                owned =
                    listOf(
                        PreDownloadOwnedPayload(retainedTile, valid = true),
                        PreDownloadOwnedPayload(retainedCrop, valid = true),
                    ),
            )

        val inventory = result.toInventory()
        assertEquals(1, inventory.geotiffPresentCount)
        assertEquals(1, inventory.geotiffMissingCount)
        assertEquals(1, inventory.cropPresentCount)
        assertEquals(0, inventory.cropMissingCount)
        assertEquals(0, inventory.satellitePresentCount)
        assertEquals(1, inventory.satelliteMissingCount)
    }

    private fun assertTileMismatch(
        target: PreDownloadTargetKey.Tile,
        owned: PreDownloadTargetKey.Tile,
    ) {
        val result =
            ReplacementClassifier.classify(
                target = PreDownloadTargetSet(listOf(target), emptyList(), emptyList()),
                owned = listOf(PreDownloadOwnedPayload(owned, valid = true)),
            )

        assertTrue(result.retained.isEmpty())
        assertEquals(setOf(target), result.missing)
        assertEquals(1, result.obsolete.size)
    }

    private fun tile(
        surveyId: Long = SURVEY_ID,
        candidateId: Long = CANDIDATE_ID,
        sourceVersion: String = SOURCE_VERSION,
        radiusMetres: Double = RADIUS,
        zoom: Int = ZOOM,
        x: Int = X,
        y: Int = Y,
        kind: String = KIND,
        expectedFormat: String = FORMAT,
    ): PreDownloadTargetKey.Tile =
        PreDownloadTargetKey.Tile(
            surveyId = surveyId,
            candidateId = candidateId,
            sourceVersion = sourceVersion,
            radiusMetres = radiusMetres,
            zoom = zoom,
            x = x,
            y = y,
            kind = kind,
            expectedFormat = expectedFormat,
        )

    private fun crop(
        surveyId: Long = SURVEY_ID,
        candidateId: Long = CANDIDATE_ID,
        sourceVersion: String = SOURCE_VERSION,
        requestSignature: String = "crop:$SURVEY_ID:$CANDIDATE_ID",
    ): PreDownloadTargetKey.Crop =
        PreDownloadTargetKey.Crop(
            surveyId = surveyId,
            candidateId = candidateId,
            sourceVersion = sourceVersion,
            requestSignature = requestSignature,
        )

    private fun satellite(
        surveyId: Long = SURVEY_ID,
        sourceVersion: String = SOURCE_VERSION,
        signature: String,
    ): PreDownloadTargetKey.Satellite =
        PreDownloadTargetKey.Satellite(
            surveyId = surveyId,
            sourceVersion = sourceVersion,
            signature = signature,
        )

    private companion object {
        const val SURVEY_ID = 7L
        const val CANDIDATE_ID = 1L
        const val SOURCE_VERSION = "2026-01-01T00:00:00Z"
        const val RADIUS = 10.0
        const val ZOOM = 20
        const val X = 5
        const val Y = 6
        const val KIND = "GEOTIFF"
        const val FORMAT = "PNG"
    }
}
