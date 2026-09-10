package au.edu.fireballs.stage4.ui.screen.candidate

import au.edu.fireballs.stage4.domain.model.Stage4Candidate

data class CropBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val width: Int,
    val height: Int,
)

data class BoxRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

fun calculateCropPosition(
    candidate: Stage4Candidate,
    cropWidth: Int = 2000,
    cropHeight: Int = 2000,
): CropBounds {
    val (left, right) =
        if (candidate.imageDims.w > 0) {
            clampDimension(candidate.box.x, cropWidth, candidate.imageDims.w)
        } else {
            val l = candidate.box.x - cropWidth / 2
            l to (l + cropWidth)
        }

    val (top, bottom) =
        if (candidate.imageDims.h > 0) {
            clampDimension(candidate.box.y, cropHeight, candidate.imageDims.h)
        } else {
            val t = candidate.box.y - cropHeight / 2
            t to (t + cropHeight)
        }

    return CropBounds(
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        width = right - left,
        height = bottom - top,
    )
}

fun calculateBoxInCrop(
    candidate: Stage4Candidate,
    cropBounds: CropBounds,
): BoxRect {
    val featureX = candidate.box.x - candidate.box.w / 2f
    val featureY = candidate.box.y - candidate.box.h / 2f
    return BoxRect(
        x = featureX - cropBounds.left,
        y = featureY - cropBounds.top,
        width = candidate.box.w.toFloat(),
        height = candidate.box.h.toFloat(),
    )
}

private fun clampDimension(
    center: Int,
    cropSize: Int,
    imageLimit: Int,
): Pair<Int, Int> {
    if (cropSize >= imageLimit) {
        return 0 to imageLimit
    }
    val minVal = center - cropSize / 2
    val maxVal = minVal + cropSize
    return when {
        minVal < 0 -> 0 to cropSize
        maxVal > imageLimit -> (imageLimit - cropSize) to imageLimit
        else -> minVal to maxVal
    }
}
