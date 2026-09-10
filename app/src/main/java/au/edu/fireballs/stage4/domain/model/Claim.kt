package au.edu.fireballs.stage4.domain.model

data class Claim(
    val inferenceResultId: Long,
    val userId: Long,
    val username: String? = null,
    val fullName: String? = null,
    val claimedAt: String? = null,
    val isMe: Boolean = false,
)
