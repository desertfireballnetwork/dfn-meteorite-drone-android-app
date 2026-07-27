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

    @Query("SELECT * FROM offline_bundle WHERE surveyId = :surveyId ORDER BY rowId DESC LIMIT 1")
    fun observeLatestBundleForSurvey(surveyId: Long): Flow<OfflineBundleEntity?>

    @Query("DELETE FROM offline_bundle WHERE surveyId = :surveyId")
    suspend fun deleteForSurvey(surveyId: Long)

    @Query("DELETE FROM offline_bundle")
    suspend fun deleteAll()
}
