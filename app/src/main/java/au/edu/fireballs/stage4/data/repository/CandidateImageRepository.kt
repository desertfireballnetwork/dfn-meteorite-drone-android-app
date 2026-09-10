package au.edu.fireballs.stage4.data.repository

import android.content.Context
import coil.request.ImageRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class CandidateImageRepository
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        @param:Named("serverUrl") private val serverUrl: String,
    ) {
        fun getCroppedImageUrl(inferenceResultId: Long): String =
            "${serverUrl.trimEnd('/')}/image_survey_cropped/$inferenceResultId/"

        fun getCandidateTileUrlPattern(
            surveyId: Long,
            inferenceResultId: Long,
        ): String {
            val base = serverUrl.trimEnd('/')
            return "$base/image_geotiff_candidate_tile/$surveyId/$inferenceResultId/{z}/{x}/{y}/"
        }

        fun getLocalCropImageFile(
            surveyId: Long,
            inferenceResultId: Long,
        ): File? {
            val file = File(context.filesDir, "tiles/$surveyId/$inferenceResultId.jpg")
            return if (file.exists() && file.length() > 0L) file else null
        }

        fun buildCroppedImageRequest(
            inferenceResultId: Long,
            surveyId: Long,
            retryKey: Int = 0,
        ): ImageRequest {
            val data =
                getLocalCropImageFile(surveyId, inferenceResultId)
                    ?: getCroppedImageUrl(inferenceResultId)
            val cacheKey = "crop-$surveyId-$inferenceResultId-$retryKey"
            return ImageRequest
                .Builder(context)
                .data(data)
                .crossfade(true)
                .memoryCacheKey(cacheKey)
                .setParameter("retry", retryKey)
                .build()
        }
    }
