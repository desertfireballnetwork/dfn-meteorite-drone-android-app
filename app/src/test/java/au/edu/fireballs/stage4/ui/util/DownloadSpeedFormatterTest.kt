package au.edu.fireballs.stage4.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class DownloadSpeedFormatterTest {
    @Test
    fun `formats bytes per second`() {
        assertEquals("800 B/s", formatDownloadSpeed(800L))
    }

    @Test
    fun `formats kibibytes per second with one decimal`() {
        assertEquals("12.5 KB/s", formatDownloadSpeed(12_800L))
    }

    @Test
    fun `formats mebibytes per second with one decimal`() {
        assertEquals("3.2 MB/s", formatDownloadSpeed(3_355_443L))
    }

    @Test
    fun `formats gibibytes per second at threshold`() {
        assertEquals("1.0 GB/s", formatDownloadSpeed(1_073_741_824L))
    }

    @Test
    fun `format is stable when default locale uses decimal comma`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("12.5 KB/s", formatDownloadSpeed(12_800L))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `formats Long values without narrowing`() {
        assertEquals("2048.0 GB/s", formatDownloadSpeed(2_199_023_255_552L))
    }
}
