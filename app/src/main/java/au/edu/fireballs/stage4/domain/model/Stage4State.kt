package au.edu.fireballs.stage4.domain.model

import au.edu.fireballs.stage4.data.remote.dto.DetectionTagDto
import au.edu.fireballs.stage4.data.remote.dto.LatLonDto
import au.edu.fireballs.stage4.data.remote.dto.Stage4CandidateDto
import au.edu.fireballs.stage4.data.remote.dto.Stage4StateDto
import au.edu.fireballs.stage4.data.remote.dto.Stage4SurveyDto
import au.edu.fireballs.stage4.data.remote.dto.UserLocationDto

data class Stage4Survey(
    val id: Long,
    val eventId: String,
    val tilesetId: String?,
)

data class GeoCoordinate(
    val latitude: Double,
    val longitude: Double,
)

data class ImageDims(
    val w: Int,
    val h: Int,
)

data class BoundingBox(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
)

data class SizeMetres(
    val w: Double,
    val h: Double,
)

data class Stage4Candidate(
    val inferenceResultId: Long,
    val imageId: Long,
    val imageFilename: String,
    val imageDims: ImageDims,
    val geoCentroid: GeoCoordinate?,
    val geoArea: List<List<Double>>?,
    val box: BoundingBox,
    val confidence: Double,
    val sizeM: SizeMetres?,
    val claimedByMe: Boolean = false,
    val claimedByOther: Boolean = false,
)

data class DetectionTag(
    val id: Long,
    val name: String,
    val category: String,
)

data class UserLocation(
    val username: String,
    val fullName: String,
    val userId: Long,
    val coordinate: GeoCoordinate,
    val processedAt: String,
)

data class Stage4State(
    val survey: Stage4Survey,
    val base: GeoCoordinate?,
    val surveyedAreas: List<List<List<Double>>>,
    val unprocessedCandidates: List<Stage4Candidate>,
    val yesMeteorites: List<Stage4Candidate>,
    val noMeteorites: List<Stage4Candidate>,
    val detectionTags: List<DetectionTag>,
    val userLocations: List<UserLocation>,
    val showGeolocationAccuracyCircle: Boolean,
    val latestTaskCreated: String,
)

data class MapCameraTarget(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
)

private const val INITIAL_CAMERA_ZOOM = 13.0

fun Stage4StateDto.toDomain(): Stage4State =
    Stage4State(
        survey = survey.toDomain(),
        base = base?.toGeoCoordinate(),
        surveyedAreas = surveyedAreas,
        unprocessedCandidates = unprocessedCandidates.map { it.toDomain() },
        yesMeteorites = yesMeteorites.map { it.toDomain() },
        noMeteorites = noMeteorites.map { it.toDomain() },
        detectionTags = detectionTags.map { it.toDomain() },
        userLocations = userLocations.map { it.toDomain() },
        showGeolocationAccuracyCircle = settings.showGeolocationAccuracyCircle,
        latestTaskCreated = latestTaskCreated,
    )

fun resolveInitialCamera(state: Stage4State): MapCameraTarget? {
    state.base?.let { base ->
        return MapCameraTarget(base.latitude, base.longitude, INITIAL_CAMERA_ZOOM)
    }
    val firstVertex =
        state.surveyedAreas
            .asSequence()
            .flatMap { area -> area.asSequence() }
            .firstOrNull { it.size >= 2 }
            ?: return null
    return MapCameraTarget(
        latitude = firstVertex[1],
        longitude = firstVertex[0],
        zoom = INITIAL_CAMERA_ZOOM,
    )
}

private fun Stage4SurveyDto.toDomain(): Stage4Survey =
    Stage4Survey(id = id, eventId = eventId, tilesetId = tilesetId)

private fun LatLonDto.toGeoCoordinate(): GeoCoordinate =
    GeoCoordinate(latitude = lat, longitude = lon)

private fun Stage4CandidateDto.toDomain(): Stage4Candidate =
    Stage4Candidate(
        inferenceResultId = inferenceResultId,
        imageId = imageId,
        imageFilename = imageFilename,
        imageDims = ImageDims(w = imageDims.w, h = imageDims.h),
        geoCentroid = geoCentroid?.toGeoCoordinate(),
        geoArea = geoArea,
        box = BoundingBox(x = box.x, y = box.y, w = box.w, h = box.h),
        confidence = confidence,
        sizeM = sizeM?.let { SizeMetres(w = it.w, h = it.h) },
        claimedByMe = claimedByMe,
        claimedByOther = false,
    )

private fun DetectionTagDto.toDomain(): DetectionTag =
    DetectionTag(id = id, name = name, category = category)

private fun UserLocationDto.toDomain(): UserLocation =
    UserLocation(
        username = username,
        fullName = fullName,
        userId = userId,
        coordinate = GeoCoordinate(latitude = lat, longitude = lon),
        processedAt = processed,
    )
