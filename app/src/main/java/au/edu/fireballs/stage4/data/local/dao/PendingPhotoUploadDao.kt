package au.edu.fireballs.stage4.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import au.edu.fireballs.stage4.data.local.PendingPhotoUploadEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingPhotoUploadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pendingPhotoUpload: PendingPhotoUploadEntity): Long

    @Query(
        "SELECT * FROM pending_photo_upload WHERE uploaded = 0 " +
            "AND (uploadFailedReason IS NULL OR uploadFailedReason != 'cross_campaign')",
    )
    suspend fun getUnuploaded(): List<PendingPhotoUploadEntity>

    @Query(
        "SELECT * FROM pending_photo_upload WHERE inferenceResultId = :inferenceResultId ORDER BY capturedAt DESC",
    )
    fun getLocalPhotosForCandidate(inferenceResultId: Long): Flow<List<PendingPhotoUploadEntity>>

    @Query(
        """
        UPDATE pending_photo_upload
        SET uploaded = 1, serverPhotoId = :serverPhotoId, uploadFailedReason = NULL
        WHERE rowId = :rowId
        """,
    )
    suspend fun markUploaded(
        rowId: Long,
        serverPhotoId: Long,
    )

    @Query(
        """
        UPDATE pending_photo_upload
        SET uploaded = 0, uploadFailedReason = :reason
        WHERE rowId = :rowId
        """,
    )
    suspend fun markFailed(
        rowId: Long,
        reason: String,
    )

    @Query("DELETE FROM pending_photo_upload")
    suspend fun deleteAll()
}
