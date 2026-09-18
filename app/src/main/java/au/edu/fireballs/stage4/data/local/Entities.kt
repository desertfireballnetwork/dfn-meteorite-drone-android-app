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
    val surveyedAreasJson: String? = null,
    val detectionTagsJson: String? = null,
    val userLocationsJson: String? = null,
    val showGeolocationAccuracyCircle: Boolean = true,
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
    val geoCentroidLat: Double?,
    val geoCentroidLon: Double?,
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
    val isClaimedByOther: Boolean = false,
    val claimOwnerUsername: String?,
    val serverVerdict: Int = 0, // 0=unprocessed, 1=yes, 2=no
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
        Index(value = ["manifestId"], unique = true),
        Index("surveyId"),
        Index(value = ["surveyId", "sourceVersion"]),
        Index("state"),
    ],
)
data class OfflineBundleEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0L,
    val manifestId: String = "",
    val surveyId: Long,
    val sourceVersion: String = "",
    val radiusMetres: Double = 0.0,
    val minZoom: Int = 0,
    val maxZoom: Int = 0,
    val state: String = "INCOMPLETE",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val totalBytes: Long,
    val tileCount: Int,
    val satelliteRegionCount: Int,
    val candidateCount: Int,
)

@Entity(
    tableName = "tile_manifest",
    indices = [
        Index("manifestId"),
        Index("surveyId"),
        Index("candidateId"),
        Index(
            value = [
                "manifestId",
                "surveyId",
                "candidateId",
                "sourceVersion",
                "radiusMetres",
                "zoom",
                "x",
                "y",
                "kind",
                "expectedFormat",
            ],
            unique = true,
        ),
        Index(value = ["manifestId", "completed"]),
    ],
)
data class TileManifestEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0L,
    val manifestId: String = "",
    val surveyId: Long,
    val candidateId: Long,
    val sourceVersion: String = "",
    val radiusMetres: Double = 0.0,
    val zoom: Int,
    val x: Int,
    val y: Int,
    val kind: String = "",
    val expectedFormat: String = "",
    val completed: Boolean = false,
    val bytes: Long = 0L,
)

@Entity(
    tableName = "candidate_crop_manifest",
    indices = [
        Index("manifestId"),
        Index("surveyId"),
        Index("candidateId"),
        Index(
            value = [
                "manifestId",
                "surveyId",
                "candidateId",
                "sourceVersion",
                "requestSignature",
            ],
            unique = true,
        ),
        Index(value = ["manifestId", "completed"]),
    ],
)
data class CandidateCropManifestEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0L,
    val manifestId: String = "",
    val surveyId: Long,
    val candidateId: Long,
    val sourceVersion: String = "",
    val requestSignature: String = "",
    val completed: Boolean = false,
)

@Entity(
    tableName = "satellite_region",
    indices = [
        Index("manifestId"),
        Index(value = ["surveyId", "signature"], unique = true),
        Index(value = ["manifestId", "surveyId", "sourceVersion", "signature"]),
        Index("pendingDeletion"),
        Index(value = ["manifestId", "completed"]),
    ],
)
data class SatelliteRegionEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0L,
    val manifestId: String = "",
    val surveyId: Long,
    val sourceVersion: String = "",
    val signature: String,
    val completed: Boolean,
    val pendingDeletion: Boolean = false,
    val purgeCategory: String? = null,
    val lastAttemptTime: Long? = null,
)

@Entity(tableName = "sync_run")
data class SyncRunEntity(
    @PrimaryKey val surveyId: Long,
    val phase: String,
    val total: Int,
    val done: Int,
)
