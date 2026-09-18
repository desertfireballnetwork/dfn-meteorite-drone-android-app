package au.edu.fireballs.stage4.data.repository

import android.content.Context
import au.edu.fireballs.stage4.data.tiles.deleteRegularFile
import au.edu.fireballs.stage4.data.tiles.deleteTreeNoFollow
import au.edu.fireballs.stage4.data.tiles.pruneEmptyDirectories
import au.edu.fireballs.stage4.data.tiles.replaceFileAtomically
import coil.request.ImageRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

sealed interface CropWriteResult {
    data object Success : CropWriteResult

    data object InvalidContent : CropWriteResult

    data object StorageFull : CropWriteResult

    data class IoFailure(
        val message: String?,
    ) : CropWriteResult
}

@Singleton
class CandidateImageRepository
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        @param:Named("serverUrl") private val serverUrl: String,
    ) {
        private val lock = Any()

        private val cropRoot: File
            get() = File(context.filesDir, CROP_DIR)

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
            val file = File(cropRoot, "$surveyId/$inferenceResultId.jpg")
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

        @Suppress("SwallowedException")
        fun isValidCrop(
            surveyId: Long,
            candidateId: Long,
        ): Boolean {
            if (surveyId < 0L || candidateId < 0L) {
                return false
            }
            val file =
                try {
                    cropFile(surveyId, candidateId)
                } catch (error: IOException) {
                    return false
                }
            val path = file.toPath()
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) {
                return false
            }
            val bytes = readBoundedCrop(file) ?: return false
            return isValidJpegContent(bytes)
        }

        fun writeCrop(
            surveyId: Long,
            candidateId: Long,
            bytes: ByteArray,
        ): CropWriteResult {
            require(surveyId >= 0L && candidateId >= 0L) {
                "Identifiers must be non-negative"
            }
            if (bytes.isEmpty() || bytes.size > MAX_CROP_BYTES) {
                return CropWriteResult.InvalidContent
            }
            val target =
                try {
                    cropFile(surveyId, candidateId)
                } catch (error: IOException) {
                    return CropWriteResult.IoFailure(error.message)
                }
            synchronized(lock) {
                val directory = target.parentFile
                if (directory == null ||
                    (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory)
                ) {
                    return CropWriteResult.IoFailure("Failed to create crop directory")
                }
                val temp =
                    try {
                        Files
                            .createTempFile(directory.toPath(), "${target.name}-", TEMP_SUFFIX)
                            .toFile()
                    } catch (error: IOException) {
                        return cropWriteFailure(error)
                    }
                var promoted = false
                return try {
                    FileOutputStream(temp).use { output ->
                        output.write(bytes)
                        output.flush()
                    }
                    val tempBytes = readBoundedCrop(temp)
                    if (tempBytes == null || !isValidJpegContent(tempBytes)) {
                        CropWriteResult.InvalidContent
                    } else {
                        replaceFileAtomically(temp, target)
                        promoted = true
                        CropWriteResult.Success
                    }
                } catch (error: IOException) {
                    cropWriteFailure(error)
                } finally {
                    if (!promoted) {
                        runCatching { Files.deleteIfExists(temp.toPath()) }
                    }
                }
            }
        }

        fun deleteCrops(keys: Collection<PreDownloadPruneKey.Crop>) {
            synchronized(lock) {
                keys.forEach { key ->
                    require(key.surveyId >= 0L && key.candidateId >= 0L) {
                        "Identifiers must be non-negative"
                    }
                }
                keys.forEach { key ->
                    val file = cropFile(key.surveyId, key.candidateId)
                    deleteRegularFile(file)
                    pruneEmptyDirectories(file.parentFile ?: cropRoot, cropRoot)
                }
            }
        }

        fun clearAllCrops() {
            synchronized(lock) {
                deleteTreeNoFollow(cropRoot)
            }
        }

        fun removeOrphanedTempFiles(): Int {
            synchronized(lock) {
                val root = cropRoot.toPath()
                if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                    return 0
                }
                var removed = 0
                Files.walkFileTree(
                    root,
                    object : SimpleFileVisitor<Path>() {
                        override fun visitFile(
                            file: Path,
                            attrs: BasicFileAttributes,
                        ): FileVisitResult {
                            if (attrs.isRegularFile &&
                                file.fileName.toString().endsWith(TEMP_SUFFIX) &&
                                Files.deleteIfExists(file)
                            ) {
                                removed++
                            }
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFileFailed(
                            file: Path,
                            error: IOException,
                        ): FileVisitResult = FileVisitResult.CONTINUE
                    },
                )
                return removed
            }
        }

        private fun cropFile(
            surveyId: Long,
            candidateId: Long,
        ): File {
            if (Files.isSymbolicLink(cropRoot.toPath())) {
                throw IOException("Crop root must not be a symbolic link")
            }
            val root = cropRoot.canonicalFile
            val target = File(root, "$surveyId/$candidateId.jpg")
            val canonical = target.canonicalFile
            val contained = canonical.path.startsWith(root.path + File.separator)
            if (Files.isSymbolicLink(target.toPath()) || canonical != target || !contained) {
                throw IOException("Crop path escapes the crop root")
            }
            return canonical
        }

        @Suppress("SwallowedException")
        private fun readBoundedCrop(file: File): ByteArray? {
            val length = file.length()
            if (length <= 0L || length > MAX_CROP_BYTES) {
                return null
            }
            return try {
                file.readBytes()
            } catch (error: IOException) {
                null
            }
        }

        companion object {
            const val MAX_CROP_BYTES = 2_097_152
            private const val CROP_DIR = "crops"
            private const val TEMP_SUFFIX = ".tmp.jpg"
        }
    }

