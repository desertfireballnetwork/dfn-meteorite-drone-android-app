package au.edu.fireballs.stage4.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import au.edu.fireballs.stage4.data.local.CandidateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CandidateDao {
    @Upsert
    suspend fun upsertAll(candidates: List<CandidateEntity>)

    @Upsert
    suspend fun upsert(candidate: CandidateEntity)

    @Query("SELECT * FROM candidate WHERE surveyId = :surveyId")
    fun observeCandidatesForSurvey(surveyId: Long): Flow<List<CandidateEntity>>

    @Query("SELECT * FROM candidate WHERE surveyId = :surveyId")
    suspend fun getCandidatesForSurvey(surveyId: Long): List<CandidateEntity>

    @Query("SELECT * FROM candidate WHERE inferenceResultId = :inferenceResultId")
    suspend fun getById(inferenceResultId: Long): CandidateEntity?

    @Query("DELETE FROM candidate WHERE surveyId = :surveyId")
    suspend fun deleteForSurvey(surveyId: Long)

    @Query("DELETE FROM candidate")
    suspend fun deleteAll()
}
