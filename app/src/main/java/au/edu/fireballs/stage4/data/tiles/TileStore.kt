package au.edu.fireballs.stage4.data.tiles

import au.edu.fireballs.stage4.data.repository.PreDownloadPruneKey
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AccessDeniedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
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
) {
    private val lock = Any()

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
            check(file.parentFile?.mkdirs() != false || file.parentFile?.isDirectory == true) {
                "Failed to create tile directory"
            }
            val parent = file.parentFile ?: error("Tile path has no parent directory")
            val temp = File(parent, "${file.name}.tmp")
            try {
                temp.writeBytes(bytes)
                replaceFileAtomically(temp, file)
            } finally {
                if (temp.exists()) {
                    temp.delete()
                }
            }
        }
    }

    fun isValidTile(
        surveyId: Long,
        candidateId: Long,
        z: Int,
        x: Int,
        y: Int,
    ): Boolean {
        if (!isValidCoordinate(surveyId, candidateId, z, x, y)) {
            return false
        }
        val file = tileFile(surveyId, candidateId, z, x, y)
        val path = file.toPath()
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) {
            return false
        }
        val length = file.length()
        if (length <= 0L || length > MAX_TILE_BYTES.toLong()) {
            return false
        }
        return isValidPayload(file)
    }

    fun isValidPayload(bytes: ByteArray): Boolean =
        bytes.isNotEmpty() &&
            bytes.size <= MAX_TILE_BYTES &&
            isValidPng(bytes)

    fun fileLength(
        surveyId: Long,
        candidateId: Long,
        z: Int,
        x: Int,
        y: Int,
    ): Long? {
        if (!isValidCoordinate(surveyId, candidateId, z, x, y)) {
            return null
        }
        val file = tileFile(surveyId, candidateId, z, x, y)
        val path = file.toPath()
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) {
            return null
        }
        return file.length()
    }

    fun removeOrphanedTempFiles(): Int {
        synchronized(lock) {
            val path = baseDir.toPath()
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                return 0
            }
            var removed = 0
            Files.walkFileTree(
                path,
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

    fun deleteTiles(keys: Collection<PreDownloadPruneKey.Geotiff>) {
        synchronized(lock) {
            if (Files.isSymbolicLink(baseDir.toPath())) {
                throw IOException("Tile root must not be a symbolic link")
            }
            keys.forEach { key ->
                validateTile(key.surveyId, key.candidateId, key.zoom, key.x, key.y)
            }
            keys.forEach { key ->
                val file = tileFile(key.surveyId, key.candidateId, key.zoom, key.x, key.y)
                deleteRegularFile(file)
                pruneEmptyDirectories(file.parentFile ?: baseDir, baseDir)
            }
        }
    }

    suspend fun measuredUsage(): TileStoreMeasuredUsage =

        synchronized(lock) {
            measureOwnedUsage(baseDir)
        }

    fun deleteDerivedTiles(surveyId: Long) {
        require(surveyId >= 0L) { "Survey id must be non-negative" }
        synchronized(lock) {
            val root = baseDir.toPath()
            if (Files.isSymbolicLink(root)) {
                throw IOException("Tile root must not be a symbolic link")
            }
            val surveyDirectory = surveyTilesDirectory(surveyId)
            val surveyPath = surveyDirectory.toPath()
            if (!Files.isDirectory(surveyPath, LinkOption.NOFOLLOW_LINKS)) {
                return
            }
            val derivedTiles = mutableListOf<File>()
            Files.walkFileTree(
                surveyPath,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        val relative = surveyPath.relativize(file)
                        val zoom = relative.getName(1).toString().toIntOrNull()
                        if (relative.nameCount == TILE_PATH_DEPTH &&
                            zoom != null &&
                            zoom < SOURCE_ZOOM
                        ) {
                            derivedTiles += file.toFile()
                        }
                        return FileVisitResult.CONTINUE
                    }
                },
            )
            derivedTiles.forEach { file ->
                deleteRegularFile(file)
                pruneEmptyDirectories(file.parentFile ?: surveyDirectory, baseDir)
            }
        }
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
            deleteTreeNoFollow(surveyTilesDirectory(surveyId))
        }
    }

    fun deleteAll() {
        synchronized(lock) {
            deleteTreeNoFollow(baseDir)
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

    private fun isValidCoordinate(
        surveyId: Long,
        candidateId: Long,
        z: Int,
        x: Int,
        y: Int,
    ): Boolean {
        if (surveyId < 0L || candidateId < 0L) {
            return false
        }
        if (z !in 0..MAX_TILE_ZOOM) {
            return false
        }
        val span = 1 shl z
        return x in 0 until span && y in 0 until span
    }

    private fun isValidPayload(file: File): Boolean {
        val header = readHeader(file) ?: return false
        return isValidPng(header)
    }

    @Suppress("SwallowedException")
    private fun readHeader(file: File): ByteArray? =
        try {
            file.inputStream().use { input ->
                val header = ByteArray(PNG_HEADER_BYTES)
                java.io.DataInputStream(input).readFully(header)
                header
            }
        } catch (error: IOException) {
            null
        }

    private fun isValidPng(bytes: ByteArray): Boolean {
        if (bytes.size < PNG_HEADER_BYTES) {
            return false
        }
        for (index in PNG_SIGNATURE.indices) {
            if (bytes[index] != PNG_SIGNATURE[index]) {
                return false
            }
        }
        return bytes[12] == IHDR_MARKER[0] &&
            bytes[13] == IHDR_MARKER[1] &&
            bytes[14] == IHDR_MARKER[2] &&
            bytes[15] == IHDR_MARKER[3] &&
            readUint32(bytes, 16) > 0L &&
            readUint32(bytes, 20) > 0L
    }

    private fun readUint32(
        bytes: ByteArray,
        offset: Int,
    ): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    private fun tileFile(
        surveyId: Long,
        candidateId: Long,
        z: Int,
        x: Int,
        y: Int,
    ): File = File(baseDir, "$surveyId/$candidateId/$z/$x/$y.png")

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

    companion object {
        const val MAX_TILE_BYTES = 16 * 1024 * 1024
        private const val MAX_TILE_ZOOM = 30
        private const val SOURCE_ZOOM = 20
        private const val TILE_PATH_DEPTH = 4
        private const val TEMP_SUFFIX = ".tmp"
        private const val PNG_HEADER_BYTES = 24
        private val PNG_SIGNATURE =
            byteArrayOf(
                0x89.toByte(),
                0x50.toByte(),
                0x4E.toByte(),
                0x47.toByte(),
                0x0D.toByte(),
                0x0A.toByte(),
                0x1A.toByte(),
                0x0A.toByte(),
            )
        private val IHDR_MARKER =
            byteArrayOf(
                0x49.toByte(),
                0x48.toByte(),
                0x44.toByte(),
                0x52.toByte(),
            )
    }
}
