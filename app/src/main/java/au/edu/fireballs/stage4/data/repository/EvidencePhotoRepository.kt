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
        @Suppress("TooGenericExceptionCaught")
        suspend fun saveLocally(
            uri: Uri,
            surveyId: Long,
            inferenceResultId: Long,
            deleteSource: Boolean = false,
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
                    val oriented = applyExifOrientation(uri, bitmap)
                    temp.outputStream().use { stream ->
                        oriented.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                    }
                    if (!oriented.isRecycled) oriented.recycle()
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
                    if (deleteSource) deleteSourceFile(uri)
                    row.copy(rowId = rowId)
                } catch (e: Exception) {
                    temp.delete()
                    target.delete()
                    if (deleteSource) deleteSourceFile(uri)
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

        private fun deleteSourceFile(uri: Uri) {
            if (uri.scheme == "file") {
                File(uri.path ?: return).delete()
            } else {
                context.contentResolver.delete(uri, null, null)
            }
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
            val matrix =
                exifOrientationMatrix(
                    orientation,
                    bitmap.width,
                    bitmap.height,
                )
            if (matrix.isIdentity) return bitmap
            val transformed =
                Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    bitmap.width,
                    bitmap.height,
                    matrix,
                    true,
                )
            if (transformed != bitmap) bitmap.recycle()
            return transformed
        }

        private companion object {
            const val MAX_EDGE = 2048
        }
    }

internal fun exifOrientationMatrix(
    orientation: Int,
    width: Int,
    height: Int,
): Matrix {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
            matrix.setScale(-1f, 1f)
            matrix.postTranslate((width - 1).toFloat(), 0f)
        }
        ExifInterface.ORIENTATION_ROTATE_180 -> {
            matrix.setRotate(180f)
            matrix.postTranslate((width - 1).toFloat(), (height - 1).toFloat())
        }
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
            matrix.setScale(1f, -1f)
            matrix.postTranslate(0f, (height - 1).toFloat())
        }
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> {
            matrix.setRotate(90f)
            matrix.postTranslate((height - 1).toFloat(), 0f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(270f)
            matrix.postScale(-1f, 1f)
            matrix.postTranslate((height - 1).toFloat(), (width - 1).toFloat())
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> {
            matrix.setRotate(270f)
            matrix.postTranslate(0f, (width - 1).toFloat())
        }
    }
    return matrix
}
