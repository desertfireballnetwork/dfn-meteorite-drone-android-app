package au.edu.fireballs.stage4.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SurveyDto(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "candidate_count") val candidateCount: Int? = null,
)

@JsonClass(generateAdapter = true)
data class GeoPointDto(
    @Json(name = "latitude") val latitude: Double,
    @Json(name = "longitude") val longitude: Double,
    @Json(name = "elevation") val elevation: Double? = null,
)

@JsonClass(generateAdapter = true)
data class CandidateDto(
    @Json(name = "id") val id: Int,
    @Json(name = "survey_id") val surveyId: Int,
    @Json(name = "location") val location: GeoPointDto? = null,
    @Json(name = "score") val score: Double? = null,
    @Json(name = "status") val status: String? = null,
)

@JsonClass(generateAdapter = true)
data class CandidatesResponseDto(
    @Json(name = "candidates") val candidates: List<CandidateDto>,
)

@JsonClass(generateAdapter = true)
data class ClaimDto(
    @Json(name = "id") val id: Int,
    @Json(name = "candidate_id") val candidateId: Int,
    @Json(name = "user_id") val userId: Int? = null,
    @Json(name = "claimed_at") val claimedAt: String? = null,
    @Json(name = "status") val status: String? = null,
)

@JsonClass(generateAdapter = true)
data class ClaimRequestDto(
    @Json(name = "candidate_id") val candidateId: Int,
)

@JsonClass(generateAdapter = true)
data class ClaimResponseDto(
    @Json(name = "success") val success: Boolean,
    @Json(name = "claim") val claim: ClaimDto? = null,
    @Json(name = "message") val message: String? = null,
)

@JsonClass(generateAdapter = true)
data class ReleaseRequestDto(
    @Json(name = "candidate_id") val candidateId: Int,
)

@JsonClass(generateAdapter = true)
data class ReleaseResponseDto(
    @Json(name = "success") val success: Boolean,
    @Json(name = "message") val message: String? = null,
)

@JsonClass(generateAdapter = true)
data class ListClaimsResponseDto(
    @Json(name = "claims") val claims: List<ClaimDto>,
)

@JsonClass(generateAdapter = true)
data class UploadEvidenceResponseDto(
    @Json(name = "id") val id: Int,
    @Json(name = "success") val success: Boolean,
    @Json(name = "message") val message: String? = null,
)
