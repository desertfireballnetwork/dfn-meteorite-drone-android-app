package au.edu.fireballs.stage4.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import au.edu.fireballs.stage4.data.local.SatelliteRegionEntity

@Dao
interface SatelliteRegionDao {
    @Upsert
    suspend fun upsert(region: SatelliteRegionEntity)

    @Upsert
    suspend fun upsertAll(regions: List<SatelliteRegionEntity>)

    @Query("SELECT * FROM satellite_region WHERE manifestId = :manifestId")
    suspend fun getForManifest(manifestId: String): List<SatelliteRegionEntity>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM satellite_region
            WHERE surveyId = :surveyId
            AND signature = :signature
            AND completed = 1
            AND pendingDeletion = 0
        )
        """,
    )
    suspend fun contains(
        surveyId: Long,
        signature: String,
    ): Boolean

    @Query(
        """
        UPDATE satellite_region SET completed = :completed
        WHERE manifestId = :manifestId AND signature = :signature
        """,
    )
    suspend fun updateCompletion(
        manifestId: String,
        signature: String,
        completed: Boolean,
    )

    @Query(
        """
        UPDATE satellite_region
        SET pendingDeletion = 1, purgeCategory = NULL, lastAttemptTime = NULL
        WHERE manifestId = :manifestId
        """,
    )
    suspend fun markPendingDeletion(manifestId: String)

    @Query(
        """
        SELECT * FROM satellite_region
        WHERE pendingDeletion = 1
        ORDER BY lastAttemptTime
        """,
    )
    suspend fun getPendingDeletion(): List<SatelliteRegionEntity>

    @Query(
        """
        UPDATE satellite_region
        SET purgeCategory = :purgeCategory, lastAttemptTime = :lastAttemptTime
        WHERE rowId = :rowId AND pendingDeletion = 1
        """,
    )
    suspend fun recordPurgeAttempt(
        rowId: Long,
        purgeCategory: String?,
        lastAttemptTime: Long,
    )

    @Query("DELETE FROM satellite_region WHERE rowId = :rowId AND pendingDeletion = 1")
    suspend fun removePurged(rowId: Long): Int

    @Query(
        "SELECT COUNT(*) FROM satellite_region WHERE manifestId = :manifestId AND completed = 0",
    )
    suspend fun countIncomplete(manifestId: String): Int

    @Query("DELETE FROM satellite_region WHERE manifestId = :manifestId")
    suspend fun deleteForManifest(manifestId: String)

    @Query("DELETE FROM satellite_region WHERE surveyId = :surveyId")
    suspend fun deleteForSurvey(surveyId: Long)

    @Query(
        """
        DELETE FROM satellite_region
        WHERE pendingDeletion = 0
        AND manifestId NOT IN (SELECT manifestId FROM offline_bundle)
        """,
    )
    suspend fun deleteOrphans()
}
