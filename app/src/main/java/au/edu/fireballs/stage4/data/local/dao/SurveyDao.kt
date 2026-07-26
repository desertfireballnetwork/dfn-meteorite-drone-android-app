package au.edu.fireballs.stage4.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import au.edu.fireballs.stage4.data.local.SurveyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SurveyDao {
    @Upsert
    suspend fun upsert(survey: SurveyEntity)

    @Upsert
    suspend fun upsertAll(surveys: List<SurveyEntity>)

    @Query("SELECT * FROM survey WHERE id = :id")
    suspend fun getById(id: Long): SurveyEntity?

    @Query("SELECT * FROM survey WHERE id = :id")
    fun observeById(id: Long): Flow<SurveyEntity?>

    @Query("SELECT * FROM survey ORDER BY lastViewed DESC")
    fun observeAllSurveys(): Flow<List<SurveyEntity>>

    @Query("UPDATE survey SET lastViewed = :timestamp WHERE id = :id")
    suspend fun updateLastViewed(
        id: Long,
        timestamp: Long = System.currentTimeMillis(),
    )

    @Query("DELETE FROM survey WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM survey")
    suspend fun deleteAll()
}
