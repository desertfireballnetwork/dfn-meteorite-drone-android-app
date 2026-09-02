package au.edu.fireballs.stage4.data.tiles

import java.io.File
import java.io.IOException
import java.io.InputStream

class TileStore(
    private val baseDir: File,
) {
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
        val file = tileFile(surveyId, candidateId, z, x, y)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IOException("Failed to commit tile $file")
        }
    }

    fun surveyTilesDirectory(surveyId: Long): File = File(baseDir, surveyId.toString())

    fun hasSurvey(surveyId: Long): Boolean = surveyTilesDirectory(surveyId).isDirectory

    fun deleteSurveyTiles(surveyId: Long) {
        surveyTilesDirectory(surveyId).deleteRecursively()
    }

    fun deleteAll() {
        baseDir.deleteRecursively()
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

    companion object {
        const val MAX_TILE_BYTES = 1_048_576
        private const val MAX_TILE_ZOOM = 30
    }
}
