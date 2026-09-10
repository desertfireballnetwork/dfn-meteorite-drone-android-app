package au.edu.fireballs.stage4.data.tiles

sealed interface TileDownloadResult {
    data class Success(
        val tileCount: Int,
    ) : TileDownloadResult

    data object AuthExpired : TileDownloadResult

    data class PermanentHttp(
        val code: Int,
    ) : TileDownloadResult

    data class TransientHttp(
        val code: Int,
    ) : TileDownloadResult

    data object NetworkError : TileDownloadResult

    data class StorageError(
        val message: String,
    ) : TileDownloadResult
}
