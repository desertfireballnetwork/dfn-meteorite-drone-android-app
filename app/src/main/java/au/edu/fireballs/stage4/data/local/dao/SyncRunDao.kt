package au.edu.fireballs.stage4.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import au.edu.fireballs.stage4.data.local.SyncRunEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncRunDao {
    @Upsert
    suspend fun upsert(run: SyncRunEntity)

    @Query("SELECT * FROM sync_run WHERE surveyId = :surveyId")
    fun observeRun(surveyId: Long): Flow<SyncRunEntity?>

    @Query("DELETE FROM sync_run WHERE surveyId = :surveyId")
    suspend fun clear(surveyId: Long)
}
