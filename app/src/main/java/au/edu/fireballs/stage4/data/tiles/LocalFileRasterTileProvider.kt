package au.edu.fireballs.stage4.data.tiles

class LocalFileRasterTileProvider(
    private val tileStore: TileStore,
) {
    fun tile(
        surveyId: Long,
        candidateId: Long,
        z: Int,
        x: Int,
        y: Int,
    ): ByteArray {
        val input = tileStore.read(surveyId, candidateId, z, x, y)
        val result =
            when (input) {
                null -> TRANSPARENT_PNG
                else -> input.use { it.readBytes() }
            }
        return result
    }

    companion object {
        val TRANSPARENT_PNG: ByteArray =
            byteArrayOf(
                0x89.toByte(),
                0x50.toByte(),
                0x4E.toByte(),
                0x47.toByte(),
                0x0D.toByte(),
                0x0A.toByte(),
                0x1A.toByte(),
                0x0A.toByte(),
                0x00.toByte(),
                0x00.toByte(),
                0x00.toByte(),
                0x0D.toByte(),
                0x49.toByte(),
                0x48.toByte(),
                0x44.toByte(),
                0x52.toByte(),
                0x00.toByte(),
                0x00.toByte(),
                0x00.toByte(),
                0x01.toByte(),
                0x00.toByte(),
                0x00.toByte(),
                0x00.toByte(),
                0x01.toByte(),
                0x08.toByte(),
                0x06.toByte(),
                0x00.toByte(),
                0x00.toByte(),
                0x00.toByte(),
                0x1F.toByte(),
                0x15.toByte(),
                0xC4.toByte(),
                0x89.toByte(),
                0x00.toByte(),
                0x00.toByte(),
                0x00.toByte(),
                0x0D.toByte(),
                0x49.toByte(),
                0x44.toByte(),
                0x41.toByte(),
                0x54.toByte(),
                0x78.toByte(),
                0x9C.toByte(),
                0x63.toByte(),
                0x00.toByte(),
                0x01.toByte(),
                0x00.toByte(),
                0x00.toByte(),
                0x05.toByte(),
                0x00.toByte(),
                0x01.toByte(),
                0x0D.toByte(),
                0x0A.toByte(),
                0x2D.toByte(),
                0xB4.toByte(),
                0x00.toByte(),
                0x00.toByte(),
                0x00.toByte(),
                0x00.toByte(),
                0x49.toByte(),
                0x45.toByte(),
                0x4E.toByte(),
                0x44.toByte(),
                0xAE.toByte(),
                0x42.toByte(),
                0x60.toByte(),
                0x82.toByte(),
            )
    }
}
