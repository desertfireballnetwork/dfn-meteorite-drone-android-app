package au.edu.fireballs.stage4.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import au.edu.fireballs.stage4.data.local.OfflineBundleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OfflineBundleDao {
    @Upsert
    suspend fun insert(bundle: OfflineBundleEntity): Long

    @Query("SELECT * FROM offline_bundle WHERE manifestId = :manifestId")
    suspend fun getByManifestId(manifestId: String): OfflineBundleEntity?

    @Query("SELECT * FROM offline_bundle ORDER BY createdAt DESC")
    suspend fun getAll(): List<OfflineBundleEntity>

    @Query(
        """
        SELECT * FROM offline_bundle
        WHERE surveyId = :surveyId
        ORDER BY createdAt DESC
        LIMIT 1
        """,
    )
    fun observeLatestBundleForSurvey(surveyId: Long): Flow<OfflineBundleEntity?>

    @Query(
        """
        UPDATE offline_bundle
        SET state = :state, updatedAt = :updatedAt
        WHERE manifestId = :manifestId
        """,
    )
    suspend fun updateState(
        manifestId: String,
        state: String,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE offline_bundle
        SET state = 'COMPLETE', updatedAt = :updatedAt
        WHERE manifestId = :manifestId
        AND NOT EXISTS (
            SELECT 1 FROM tile_manifest
            WHERE manifestId = :manifestId AND completed = 0
        )
        AND NOT EXISTS (
            SELECT 1 FROM candidate_crop_manifest
            WHERE manifestId = :manifestId AND completed = 0
        )
        AND NOT EXISTS (
            SELECT 1 FROM satellite_region
            WHERE manifestId = :manifestId AND completed = 0
        )
        """,
    )
    suspend fun markCompleteIfReady(
        manifestId: String,
        updatedAt: Long,
    ): Int

    @Query("DELETE FROM offline_bundle WHERE manifestId = :manifestId")
    suspend fun deleteByManifestId(manifestId: String)

    @Query("DELETE FROM offline_bundle WHERE surveyId = :surveyId")
    suspend fun deleteForSurvey(surveyId: Long)

    @Query("DELETE FROM offline_bundle")
    suspend fun deleteAll()
}
