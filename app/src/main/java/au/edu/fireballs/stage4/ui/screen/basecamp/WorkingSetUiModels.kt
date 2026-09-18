package au.edu.fireballs.stage4.ui.screen.basecamp

sealed interface WorkingSetUiState {
    data object None : WorkingSetUiState

    data class ReplacementReady(
        val candidateCount: Int,
        val retainedCount: Int,
        val missingCount: Int,
        val obsoleteCount: Int,
        val confidentlyDeletableBytes: Long,
        val estimatedDownloadBytes: Long,
        val mapboxBytesUnavailable: Boolean,
    ) : WorkingSetUiState

    data class Replacing(
        val manifestId: String,
        val phase: ReplacingPhase,
        val done: Int,
        val total: Int,
    ) : WorkingSetUiState

    data class Incomplete(
        val manifestId: String,
        val missingCount: Int,
        val retainedCount: Int,
    ) : WorkingSetUiState

    data class ResumeAvailable(
        val manifestId: String,
        val missingCount: Int,
    ) : WorkingSetUiState
}

enum class ReplacingPhase {
    PLANNING,
    PRUNING,
    TRANSFER,
    VERIFYING,
    COMPLETING,
}
