package au.edu.fireballs.stage4.data.tiles

import au.edu.fireballs.stage4.data.remote.LoginRedirectDetector
import au.edu.fireballs.stage4.data.remote.TileService
import au.edu.fireballs.stage4.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CandidateTileDownloader
    @Inject
    constructor(
        private val tileService: TileService,
        private val tileStore: TileStore,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        suspend fun downloadCandidateTiles(
            surveyId: Long,
            candidateId: Long,
            centroidLat: Double,
            centroidLon: Double,
            bufferMeters: Float,
            minZoom: Int,
            maxZoom: Int,
        ): TileDownloadResult =
            withContext(ioDispatcher) {
                val bbox =
                    TileMath.bufferBbox(
                        centroidLat,
                        centroidLon,
                        bufferMeters.toDouble(),
                    )
                var downloaded = 0
                for (z in minZoom..maxZoom) {
                    val tiles =
                        TileMath.tilesForBbox(
                            bbox.minLat,
                            bbox.minLon,
                            bbox.maxLat,
                            bbox.maxLon,
                            z,
                        )
                    for (tile in tiles) {
                        val result = fetchTileWithRetry(surveyId, candidateId, tile)
                        when (result) {
                            is TileDownloadResult.Success -> downloaded += result.tileCount
                            is TileDownloadResult.AuthExpired -> return@withContext result
                            is TileDownloadResult.PermanentHttp -> return@withContext result
                            is TileDownloadResult.TransientHttp -> return@withContext result
                            is TileDownloadResult.NetworkError -> return@withContext result
                            is TileDownloadResult.StorageError -> return@withContext result
                        }
                    }
                }
                TileDownloadResult.Success(downloaded)
            }

        private suspend fun fetchTileWithRetry(
            surveyId: Long,
            candidateId: Long,
            xyz: TileCoord,
        ): TileDownloadResult {
            var attempt = 0
            while (true) {
                val result = fetchTileOnce(surveyId, candidateId, xyz)
                if (
                    result is TileDownloadResult.TransientHttp ||
                    result is TileDownloadResult.NetworkError
                ) {
                    if (attempt >= MAX_RETRIES - 1) {
                        return result
                    }
                    delay(BACKOFF_MS * (1L shl attempt))
                    attempt++
                } else {
                    return result
                }
            }
        }

        @Suppress("SwallowedException")
        private suspend fun fetchTileOnce(
            surveyId: Long,
            candidateId: Long,
            xyz: TileCoord,
        ): TileDownloadResult {
            val tms = TileMath.flipTileY(xyz)
            val response =
                try {
                    tileService.getCandidateTile(
                        surveyId,
                        candidateId,
                        xyz.z,
                        xyz.x,
                        tms.y,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    return TileDownloadResult.NetworkError
                }
            if (LoginRedirectDetector.isLoginRedirect(response)) {
                return TileDownloadResult.AuthExpired
            }
            return when {
                response.isSuccessful && response.body() != null -> {
                    val body = response.body()!!
                    val contentType = body.contentType()
                    if (
                        contentType?.type != "image" ||
                        body.contentLength() > TileStore.MAX_TILE_BYTES
                    ) {
                        TileDownloadResult.PermanentHttp(response.code())
                    } else {
                        val bytes =
                            readBoundedBody(body)
                                ?: return TileDownloadResult.PermanentHttp(response.code())
                        writeTile(surveyId, candidateId, xyz, bytes)
                    }
                }

                response.code() == HttpURLConnection.HTTP_NO_CONTENT ->
                    TileDownloadResult.Success(0)

                response.code() == HttpURLConnection.HTTP_UNAUTHORIZED ->
                    TileDownloadResult.AuthExpired

                response.code() in 500..599 ->
                    TileDownloadResult.TransientHttp(response.code())

                else -> TileDownloadResult.PermanentHttp(response.code())
            }
        }

        private fun readBoundedBody(body: ResponseBody): ByteArray? {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            body.byteStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    if (output.size() + read > TileStore.MAX_TILE_BYTES) {
                        return null
                    }
                    output.write(buffer, 0, read)
                }
            }
            return output.toByteArray()
        }

        private fun writeTile(
            surveyId: Long,
            candidateId: Long,
            xyz: TileCoord,
            bytes: ByteArray,
        ): TileDownloadResult =
            try {
                tileStore.write(surveyId, candidateId, xyz.z, xyz.x, xyz.y, bytes)
                TileDownloadResult.Success(1)
            } catch (e: IOException) {
                TileDownloadResult.StorageError(e.localizedMessage ?: "Tile storage error")
            } catch (e: IllegalArgumentException) {
                TileDownloadResult.StorageError(e.localizedMessage ?: "Tile storage error")
            } catch (e: IllegalStateException) {
                TileDownloadResult.StorageError(e.localizedMessage ?: "Tile storage error")
            }

        companion object {
            private const val MAX_RETRIES = 3
            private const val BACKOFF_MS = 500L
        }
    }
