package au.edu.fireballs.stage4.data.tiles

import java.io.File
import java.io.IOException
import java.io.InputStream

class TileStore(
    private val baseDir: File,
    private val scopeProvider: AccountScopeProvider = NoScopeProvider,
    private val quotaBytes: Long = DEFAULT_QUOTA_BYTES,
) {
    private var totalBytes: Long = computeTotalBytes(baseDir)

    fun contains(
        surveyId: Long,
        candidateId: Long,
        z: Int,
        x: Int,
        y: Int,
    ): Boolean = tileFile(surveyId, candidateId, z, x, y).isFile

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
        check(totalBytes + bytes.size <= quotaBytes) { "Tile storage quota exceeded" }
        val file = tileFile(surveyId, candidateId, z, x, y)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IOException("Failed to commit tile $file")
        }
        totalBytes += bytes.size
    }

    fun surveyTilesDirectory(surveyId: Long): File {
        require(surveyId >= 0L) { "Survey id must be non-negative" }
        return File(scopeDir(), surveyId.toString())
    }

    fun hasSurvey(surveyId: Long): Boolean {
        require(surveyId >= 0L) { "Survey id must be non-negative" }
        return surveyTilesDirectory(surveyId).isDirectory
    }

    fun deleteSurveyTiles(surveyId: Long) {
        require(surveyId >= 0L) { "Survey id must be non-negative" }
        val dir = surveyTilesDirectory(surveyId)
        val removed = dirTotalSize(dir)
        dir.deleteRecursively()
        totalBytes = (totalBytes - removed).coerceAtLeast(0L)
    }

    fun deleteAll() {
        baseDir.deleteRecursively()
        totalBytes = 0L
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

    private fun scopeDir(): File {
        val scope = scopeProvider.currentScope()
        if (scope.isEmpty()) {
            return baseDir
        }
        require(scope.none { it == '/' || it == '\\' }) {
            "Invalid tile storage scope"
        }
        return File(baseDir, scope)
    }

    private fun tileFile(
        surveyId: Long,
        candidateId: Long,
        z: Int,
        x: Int,
        y: Int,
    ): File = File(scopeDir(), "$surveyId/$candidateId/$z/$x/$y.png")

    private fun computeTotalBytes(root: File): Long =
        root
            .walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }

    private fun dirTotalSize(dir: File): Long =
        if (dir.isDirectory) {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else {
            0L
        }

    companion object {
        const val MAX_TILE_BYTES = 1_048_576
        const val DEFAULT_QUOTA_BYTES = 512L * 1024L * 1024L
        private const val MAX_TILE_ZOOM = 30

        private object NoScopeProvider : AccountScopeProvider {
            override fun currentScope(): String = ""
        }
    }
}
