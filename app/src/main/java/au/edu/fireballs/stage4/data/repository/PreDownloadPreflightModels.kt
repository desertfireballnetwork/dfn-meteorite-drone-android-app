package au.edu.fireballs.stage4.data.repository

data class PreDownloadInventory(
    val geotiffPresentCount: Int,
    val geotiffMissingCount: Int,
    val cropPresentCount: Int,
    val cropMissingCount: Int,
    val satellitePresentCount: Int,
    val satelliteMissingCount: Int,
) {
    init {
        require(geotiffPresentCount >= 0)
        require(geotiffMissingCount >= 0)
        require(cropPresentCount >= 0)
        require(cropMissingCount >= 0)
        require(satellitePresentCount >= 0)
        require(satelliteMissingCount >= 0)
    }
}

data class PreDownloadSpaceEstimate(
    val inventory: PreDownloadInventory,
    val incrementalRequiredBytes: Long,
    val availableBytes: Long,
    val totalVolumeBytes: Long,
    val reserveBytes: Long,
    val expectedRemainingBytes: Long,
    val confidentlyDeletableBytes: Long = 0L,
)

sealed interface PreDownloadPreflightResult {
    data class Allowed(
        val estimate: PreDownloadSpaceEstimate,
    ) : PreDownloadPreflightResult

    data class InsufficientDeviceSpace(
        val estimate: PreDownloadSpaceEstimate,
    ) : PreDownloadPreflightResult

    data object StorageOperationActive : PreDownloadPreflightResult
}

sealed interface PreDownloadTargetKey {
    val surveyId: Long
    val sourceVersion: String

    data class Tile(
        override val surveyId: Long,
        val candidateId: Long,
        override val sourceVersion: String,
        val radiusMetres: Double,
        val zoom: Int,
        val x: Int,
        val y: Int,
        val kind: String,
        val expectedFormat: String,
    ) : PreDownloadTargetKey

    data class Crop(
        override val surveyId: Long,
        val candidateId: Long,
        override val sourceVersion: String,
        val requestSignature: String,
    ) : PreDownloadTargetKey

    data class Satellite(
        override val surveyId: Long,
        override val sourceVersion: String,
        val signature: String,
    ) : PreDownloadTargetKey
}

data class PreDownloadTargetSet(
    val geotiffTiles: List<PreDownloadTargetKey.Tile>,
    val crops: List<PreDownloadTargetKey.Crop>,
    val satellites: List<PreDownloadTargetKey.Satellite>,
) {
    val all: List<PreDownloadTargetKey>
        get() =
            buildList<PreDownloadTargetKey>(
                geotiffTiles.size + crops.size + satellites.size,
            ) {
                addAll(geotiffTiles)
                addAll(crops)
                addAll(satellites)
            }
}

data class PreDownloadOwnedPayload(
    val key: PreDownloadTargetKey,
    val valid: Boolean,
    val measuredDeletableBytes: Long? = null,
)

sealed interface PreDownloadPruneKey {
    data class Geotiff(
        val surveyId: Long,
        val candidateId: Long,
        val zoom: Int,
        val x: Int,
        val y: Int,
    ) : PreDownloadPruneKey

    data class Crop(
        val surveyId: Long,
        val candidateId: Long,
    ) : PreDownloadPruneKey

    data class Satellite(
        val surveyId: Long,
        val signature: String,
    ) : PreDownloadPruneKey
}

sealed interface PreDownloadClearCommand {
    data object ClearGeotiffs : PreDownloadClearCommand

    data object ClearCrops : PreDownloadClearCommand

    data object ClearSatelliteRegions : PreDownloadClearCommand
}

data class ReplacementClassification(
    val retained: Set<PreDownloadTargetKey>,
    val missing: Set<PreDownloadTargetKey>,
    val obsolete: Set<PreDownloadPruneKey>,
    val clearCommands: List<PreDownloadClearCommand>,
    val confidentlyDeletableBytes: Long,
) {
    init {
        require(confidentlyDeletableBytes >= 0L)
        require(retained.intersect(missing).isEmpty())
    }

    val hasOverlap: Boolean
        get() = retained.isNotEmpty()

    fun toInventory(): PreDownloadInventory {
        var geotiffPresent = 0
        var cropPresent = 0
        var satellitePresent = 0
        retained.forEach { key ->
            when (key) {
                is PreDownloadTargetKey.Tile -> geotiffPresent++
                is PreDownloadTargetKey.Crop -> cropPresent++
                is PreDownloadTargetKey.Satellite -> satellitePresent++
            }
        }
        var geotiffMissing = 0
        var cropMissing = 0
        var satelliteMissing = 0
        missing.forEach { key ->
            when (key) {
                is PreDownloadTargetKey.Tile -> geotiffMissing++
                is PreDownloadTargetKey.Crop -> cropMissing++
                is PreDownloadTargetKey.Satellite -> satelliteMissing++
            }
        }
        return PreDownloadInventory(
            geotiffPresentCount = geotiffPresent,
            geotiffMissingCount = geotiffMissing,
            cropPresentCount = cropPresent,
            cropMissingCount = cropMissing,
            satellitePresentCount = satellitePresent,
            satelliteMissingCount = satelliteMissing,
        )
    }
}
