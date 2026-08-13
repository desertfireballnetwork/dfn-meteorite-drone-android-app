package au.edu.fireballs.stage4.domain.model

data class Survey(
    val id: Long,
    val eventId: String,
    val description: String,
    val createdIso: String,
    val hasStage4: Boolean,
    val isActive: Boolean,
)
