package au.edu.fireballs.stage4.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import au.edu.fireballs.stage4.data.local.SatelliteRegionEntity

@Dao
interface SatelliteRegionDao {
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM satellite_region
            WHERE surveyId = :surveyId AND signature = :signature AND completed = 1
        )
        """,
    )
    suspend fun contains(
        surveyId: Long,
        signature: String,
    ): Boolean

    @Upsert
    suspend fun upsert(region: SatelliteRegionEntity)

    @Query("DELETE FROM satellite_region WHERE surveyId = :surveyId")
    suspend fun deleteForSurvey(surveyId: Long)
}
