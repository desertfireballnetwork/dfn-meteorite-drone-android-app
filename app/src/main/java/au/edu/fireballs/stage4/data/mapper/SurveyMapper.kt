package au.edu.fireballs.stage4.data.mapper

import au.edu.fireballs.stage4.data.local.SurveyEntity
import au.edu.fireballs.stage4.data.remote.dto.SurveyDto
import au.edu.fireballs.stage4.domain.model.Survey

fun SurveyDto.toDomain(): Survey =
    Survey(
        id = id,
        eventId = eventId,
        description = description.orEmpty(),
        createdIso = created,
        hasStage4 = hasStage4,
        isActive = isStarred,
    )

fun SurveyDto.toEntity(existingEntity: SurveyEntity? = null): SurveyEntity =
    SurveyEntity(
        id = id,
        eventId = eventId,
        description = description,
        created = created,
        hasStage4 = hasStage4,
        activeSurvey = isStarred,
        tilesetId = existingEntity?.tilesetId,
        latestTaskCreated = existingEntity?.latestTaskCreated,
        baseLat = existingEntity?.baseLat,
        baseLon = existingEntity?.baseLon,
        lastViewed = existingEntity?.lastViewed ?: System.currentTimeMillis(),
    )
