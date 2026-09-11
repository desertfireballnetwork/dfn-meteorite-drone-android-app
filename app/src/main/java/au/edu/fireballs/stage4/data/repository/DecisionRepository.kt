package au.edu.fireballs.stage4.data.repository

import au.edu.fireballs.stage4.data.local.LocalDecisionEntity
import au.edu.fireballs.stage4.data.local.dao.LocalDecisionDao
import au.edu.fireballs.stage4.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class VerdictCounts(
    val yes: Int,
    val no: Int,
)

@Singleton
class DecisionRepository
    @Inject
    constructor(
        private val localDecisionDao: LocalDecisionDao,
        @param:IoDispatcher private val io: CoroutineDispatcher,
    ) {
        fun upsertVerdict(
            inferenceResultId: Long,
            surveyId: Long,
            isMeteorite: Boolean,
            detectionTagId: Long?,
        ): Flow<LocalDecisionEntity> =
            flow {
                localDecisionDao.saveDecision(
                    LocalDecisionEntity(
                        inferenceResultId = inferenceResultId,
                        surveyId = surveyId,
                        verdict = isMeteorite,
                        detectionTagId = detectionTagId,
                        capturedAt = Instant.now().toString(),
                        evidencePhotoRowId = null,
                        synced = false,
                    ),
                )
                emitAll(getVerdict(inferenceResultId).filterNotNull())
            }.flowOn(io)

        fun getVerdict(inferenceResultId: Long): Flow<LocalDecisionEntity?> =
            localDecisionDao.getVerdict(inferenceResultId).flowOn(io)

        fun clearVerdict(inferenceResultId: Long): Flow<Unit> =
            flow {
                localDecisionDao.deleteByInferenceResultId(inferenceResultId)
                emit(Unit)
            }.flowOn(io)

        fun getVerdictCounts(surveyId: Long): Flow<VerdictCounts> =
            localDecisionDao
                .observeDecisionsForSurvey(surveyId)
                .map { decisions ->
                    VerdictCounts(
                        yes = decisions.count { it.verdict },
                        no = decisions.count { !it.verdict },
                    )
                }.flowOn(io)
    }
