package au.edu.fireballs.stage4.data.tiles

import au.edu.fireballs.stage4.data.local.SatelliteRegionEntity
import au.edu.fireballs.stage4.data.local.dao.SatelliteRegionDao
import javax.inject.Inject
import javax.inject.Singleton

interface SatelliteRegionStore {
    suspend fun contains(
        surveyId: Long,
        signature: String,
    ): Boolean

    suspend fun markCompleted(
        surveyId: Long,
        signature: String,
    )

    suspend fun deleteForSurvey(surveyId: Long)
}

@Singleton
class RoomSatelliteRegionStore
    @Inject
    constructor(
        private val dao: SatelliteRegionDao,
    ) : SatelliteRegionStore {
        override suspend fun contains(
            surveyId: Long,
            signature: String,
        ): Boolean = dao.contains(surveyId, signature)

        override suspend fun markCompleted(
            surveyId: Long,
            signature: String,
        ) {
            dao.upsert(
                SatelliteRegionEntity(
                    surveyId = surveyId,
                    signature = signature,
                    completed = true,
                ),
            )
        }

        override suspend fun deleteForSurvey(surveyId: Long) {
            dao.deleteForSurvey(surveyId)
        }
    }

object NoOpSatelliteRegionStore : SatelliteRegionStore {
    override suspend fun contains(
        surveyId: Long,
        signature: String,
    ): Boolean = false

    override suspend fun markCompleted(
        surveyId: Long,
        signature: String,
    ) = Unit

    override suspend fun deleteForSurvey(surveyId: Long) = Unit
}
