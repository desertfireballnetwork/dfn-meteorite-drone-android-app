package au.edu.fireballs.stage4.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class EvidenceListResponseDto(
    @Json(name = "photos") val photos: List<EvidencePhotoDto>,
)

@JsonClass(generateAdapter = true)
data class EvidencePhotoDto(
    @Json(name = "id") val id: Long,
    @Json(name = "captured_at") val capturedAt: String? = null,
    @Json(name = "created") val created: String,
    @Json(name = "user_id") val userId: Long,
    @Json(name = "username") val username: String,
)
