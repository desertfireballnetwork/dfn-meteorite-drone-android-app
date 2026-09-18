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

    @Query("SELECT * FROM tile_manifest WHERE manifestId = :manifestId")
    suspend fun getForManifest(manifestId: String): List<TileManifestEntity>

    @Query(
        """
        SELECT * FROM tile_manifest
        WHERE manifestId = :manifestId
        AND surveyId = :surveyId
        AND candidateId = :candidateId
        AND sourceVersion = :sourceVersion
        AND radiusMetres = :radiusMetres
        AND zoom = :zoom
        AND x = :x
        AND y = :y
        AND kind = :kind
        AND expectedFormat = :expectedFormat
        """,
    )
    suspend fun getExact(
        manifestId: String,
        surveyId: Long,
        candidateId: Long,
        sourceVersion: String,
        radiusMetres: Double,
        zoom: Int,
        x: Int,
        y: Int,
        kind: String,
        expectedFormat: String,
    ): TileManifestEntity?

    @Query(
        """
        UPDATE tile_manifest SET completed = :completed, bytes = :bytes
        WHERE manifestId = :manifestId AND rowId = :rowId
        """,
    )
    suspend fun updateCompletion(
        manifestId: String,
        rowId: Long,
        completed: Boolean,
        bytes: Long,
    )

    @Query(
        "SELECT COUNT(*) FROM tile_manifest WHERE manifestId = :manifestId AND completed = 0",
    )
    suspend fun countIncomplete(manifestId: String): Int

    @Query("DELETE FROM tile_manifest WHERE manifestId = :manifestId")
    suspend fun deleteForManifest(manifestId: String)

    @Query("DELETE FROM tile_manifest WHERE surveyId = :surveyId")
    suspend fun deleteForSurvey(surveyId: Long)

    @Query(
        """
        DELETE FROM tile_manifest
        WHERE manifestId NOT IN (SELECT manifestId FROM offline_bundle)
        """,
    )
    suspend fun deleteOrphans()

    @Query("DELETE FROM tile_manifest")
    suspend fun deleteAll()
}
