package au.edu.fireballs.stage4.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SurveyListResponseDto(
    @Json(name = "surveys") val surveys: List<SurveyDto>,
)

@JsonClass(generateAdapter = true)
data class SurveyDto(
    @Json(name = "id") val id: Long,
    @Json(name = "event_id") val eventId: String,
    @Json(name = "description") val description: String? = null,
    @Json(name = "created") val created: String,
    @Json(name = "has_stage4") val hasStage4: Boolean = false,
    @Json(name = "is_starred") val isStarred: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class Stage4StateDto(
    @Json(name = "survey") val survey: Stage4SurveyDto,
    @Json(name = "base") val base: LatLonDto?,
    @Json(name = "surveyed_areas") val surveyedAreas: List<List<List<Double>>>,
    @Json(name = "unprocessed_candidates") val unprocessedCandidates: List<Stage4CandidateDto>,
    @Json(name = "yes_meteorites") val yesMeteorites: List<Stage4CandidateDto>,
    @Json(name = "no_meteorites") val noMeteorites: List<Stage4CandidateDto>,
    @Json(name = "detection_tags") val detectionTags: List<DetectionTagDto>,
    @Json(name = "user_locations") val userLocations: List<UserLocationDto>,
    @Json(name = "settings") val settings: Stage4SettingsDto,
    @Json(name = "latest_task_created") val latestTaskCreated: String,
)

@JsonClass(generateAdapter = true)
data class Stage4SurveyDto(
    @Json(name = "id") val id: Long,
    @Json(name = "event_id") val eventId: String,
    @Json(name = "tileset_id") val tilesetId: String?,
)

@JsonClass(generateAdapter = true)
data class LatLonDto(
    @Json(name = "lat") val lat: Double,
    @Json(name = "lon") val lon: Double,
)

@JsonClass(generateAdapter = true)
data class DimsDto(
    @Json(name = "w") val w: Int,
    @Json(name = "h") val h: Int,
)

@JsonClass(generateAdapter = true)
data class BoxDto(
    @Json(name = "x") val x: Int,
    @Json(name = "y") val y: Int,
    @Json(name = "w") val w: Int,
    @Json(name = "h") val h: Int,
)

@JsonClass(generateAdapter = true)
data class SizeMDto(
    @Json(name = "w") val w: Double,
    @Json(name = "h") val h: Double,
)

@JsonClass(generateAdapter = true)
data class Stage4CandidateDto(
    @Json(name = "inference_result_id") val inferenceResultId: Long,
    @Json(name = "image_id") val imageId: Long,
    @Json(name = "image_filename") val imageFilename: String,
    @Json(name = "image_dims") val imageDims: DimsDto,
    @Json(name = "geo_centroid") val geoCentroid: LatLonDto?,
    @Json(name = "geo_area") val geoArea: List<List<Double>>?,
    @Json(name = "box") val box: BoxDto,
    @Json(name = "confidence") val confidence: Double,
    @Json(name = "size_m") val sizeM: SizeMDto?,
    @Json(name = "claimed_by_me") val claimedByMe: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class DetectionTagDto(
    @Json(name = "id") val id: Long,
    @Json(name = "name") val name: String,
    @Json(name = "category") val category: String,
)

@JsonClass(generateAdapter = true)
data class UserLocationDto(
    @Json(name = "username") val username: String,
    @Json(name = "full_name") val fullName: String,
    @Json(name = "user_id") val userId: Long,
    @Json(name = "lat") val lat: Double,
    @Json(name = "lon") val lon: Double,
    @Json(name = "processed") val processed: String,
)

@JsonClass(generateAdapter = true)
data class Stage4SettingsDto(
    @Json(name = "show_geolocation_accuracy_circle") val showGeolocationAccuracyCircle: Boolean,
)

@JsonClass(generateAdapter = true)
data class ClaimDto(
    @Json(name = "inference_result_id") val inferenceResultId: Long,
    @Json(name = "user_id") val userId: Long,
    @Json(name = "username") val username: String? = null,
    @Json(name = "full_name") val fullName: String? = null,
    @Json(name = "claimed_at") val claimedAt: String? = null,
    @Json(name = "is_me") val isMe: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class ClaimRequestDto(
    @Json(name = "inference_result_ids") val inferenceResultIds: List<Long>,
)

@JsonClass(generateAdapter = true)
data class ClaimResponseDto(
    @Json(name = "claimed") val claimed: List<Long> = emptyList(),
    @Json(name = "already_claimed") val alreadyClaimed: List<Long> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class ReleaseRequestDto(
    @Json(name = "inference_result_ids") val inferenceResultIds: List<Long>,
)

@JsonClass(generateAdapter = true)
data class ReleaseResponseDto(
    @Json(name = "released") val released: List<Long> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class ListClaimsResponseDto(
    @Json(name = "claims") val claims: List<ClaimDto>,
)
