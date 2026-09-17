package au.edu.fireballs.stage4.data.tiles

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AccessDeniedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

data class TileStoreMeasuredUsage(
    val geotiffBytes: Long,
    val ownedTempCacheBytes: Long,
)

class TileStore(
    private val baseDir: File,
    private val quotaBytes: Long = DEFAULT_QUOTA_BYTES,
) {
    private val lock = Any()
    private var totalBytes: Long = computeTotalBytes(baseDir)

    fun contains(
        surveyId: Long,
        candidateId: Long,
        z: Int,
        x: Int,
        y: Int,
    ): Boolean = tileFile(surveyId, candidateId, z, x, y).isFile

    fun contains(
        surveyId: Long,
        candidateId: Long,
        coord: TileCoord,
    ): Boolean = contains(surveyId, candidateId, coord.z, coord.x, coord.y)

    fun candidateTiles(
        surveyId: Long,
        candidateId: Long,
        zoom: Int,
    ): List<TileCoord> {
        val dir = File(baseDir, "$surveyId/$candidateId/$zoom")
        return dir
            .listFiles()
            ?.flatMap { xDirectory ->
                val x = xDirectory.name.toIntOrNull()
                if (x == null || !xDirectory.isDirectory) {
                    emptyList()
                } else {
                    xDirectory
                        .listFiles()
                        ?.mapNotNull { yFile ->
                            yFile
                                .name
                                .removeSuffix(".png")
                                .toIntOrNull()
                                ?.let { y -> TileCoord(zoom, x, y) }
                        }.orEmpty()
                }
            }.orEmpty()
    }

    fun hasCandidate(
        surveyId: Long,
        candidateId: Long,
    ): Boolean {
        require(surveyId >= 0L && candidateId >= 0L) { "Identifiers must be non-negative" }
        return File(baseDir, "$surveyId/$candidateId").isDirectory
    }

    fun read(
        surveyId: Long,
        candidateId: Long,
        z: Int,
        x: Int,
        y: Int,
    ): InputStream? {
        val file = tileFile(surveyId, candidateId, z, x, y)
        return if (file.isFile) file.inputStream() else null
    }

    fun read(
        surveyId: Long,
        candidateId: Long,
        coord: TileCoord,
    ): InputStream? = read(surveyId, candidateId, coord.z, coord.x, coord.y)

    @Suppress("SwallowedException")
    fun write(
        surveyId: Long,
        candidateId: Long,
        z: Int,
        x: Int,
        y: Int,
        bytes: ByteArray,
    ) {
        validateTile(surveyId, candidateId, z, x, y)
        require(bytes.size <= MAX_TILE_BYTES) { "Tile exceeds maximum encoded size" }
        synchronized(lock) {
            val file = tileFile(surveyId, candidateId, z, x, y)
            val previousSize = if (file.isFile) file.length() else 0L
            val updatedTotal = totalBytes - previousSize + bytes.size
            check(updatedTotal <= quotaBytes) { "Tile storage quota exceeded" }
            check(file.parentFile?.mkdirs() != false || file.parentFile?.isDirectory == true) {
                "Failed to create tile directory"
            }
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeBytes(bytes)
            try {
                java.nio.file.Files.move(
                    temp.toPath(),
                    file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (error: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(
                    temp.toPath(),
                    file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
            totalBytes = updatedTotal
        }
    }

    fun remainingQuotaBytes(): Long = quotaBytes - totalBytes

    suspend fun measuredUsage(): TileStoreMeasuredUsage =
        synchronized(lock) {
            measureOwnedUsage(baseDir)
        }

    fun surveyTilesDirectory(surveyId: Long): File {
        require(surveyId >= 0L) { "Survey id must be non-negative" }
        return File(baseDir, surveyId.toString())
    }

    fun hasSurvey(surveyId: Long): Boolean {
        require(surveyId >= 0L) { "Survey id must be non-negative" }
        return surveyTilesDirectory(surveyId).isDirectory
    }

    fun deleteSurveyTiles(surveyId: Long) {
        require(surveyId >= 0L) { "Survey id must be non-negative" }
        synchronized(lock) {
            val dir = surveyTilesDirectory(surveyId)
            val removed = dirTotalSize(dir)
            dir.deleteRecursively()
            totalBytes = (totalBytes - removed).coerceAtLeast(0L)
        }
    }

    fun deleteAll() {
        synchronized(lock) {
            baseDir.deleteRecursively()
            totalBytes = 0L
        }
    }

    private fun validateTile(
        surveyId: Long,
        candidateId: Long,
        z: Int,
        x: Int,
        y: Int,
    ) {
        require(surveyId >= 0L && candidateId >= 0L) { "Identifiers must be non-negative" }
        require(z in 0..MAX_TILE_ZOOM) { "Zoom out of range" }
        val span = 1 shl z
        require(x in 0 until span && y in 0 until span) { "Tile coordinates out of range" }
    }

    private fun tileFile(
        surveyId: Long,
        candidateId: Long,
        z: Int,
        x: Int,
        y: Int,
    ): File = File(baseDir, "$surveyId/$candidateId/$z/$x/$y.png")

    private fun computeTotalBytes(root: File): Long =
        measureOwnedUsage(root).let { usage ->
            Math.addExact(usage.geotiffBytes, usage.ownedTempCacheBytes)
        }

    private fun measureOwnedUsage(root: File): TileStoreMeasuredUsage {
        val path = root.toPath()
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return TileStoreMeasuredUsage(0L, 0L)
        }
        var persisted = 0L
        var temporary = 0L
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (attrs.isRegularFile) {
                        if (file.fileName.toString().endsWith(TEMP_SUFFIX)) {
                            temporary = Math.addExact(temporary, attrs.size())
                        } else {
                            persisted = Math.addExact(persisted, attrs.size())
                        }
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    error: IOException,
                ): FileVisitResult =
                    if (error is NoSuchFileException || error is AccessDeniedException) {
                        FileVisitResult.CONTINUE
                    } else {
                        throw error
                    }
            },
        )
        return TileStoreMeasuredUsage(
            geotiffBytes = persisted,
            ownedTempCacheBytes = temporary,
        )
    }

    private fun dirTotalSize(dir: File): Long =
        if (dir.isDirectory) {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else {
            0L
        }

    companion object {
        const val MAX_TILE_BYTES = 16 * 1024 * 1024
        const val DEFAULT_QUOTA_BYTES = 512L * 1024L * 1024L
        private const val MAX_TILE_ZOOM = 30
        private const val TEMP_SUFFIX = ".tmp"
    }
}
