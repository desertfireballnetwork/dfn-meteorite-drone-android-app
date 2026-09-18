package au.edu.fireballs.stage4.data.repository

enum class WorkingSetState(
    val value: String,
) {
    REPLACING("REPLACING"),
    INCOMPLETE("INCOMPLETE"),
    COMPLETE("COMPLETE"),
    ;

    companion object {
        fun from(value: String): WorkingSetState =
            entries.firstOrNull { it.value == value } ?: INCOMPLETE
    }
}

data class ReplacementTarget(
    val surveyId: Long,
    val sourceVersion: String,
    val radiusMetres: Double,
    val minZoom: Int,
    val maxZoom: Int,
    val candidateCount: Int,
)

data class ReplacementSession(
    val manifestId: String,
    val surveyId: Long,
    val sourceVersion: String,
    val state: WorkingSetState,
    val requiredCount: Int,
    val retainedCount: Int,
    val missing: Set<PreDownloadTargetKey>,
    val obsolete: Set<PreDownloadPruneKey>,
    val clearCommands: List<PreDownloadClearCommand>,
    val pendingPurgeCount: Int,
) {
    val missingCount: Int
        get() = missing.size

    val obsoleteCount: Int
        get() = obsolete.size

    val completeCount: Int
        get() = (requiredCount - missingCount).coerceAtLeast(0)

    val isOfflineReady: Boolean
        get() = state == WorkingSetState.COMPLETE
}

data class ReplacementResume(
    val session: ReplacementSession,
    val removedTemporaryFiles: Int,
    val promotedItems: Int,
    val demotedItems: Int,
    val retriedPurges: Int,
    val pendingPurgeCount: Int,
)
