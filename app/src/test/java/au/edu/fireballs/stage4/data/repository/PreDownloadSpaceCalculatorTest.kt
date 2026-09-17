package au.edu.fireballs.stage4.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PreDownloadSpaceCalculatorTest {
    @Test
    fun `all missing items contribute to incremental requirement`() {
        val inventory =
            inventory(
                geotiffMissing = 2,
                cropMissing = 3,
                satelliteMissing = 1,
            )

        val estimate = calculate(inventory)

        assertEquals(57L * PreDownloadSpaceCalculator.MIB, estimate.incrementalRequiredBytes)
    }

    @Test
    fun `all present items contribute zero`() {
        val inventory =
            inventory(
                geotiffPresent = 8,
                cropPresent = 9,
                satellitePresent = 10,
            )

        val estimate = calculate(inventory)

        assertEquals(0L, estimate.incrementalRequiredBytes)
    }

    @Test
    fun `mixed inventory counts only missing items`() {
        val inventory =
            inventory(
                geotiffPresent = 100,
                geotiffMissing = 1,
                cropPresent = 200,
                cropMissing = 2,
                satellitePresent = 300,
                satelliteMissing = 1,
            )

        val estimate = calculate(inventory)

        assertEquals(54L * PreDownloadSpaceCalculator.MIB, estimate.incrementalRequiredBytes)
    }

    @Test
    fun `one geotiff uses fixed binary unit multiplication`() {
        val estimate = calculate(inventory(geotiffMissing = 1))

        assertEquals(2L * 1024L * 1024L, estimate.incrementalRequiredBytes)
    }

    @Test
    fun `small volume uses minimum reserve`() {
        val estimate =
            calculate(
                inventory = inventory(),
                totalVolumeBytes = 10L * PreDownloadSpaceCalculator.GIB,
            )

        assertEquals(2L * PreDownloadSpaceCalculator.GIB, estimate.reserveBytes)
    }

    @Test
    fun `large volume uses ten percent reserve`() {
        val total = 30L * PreDownloadSpaceCalculator.GIB
        val estimate =
            calculate(
                inventory = inventory(),
                totalVolumeBytes = total,
            )

        assertEquals(3L * PreDownloadSpaceCalculator.GIB, estimate.reserveBytes)
    }

    @Test
    fun `expected remaining equal to reserve passes`() {
        val reserve = PreDownloadSpaceCalculator.MINIMUM_RESERVE_BYTES
        val estimate =
            calculate(
                inventory = inventory(cropMissing = 1),
                availableBytes = reserve + PreDownloadSpaceCalculator.CROP_ESTIMATE_BYTES,
            )

        assertEquals(reserve, estimate.expectedRemainingBytes)
        assertTrue(PreDownloadSpaceCalculator.hasSufficientSpace(estimate))
        assertTrue(
            PreDownloadSpaceCalculator.evaluate(
                inventory(cropMissing = 1),
                reserve + PreDownloadSpaceCalculator.CROP_ESTIMATE_BYTES,
                0L,
            ) is PreDownloadPreflightResult.Allowed,
        )
    }

    @Test
    fun `expected remaining one byte below reserve fails`() {
        val reserve = PreDownloadSpaceCalculator.MINIMUM_RESERVE_BYTES
        val estimate =
            calculate(
                inventory = inventory(cropMissing = 1),
                availableBytes = reserve + PreDownloadSpaceCalculator.CROP_ESTIMATE_BYTES - 1L,
            )

        assertFalse(PreDownloadSpaceCalculator.hasSufficientSpace(estimate))
    }

    @Test
    fun `expected remaining one byte above reserve passes`() {
        val reserve = PreDownloadSpaceCalculator.MINIMUM_RESERVE_BYTES
        val estimate =
            calculate(
                inventory = inventory(cropMissing = 1),
                availableBytes = reserve + PreDownloadSpaceCalculator.CROP_ESTIMATE_BYTES + 1L,
            )

        assertTrue(PreDownloadSpaceCalculator.hasSufficientSpace(estimate))
    }

    @Test
    fun `available smaller than incremental produces negative remaining`() {
        val estimate =
            calculate(
                inventory = inventory(geotiffMissing = 1),
                availableBytes = 1L,
            )

        assertEquals(
            1L - PreDownloadSpaceCalculator.GEOTIFF_ESTIMATE_BYTES,
            estimate.expectedRemainingBytes,
        )
        assertFalse(PreDownloadSpaceCalculator.hasSufficientSpace(estimate))
    }

    @Test
    fun `zero available and total is insufficient with minimum reserve`() {
        val estimate =
            calculate(
                inventory = inventory(),
                availableBytes = 0L,
                totalVolumeBytes = 0L,
            )

        assertEquals(0L, estimate.incrementalRequiredBytes)
        assertEquals(0L, estimate.expectedRemainingBytes)
        assertEquals(PreDownloadSpaceCalculator.MINIMUM_RESERVE_BYTES, estimate.reserveBytes)
        assertFalse(PreDownloadSpaceCalculator.hasSufficientSpace(estimate))
    }

    @Test
    fun `large counts remain exact without wrapping`() {
        val largeInventory =
            inventory(
                geotiffMissing = Int.MAX_VALUE,
                cropMissing = Int.MAX_VALUE,
                satelliteMissing = Int.MAX_VALUE,
            )
        val estimate =
            calculate(
                inventory = largeInventory,
                availableBytes = Long.MAX_VALUE,
                totalVolumeBytes = Long.MAX_VALUE,
            )
        val expected =
            Int.MAX_VALUE.toLong() *
                (2L + 1L + 50L) *
                PreDownloadSpaceCalculator.MIB

        assertEquals(expected, estimate.incrementalRequiredBytes)
        assertEquals(Long.MAX_VALUE - expected, estimate.expectedRemainingBytes)
        assertTrue(estimate.incrementalRequiredBytes in 1 until Long.MAX_VALUE)

        val insufficient =
            calculate(
                inventory = largeInventory,
                availableBytes = 0L,
                totalVolumeBytes = 0L,
            )
        assertFalse(PreDownloadSpaceCalculator.hasSufficientSpace(insufficient))
    }

    @Test
    fun `percentage reserve calculation is overflow safe`() {
        val estimate =
            calculate(
                inventory = inventory(),
                availableBytes = Long.MAX_VALUE,
                totalVolumeBytes = Long.MAX_VALUE,
            )

        assertEquals(Long.MAX_VALUE / 10L, estimate.reserveBytes)
        assertTrue(PreDownloadSpaceCalculator.hasSufficientSpace(estimate))
    }

    @Test
    fun `negative counts are rejected`() {
        assertIllegalArgument {
            inventory(geotiffMissing = -1)
        }
    }

    @Test
    fun `negative byte values are rejected`() {
        assertIllegalArgument {
            calculate(inventory(), availableBytes = -1L)
        }
        assertIllegalArgument {
            calculate(inventory(), totalVolumeBytes = -1L)
        }
    }

    @Test
    fun `five hundred candidate plan is not rejected by an application quota`() {
        val estimate =
            calculate(
                inventory =
                    inventory(
                        geotiffMissing = 500 * 100,
                        cropMissing = 500,
                        satelliteMissing = 500,
                    ),
                availableBytes = 200L * 1024L * 1024L * 1024L,
                totalVolumeBytes = 250L * 1024L * 1024L * 1024L,
            )

        assertTrue(estimate.incrementalRequiredBytes > 512L * 1024L * 1024L)
        assertTrue(PreDownloadSpaceCalculator.hasSufficientSpace(estimate))
    }

    private fun calculate(
        inventory: PreDownloadInventory,
        availableBytes: Long = 10L * PreDownloadSpaceCalculator.GIB,
        totalVolumeBytes: Long = 10L * PreDownloadSpaceCalculator.GIB,
    ): PreDownloadSpaceEstimate =
        PreDownloadSpaceCalculator.calculate(
            inventory = inventory,
            availableBytes = availableBytes,
            totalVolumeBytes = totalVolumeBytes,
        )

    private fun inventory(
        geotiffPresent: Int = 0,
        geotiffMissing: Int = 0,
        cropPresent: Int = 0,
        cropMissing: Int = 0,
        satellitePresent: Int = 0,
        satelliteMissing: Int = 0,
    ): PreDownloadInventory =
        PreDownloadInventory(
            geotiffPresentCount = geotiffPresent,
            geotiffMissingCount = geotiffMissing,
            cropPresentCount = cropPresent,
            cropMissingCount = cropMissing,
            satellitePresentCount = satellitePresent,
            satelliteMissingCount = satelliteMissing,
        )

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
