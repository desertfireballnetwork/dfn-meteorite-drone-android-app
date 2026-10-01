package au.edu.fireballs.stage4.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadRateTest {
    @Test
    fun returnsZeroBeforeWindowBoundary() {
        assertEquals(0L, bytesPerSecond(1_000L, 999L))
    }

    @Test
    fun calculatesAtExactWindowBoundary() {
        assertEquals(1_000L, bytesPerSecond(1_000L, 1_000L))
    }

    @Test
    fun usesActualElapsedTime() {
        assertEquals(2_000L, bytesPerSecond(4_000L, 2_000L))
    }

    @Test
    fun zeroBytesProducesZero() {
        assertEquals(0L, bytesPerSecond(0L, 1_000L))
    }

    @Test
    fun negativeBytesProducesZero() {
        assertEquals(0L, bytesPerSecond(-1L, 1_000L))
    }

    @Test
    fun longRangeArithmeticDoesNotOverflowOrTruncate() {
        assertEquals(Long.MAX_VALUE, bytesPerSecond(Long.MAX_VALUE, 1_000L))
        assertEquals(Long.MAX_VALUE / 2L, bytesPerSecond(Long.MAX_VALUE, 2_000L))
    }
}
