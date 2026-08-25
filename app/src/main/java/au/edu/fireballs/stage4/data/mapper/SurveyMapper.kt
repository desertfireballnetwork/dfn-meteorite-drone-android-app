package au.edu.fireballs.stage4.data.mapper

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
