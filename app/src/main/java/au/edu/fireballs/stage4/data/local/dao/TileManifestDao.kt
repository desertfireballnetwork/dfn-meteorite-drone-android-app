package au.edu.fireballs.stage4.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import au.edu.fireballs.stage4.data.local.TileManifestEntity

@Dao
interface TileManifestDao {
    @Upsert
    suspend fun insertAll(tiles: List<TileManifestEntity>)

    @Query("SELECT * FROM tile_manifest WHERE surveyId = :surveyId")
    suspend fun getTilesForSurvey(surveyId: Long): List<TileManifestEntity>

    @Query("DELETE FROM tile_manifest WHERE surveyId = :surveyId")
    suspend fun deleteForSurvey(surveyId: Long)

    @Query("DELETE FROM tile_manifest")
    suspend fun deleteAll()
}
