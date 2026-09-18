package au.edu.fireballs.stage4.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import au.edu.fireballs.stage4.data.local.CandidateCropManifestEntity

@Dao
interface CandidateCropManifestDao {
    @Upsert
    suspend fun insertAll(crops: List<CandidateCropManifestEntity>)

    @Query("SELECT * FROM candidate_crop_manifest WHERE manifestId = :manifestId")
    suspend fun getForManifest(manifestId: String): List<CandidateCropManifestEntity>

    @Query(
        """
        SELECT * FROM candidate_crop_manifest
        WHERE manifestId = :manifestId
        AND surveyId = :surveyId
        AND candidateId = :candidateId
        AND sourceVersion = :sourceVersion
        AND requestSignature = :requestSignature
        """,
    )
    suspend fun getExact(
        manifestId: String,
        surveyId: Long,
        candidateId: Long,
        sourceVersion: String,
        requestSignature: String,
    ): CandidateCropManifestEntity?

    @Query(
        """
        UPDATE candidate_crop_manifest SET completed = :completed
        WHERE manifestId = :manifestId AND rowId = :rowId
        """,
    )
    suspend fun updateCompletion(
        manifestId: String,
        rowId: Long,
        completed: Boolean,
    )

    @Query(
        """
        SELECT COUNT(*) FROM candidate_crop_manifest
        WHERE manifestId = :manifestId AND completed = 0
        """,
    )
    suspend fun countIncomplete(manifestId: String): Int

    @Query("DELETE FROM candidate_crop_manifest WHERE manifestId = :manifestId")
    suspend fun deleteForManifest(manifestId: String)

    @Query(
        """
        DELETE FROM candidate_crop_manifest
        WHERE manifestId NOT IN (SELECT manifestId FROM offline_bundle)
        """,
    )
    suspend fun deleteOrphans()

    @Query("DELETE FROM candidate_crop_manifest")
    suspend fun deleteAll()
}
