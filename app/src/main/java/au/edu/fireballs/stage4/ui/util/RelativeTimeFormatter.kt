package au.edu.fireballs.stage4.ui.util

import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

object RelativeTimeFormatter {
    fun formatRelativeTime(
        isoTimestamp: String,
        now: Instant = Instant.now(),
    ): String =
        try {
            val createdInstant = Instant.parse(isoTimestamp)
            val duration = Duration.between(createdInstant, now)

            when {
                duration.isNegative -> "Just now"
                duration.toMinutes() < 1 -> "Just now"
                duration.toMinutes() < 60 -> "${duration.toMinutes()}m ago"
                duration.toHours() < 24 -> "${duration.toHours()}h ago"
                duration.toDays() == 1L -> "Yesterday"
                duration.toDays() < 30 -> "${duration.toDays()} days ago"
                duration.toDays() < 365 -> "${duration.toDays() / 30} months ago"
                else -> "${duration.toDays() / 365} years ago"
            }
        } catch (e: DateTimeParseException) {
            isoTimestamp
        }
}
