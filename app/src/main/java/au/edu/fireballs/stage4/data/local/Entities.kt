package au.edu.fireballs.stage4.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "survey",
    indices = [
        Index("eventId"),
    ],
)
data class SurveyEntity(
    @PrimaryKey val id: Long,
    val eventId: String,
    val description: String?,
    val created: String, // ISO-8601 UTC
    val hasStage4: Boolean,
    val activeSurvey: Boolean,
    val tilesetId: String?, // Nullable; custom Mapbox tileset
    val latestTaskCreated: String?, // ISO-8601 UTC; null until first candidate fetch
    val baseLat: Double?,
    val baseLon: Double?,
    val lastViewed: Long? = System.currentTimeMillis(),
)

@Entity(
    tableName = "candidate",
    indices = [
        Index("surveyId"),
    ],
)
data class CandidateEntity(
    @PrimaryKey val inferenceResultId: Long,
    val surveyId: Long,
    val imageId: Long,
    val imageFilename: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val geoCentroidLat: Double,
    val geoCentroidLon: Double,
    // Cached footprint polygon serialized as JSON string of [lon,lat] pairs
    val geoAreaJson: String,
    // ML box in source-image pixel coords (x,y center per InferenceResult)
    val boxX: Int,
    val boxY: Int,
    val boxW: Int,
    val boxH: Int,
    val confidence: Float,
    val sizeMw: Float?,
    val sizeMh: Float?,
    // current claim state cached from basecamp
    val isClaimedByMe: Boolean, // ClaimEntity isClaimedByMe is the live state; this is cache only
    val claimOwnerUsername: String?,
)

@Entity(
    tableName = "claim",
    indices = [
        Index("surveyId"),
    ],
)
data class ClaimEntity(
    @PrimaryKey val inferenceResultId: Long, // Unique per candidate
    val surveyId: Long,
    val userId: Long,
    val username: String,
    val claimedAt: String, // ISO-8601 UTC
    val isMine: Boolean,
    val isActive: Boolean,
)

@Entity(
    tableName = "local_decision",
    indices = [
        Index("surveyId"),
    ],
)
data class LocalDecisionEntity(
    @PrimaryKey val inferenceResultId: Long, // Unique per candidate
    val surveyId: Long,
    val verdict: Boolean, // true=yes, false=no
    val detectionTagId: Long?,
    val capturedAt: String, // ISO-8601 UTC; client-set
    val evidencePhotoRowId: Long?, // FK to PendingPhotoUploadEntity.rowId (nullable)
    val synced: Boolean = false,
    val syncedAt: String? = null,
    val syncFailedReason: String? = null, // populated by A-16 on failed sync attempts
)

@Entity(
    tableName = "pending_photo_upload",
    indices = [
        Index("surveyId"),
        Index("inferenceResultId"),
    ],
)
data class PendingPhotoUploadEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0L,
    val surveyId: Long,
    val inferenceResultId: Long,
    val localFilePath: String, // Path on disk to the local JPG
    val capturedAt: String,
    val uploaded: Boolean = false,
    val serverPhotoId: Long? = null,
    val uploadFailedReason: String? = null,
)

@Entity(
    tableName = "offline_bundle",
    indices = [
        Index("surveyId"),
    ],
)
data class OfflineBundleEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0L,
    val surveyId: Long,
    val created: String, // ISO-8601 UTC; creation timestamp
    val totalBytes: Long,
    val tileCount: Int,
    val satelliteRegionCount: Int, // may be >1 if split into sub-regions
    val candidateCount: Int, // number of claimed candidates pushed into the download
    val bufferMeters: Float,
)

@Entity(
    tableName = "tile_manifest",
    indices = [
        Index("surveyId"),
        Index("candidateId"),
    ],
)
data class TileManifestEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0L,
    val surveyId: Long,
    val candidateId: Long,
    val zoom: Int,
    val x: Int,
    val y: Int,
    val bytes: Long = 0L,
)
