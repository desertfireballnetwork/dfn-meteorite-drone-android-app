package au.edu.fireballs.stage4.ui.screen.sync

import au.edu.fireballs.stage4.data.repository.SyncPhase
import au.edu.fireballs.stage4.data.repository.SyncProgress

internal const val COPY_SYNCHRONISING = "Synchronising"

internal fun progressCopy(progress: SyncProgress?): String {
    if (progress == null) {
        return COPY_SYNCHRONISING
    }
    val total = progress.total
    val hasTotal = total != null && total > 0
    return when (progress.phase) {
        SyncPhase.Photo ->
            if (hasTotal) {
                "Uploading photo ${progress.done} of $total"
            } else {
                "Uploading photo"
            }
        SyncPhase.Decision ->
            if (hasTotal) {
                "Synchronising decision ${progress.done} of $total"
            } else {
                "Synchronising decision"
            }
        SyncPhase.Unknown -> COPY_SYNCHRONISING
    }
}

internal fun progressFraction(progress: SyncProgress?): Float? {
    val total = progress?.total ?: return null
    if (total <= 0) {
        return null
    }
    return progress.done.toFloat() / total.toFloat()
}
