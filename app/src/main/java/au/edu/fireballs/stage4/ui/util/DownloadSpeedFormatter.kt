package au.edu.fireballs.stage4.ui.util

import java.util.Locale

internal fun formatDownloadSpeed(bytesPerSecond: Long): String =
    when {
        bytesPerSecond < 1024L -> "$bytesPerSecond B/s"
        bytesPerSecond < 1024L * 1024L ->
            String.format(Locale.ROOT, "%.1f KB/s", bytesPerSecond / 1024.0)

        bytesPerSecond < 1024L * 1024L * 1024L ->
            String.format(Locale.ROOT, "%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0))

        else ->
            String.format(
                Locale.ROOT,
                "%.1f GB/s",
                bytesPerSecond / (1024.0 * 1024.0 * 1024.0),
            )
    }
