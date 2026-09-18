package au.edu.fireballs.stage4.data.repository

object PreDownloadSpaceCalculator {
    const val MIB = 1024L * 1024L
    const val GIB = 1024L * MIB
    const val GEOTIFF_ESTIMATE_BYTES = 2L * MIB
    const val CROP_ESTIMATE_BYTES = 1L * MIB
    const val SATELLITE_ESTIMATE_BYTES = 50L * MIB
    const val MINIMUM_RESERVE_BYTES = 2L * GIB
    const val RESERVE_PERCENT_NUMERATOR = 10L
    const val RESERVE_PERCENT_DENOMINATOR = 100L

    fun calculate(
        inventory: PreDownloadInventory,
        availableBytes: Long,
        totalVolumeBytes: Long,
        confidentlyDeletableBytes: Long = 0L,
    ): PreDownloadSpaceEstimate {
        require(availableBytes >= 0)
        require(totalVolumeBytes >= 0)
        require(confidentlyDeletableBytes >= 0)

        val incrementalRequiredBytes =
            inventory.geotiffMissingCount.toLong() * GEOTIFF_ESTIMATE_BYTES +
                inventory.cropMissingCount.toLong() * CROP_ESTIMATE_BYTES +
                inventory.satelliteMissingCount.toLong() * SATELLITE_ESTIMATE_BYTES
        val percentageReserve =
            totalVolumeBytes / RESERVE_PERCENT_DENOMINATOR * RESERVE_PERCENT_NUMERATOR +
                totalVolumeBytes % RESERVE_PERCENT_DENOMINATOR *
                RESERVE_PERCENT_NUMERATOR / RESERVE_PERCENT_DENOMINATOR
        val reserveBytes = maxOf(MINIMUM_RESERVE_BYTES, percentageReserve)

        return PreDownloadSpaceEstimate(
            inventory = inventory,
            incrementalRequiredBytes = incrementalRequiredBytes,
            availableBytes = availableBytes,
            totalVolumeBytes = totalVolumeBytes,
            reserveBytes = reserveBytes,
            expectedRemainingBytes =
                availableBytes + confidentlyDeletableBytes - incrementalRequiredBytes,
            confidentlyDeletableBytes = confidentlyDeletableBytes,
        )
    }

    fun hasSufficientSpace(estimate: PreDownloadSpaceEstimate): Boolean =
        estimate.expectedRemainingBytes >= estimate.reserveBytes

    fun evaluate(
        inventory: PreDownloadInventory,
        availableBytes: Long,
        totalVolumeBytes: Long,
        confidentlyDeletableBytes: Long = 0L,
    ): PreDownloadPreflightResult {
        val estimate =
            calculate(
                inventory,
                availableBytes,
                totalVolumeBytes,
                confidentlyDeletableBytes,
            )
        return if (hasSufficientSpace(estimate)) {
            PreDownloadPreflightResult.Allowed(estimate)
        } else {
            PreDownloadPreflightResult.InsufficientDeviceSpace(estimate)
        }
    }
}
