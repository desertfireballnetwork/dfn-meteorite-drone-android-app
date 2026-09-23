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
    val sourceVersion: String,
    val state: WorkingSetState,
    val requiredCount: Int,
    val missing: Set<PreDownloadTargetKey>,
    val obsolete: Set<PreDownloadPruneKey>,
    val clearCommands: List<PreDownloadClearCommand>,
)
