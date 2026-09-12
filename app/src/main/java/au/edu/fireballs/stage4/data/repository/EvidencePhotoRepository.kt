package au.edu.fireballs.stage4.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import au.edu.fireballs.stage4.data.local.PendingPhotoUploadEntity
import au.edu.fireballs.stage4.data.local.dao.PendingPhotoUploadDao
import au.edu.fireballs.stage4.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class EvidencePhotoRepository
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val pendingPhotoUploadDao: PendingPhotoUploadDao,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        suspend fun saveLocally(
            uri: Uri,
            surveyId: Long,
            inferenceResultId: Long,
        ): PendingPhotoUploadEntity =
            withContext(ioDispatcher) {
                val directory =
                    File(
                        context.filesDir,
                        "evidence/$surveyId/$inferenceResultId",
                    )
                directory.mkdirs()
                val target = File(directory, "${System.currentTimeMillis()}.jpg")
                val temp = File(directory, "${target.name}.tmp")
                try {
                    val bitmap = decodeBounded(uri)
                    val rotated = applyExifOrientation(uri, bitmap)
                    temp.outputStream().use { stream ->
                        rotated.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                    }
                    if (!rotated.isRecycled) rotated.recycle()
                    if (!temp.renameTo(target)) {
                        temp.copyTo(target, overwrite = true)
                        temp.delete()
                    }
                    val row =
                        PendingPhotoUploadEntity(
                            surveyId = surveyId,
                            inferenceResultId = inferenceResultId,
                            localFilePath = target.absolutePath,
                            capturedAt = Instant.now().toString(),
                            uploaded = false,
                        )
                    val rowId = pendingPhotoUploadDao.insert(row)
                    row.copy(rowId = rowId)
                } catch (e: IOException) {
                    temp.delete()
                    target.delete()
                    throw e
                } catch (e: IllegalStateException) {
                    temp.delete()
                    target.delete()
                    throw e
                }
            }

        fun getLocalPhotosForCandidate(
            inferenceResultId: Long,
        ): Flow<List<PendingPhotoUploadEntity>> =
            pendingPhotoUploadDao.getLocalPhotosForCandidate(inferenceResultId)

        suspend fun markUploaded(
            rowId: Long,
            serverPhotoId: Long,
        ) {
            pendingPhotoUploadDao.markUploaded(rowId, serverPhotoId)
        }

        private suspend fun decodeBounded(uri: Uri): Bitmap {
            val bounds =
                BitmapFactory
                    .Options()
                    .apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            var sampleSize = 1
            while (
                bounds.outWidth / sampleSize > MAX_EDGE ||
                bounds.outHeight / sampleSize > MAX_EDGE
            ) {
                sampleSize *= 2
            }
            val options =
                BitmapFactory
                    .Options()
                    .apply { inSampleSize = sampleSize }
            val decoded =
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                } ?: error("Unable to decode image from $uri")
            coroutineContext.ensureActive()
            val scale =
                minOf(
                    1f,
                    MAX_EDGE.toFloat() /
                        maxOf(decoded.width, decoded.height).toFloat(),
                )
            if (scale >= 1f) return decoded
            val scaled =
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).toInt(),
                    (decoded.height * scale).toInt(),
                    true,
                )
            if (scaled != decoded) decoded.recycle()
            return scaled
        }

        private fun applyExifOrientation(
            uri: Uri,
            bitmap: Bitmap,
        ): Bitmap {
            val orientation =
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    ExifInterface(stream)
                        .getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL,
                        )
                } ?: ExifInterface.ORIENTATION_NORMAL
            val degrees =
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            if (degrees == 0f) return bitmap
            val matrix = Matrix().apply { postRotate(degrees) }
            val rotated =
                Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    bitmap.width,
                    bitmap.height,
                    matrix,
                    true,
                )
            if (rotated != bitmap) bitmap.recycle()
            return rotated
        }

        private companion object {
            const val MAX_EDGE = 2048
        }
    }
