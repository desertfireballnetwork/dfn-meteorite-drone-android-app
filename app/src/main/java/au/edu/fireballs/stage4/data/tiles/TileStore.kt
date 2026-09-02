package au.edu.fireballs.stage4.data.tiles

import java.io.File
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
        val file = tileFile(surveyId, candidateId, z, x, y)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    fun surveyTilesDirectory(surveyId: Long): File = File(baseDir, surveyId.toString())

    fun hasSurvey(surveyId: Long): Boolean = surveyTilesDirectory(surveyId).isDirectory

    fun deleteSurveyTiles(surveyId: Long) {
        surveyTilesDirectory(surveyId).deleteRecursively()
    }

    fun deleteAll() {
        baseDir.deleteRecursively()
    }

    private fun tileFile(
        surveyId: Long,
        candidateId: Long,
        z: Int,
        x: Int,
        y: Int,
    ): File = File(baseDir, "$surveyId/$candidateId/$z/$x/$y.png")
}
