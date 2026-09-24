package au.edu.fireballs.stage4.data.tiles

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import java.io.ByteArrayOutputStream

interface LowZoomCompositor {
    fun compose(
        surveyId: Long,
        candidateId: Long,
        sourceTiles: List<TileCoord>,
        parent: TileCoord,
    ): ByteArray

    fun parentTiles(
        sourceTiles: List<TileCoord>,
        zoom: Int,
    ): List<TileCoord>
}

data class ChildPlacement(
    val child: TileCoord,
    val left: Int,
    val top: Int,
    val size: Int,
)

fun childScaleFor(
    sourceZoom: Int,
    targetZoom: Int,
): Int = 1 shl (sourceZoom - targetZoom)

fun parentOf(
    child: TileCoord,
    targetZoom: Int,
): TileCoord {
    val scale = childScaleFor(child.z, targetZoom)
    return TileCoord(targetZoom, child.x / scale, child.y / scale)
}

fun placementsFor(
    children: List<TileCoord>,
    parent: TileCoord,
    sourceZoom: Int,
    canvasPixels: Int,
): List<ChildPlacement> {
    val childScale = childScaleFor(sourceZoom, parent.z)
    val xStart = parent.x * childScale
    val yStart = parent.y * childScale
    val childSize = canvasPixels / childScale
    return children
        .filter {
            it.x in xStart until xStart + childScale &&
                it.y in yStart until yStart + childScale
        }.map { child ->
            ChildPlacement(
                child = child,
                left = (child.x - xStart) * childSize,
                top = (child.y - yStart) * childSize,
                size = childSize,
            )
        }
}

class LowZoomTileCompositor(
    private val tileStore: TileStore,
) : LowZoomCompositor {
    override fun compose(
        surveyId: Long,
        candidateId: Long,
        sourceTiles: List<TileCoord>,
        parent: TileCoord,
    ): ByteArray {
        require(parent.z in MIN_ZOOM until SOURCE_ZOOM)
        val placements = placementsFor(sourceTiles, parent, SOURCE_ZOOM, TILE_SIZE)
        if (placements.isEmpty()) {
            return LocalFileRasterTileProvider.TRANSPARENT_PNG
        }

        val output = Bitmap.createBitmap(TILE_SIZE, TILE_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        var rendered = false
        placements.forEach { placement ->
            val raw =
                tileStore
                    .read(surveyId, candidateId, placement.child)
                    ?.use { input ->
                        input.readNBytes(MAX_ENCODED_TILE_BYTES + 1)
                    }
            val bytes =
                if (raw == null || raw.size > MAX_ENCODED_TILE_BYTES) {
                    return@forEach
                } else {
                    raw
                }
            val bounds =
                BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (
                bounds.outWidth !in 1..MAX_SOURCE_TILE_PIXELS ||
                bounds.outHeight !in 1..MAX_SOURCE_TILE_PIXELS
            ) {
                return@forEach
            }
            val bitmap =
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return@forEach
            val destination =
                Rect(
                    placement.left,
                    placement.top,
                    placement.left + placement.size,
                    placement.top + placement.size,
                )
            canvas.drawBitmap(bitmap, null, destination, paint)
            bitmap.recycle()
            rendered = true
        }
        if (!rendered) {
            output.recycle()
            return LocalFileRasterTileProvider.TRANSPARENT_PNG
        }

        val stream = ByteArrayOutputStream()
        output.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, stream)
        output.recycle()
        return stream.toByteArray()
    }

    override fun parentTiles(
        sourceTiles: List<TileCoord>,
        zoom: Int,
    ): List<TileCoord> {
        require(zoom in MIN_ZOOM until SOURCE_ZOOM)
        return sourceTiles
            .map { parentOf(it, zoom) }
            .distinct()
    }

    companion object {
        const val MIN_ZOOM = 11
        const val SOURCE_ZOOM = 20
        private const val TILE_SIZE = 2048
        private const val PNG_QUALITY = 100
        private const val MAX_ENCODED_TILE_BYTES = 16 * 1024 * 1024
        private const val MAX_SOURCE_TILE_PIXELS = 4096
    }
}
