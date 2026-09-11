package au.edu.fireballs.stage4.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import au.edu.fireballs.stage4.data.local.LocalDecisionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalDecisionDao {
    @Upsert
    suspend fun saveDecision(decision: LocalDecisionEntity)

    @Query("SELECT * FROM local_decision WHERE synced = 0")
    suspend fun getUnsynced(): List<LocalDecisionEntity>

    @Query("SELECT * FROM local_decision WHERE surveyId = :surveyId")
    fun observeDecisionsForSurvey(surveyId: Long): Flow<List<LocalDecisionEntity>>

    @Query("SELECT * FROM local_decision WHERE inferenceResultId = :inferenceResultId")
    fun getVerdict(inferenceResultId: Long): Flow<LocalDecisionEntity?>

    @Query("DELETE FROM local_decision WHERE inferenceResultId = :inferenceResultId")
    suspend fun deleteByInferenceResultId(inferenceResultId: Long)

    @Query(
        """
        UPDATE local_decision
        SET synced = 1, syncedAt = :syncedAt, syncFailedReason = NULL
        WHERE inferenceResultId = :inferenceResultId
        """,
    )
    suspend fun markSynced(
        inferenceResultId: Long,
        syncedAt: String,
    )

    @Query(
        """
        UPDATE local_decision
        SET synced = 0, syncFailedReason = :reason
        WHERE inferenceResultId = :inferenceResultId
        """,
    )
    suspend fun markFailed(
        inferenceResultId: Long,
        reason: String,
    )

    @Query("DELETE FROM local_decision")
    suspend fun deleteAll()
}
