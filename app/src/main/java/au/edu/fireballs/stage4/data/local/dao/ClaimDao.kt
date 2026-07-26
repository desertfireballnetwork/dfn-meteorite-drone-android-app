package au.edu.fireballs.stage4.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import au.edu.fireballs.stage4.data.local.ClaimEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ClaimDao {
    @Upsert
    suspend fun upsert(claim: ClaimEntity)

    @Upsert
    suspend fun upsertAll(claims: List<ClaimEntity>)

    @Query(
        """
        SELECT * FROM claim
        WHERE surveyId = :surveyId
        AND (:onlyActive = 0 OR isActive = 1)
        """,
    )
    fun getClaims(
        surveyId: Long,
        onlyActive: Boolean,
    ): Flow<List<ClaimEntity>>

    @Query("SELECT * FROM claim WHERE inferenceResultId = :inferenceResultId")
    suspend fun getByCandidateId(inferenceResultId: Long): ClaimEntity?

    @Query(
        """
        UPDATE claim
        SET isActive = 0
        WHERE userId = :userId
        AND inferenceResultId IN (:candidateIds)
        """,
    )
    suspend fun releaseClaimsForUser(
        userId: Long,
        candidateIds: List<Long>,
    )

    @Query("DELETE FROM claim WHERE surveyId = :surveyId")
    suspend fun deleteForSurvey(surveyId: Long)

    @Query("DELETE FROM claim")
    suspend fun deleteAll()
}
