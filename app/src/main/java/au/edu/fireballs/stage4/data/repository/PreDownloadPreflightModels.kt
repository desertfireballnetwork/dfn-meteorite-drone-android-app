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