internal fun cropWriteFailure(error: IOException): CropWriteResult =
    if (error.isNoSpaceLeft()) {
        CropWriteResult.StorageFull
    } else {
        CropWriteResult.IoFailure(error.message)
    }

private fun Throwable.isNoSpaceLeft(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        val message = current.message?.lowercase()
        if (message != null &&
            (message.contains("enospc") || message.contains("no space left"))
        ) {
            return true
        }
        current = current.cause
    }
    return false
}

internal fun isValidJpegContent(bytes: ByteArray): Boolean {
    if (bytes.size < MIN_JPEG_BYTES) {
        return false
    }
    if ((bytes[0].toInt() and 0xFF) != MARKER_PREFIX ||
        (bytes[1].toInt() and 0xFF) != SOI_MARKER
    ) {
        return false
    }
    var index = 2
    while (index + 1 < bytes.size) {
        if ((bytes[index].toInt() and 0xFF) != MARKER_PREFIX) {
            return false
        }
        var marker = index + 1
        while (marker < bytes.size && (bytes[marker].toInt() and 0xFF) == MARKER_PREFIX) {
            marker++
        }
        if (marker >= bytes.size) {
            return false
        }
        val code = bytes[marker].toInt() and 0xFF
        if (code == EOI_MARKER) {
            return false
        }
        if (code == TEM_MARKER || code in RST_MARKERS) {
            index = marker + 1
            continue
        }
        val length = readUint16(bytes, marker + 1)
        if (length < MIN_SEGMENT_BYTES || marker + 1 + length > bytes.size) {
            return false
        }
        if (code in SOF_MARKERS) {
            val height = readUint16(bytes, marker + 4)
            val width = readUint16(bytes, marker + 6)
            return width > 0 && height > 0
        }
        index = marker + 1 + length
    }
    return false
}

private fun readUint16(
    bytes: ByteArray,
    offset: Int,
): Int {
    if (offset + 1 >= bytes.size) {
        return -1
    }
    return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
}

private const val MIN_JPEG_BYTES = 4
private const val SOI_MARKER = 0xD8
private const val EOI_MARKER = 0xD9
private const val TEM_MARKER = 0x01
private const val MARKER_PREFIX = 0xFF
private const val MIN_SEGMENT_BYTES = 2

private val RST_MARKERS = 0xD0..0xD7

private val SOF_MARKERS =
    setOf(
        0xC0,
        0xC1,
        0xC2,
        0xC3,
        0xC5,
        0xC6,
        0xC7,
        0xC9,
        0xCA,
        0xCB,
        0xCD,
        0xCE,
        0xCF,
    )
