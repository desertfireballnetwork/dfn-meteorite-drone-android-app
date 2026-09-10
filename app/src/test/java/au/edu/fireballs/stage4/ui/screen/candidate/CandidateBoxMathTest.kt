package au.edu.fireballs.stage4.ui.screen.candidate

import au.edu.fireballs.stage4.domain.model.BoundingBox
import au.edu.fireballs.stage4.domain.model.ImageDims
import au.edu.fireballs.stage4.domain.model.Stage4Candidate
import org.junit.Assert.assertEquals
import org.junit.Test

class CandidateBoxMathTest {
    private fun createCandidate(
        boxX: Int = 1500,
        boxY: Int = 1500,
        boxW: Int = 100,
        boxH: Int = 100,
        imageW: Int = 3000,
        imageH: Int = 3000,
    ): Stage4Candidate =
        Stage4Candidate(
            inferenceResultId = 42L,
            imageId = 7L,
            imageFilename = "drone_image_001.jpg",
            imageDims = ImageDims(w = imageW, h = imageH),
            geoCentroid = null,
            geoArea = null,
            box = BoundingBox(x = boxX, y = boxY, w = boxW, h = boxH),
            confidence = 0.95,
            sizeM = null,
        )

    @Test
    fun calculateCropPosition_normalCase_centersCropAroundFeature() {
        val candidate = createCandidate(boxX = 1500, boxY = 1500)
        val crop = calculateCropPosition(candidate, cropWidth = 2000, cropHeight = 2000)

        assertEquals(500, crop.left)
        assertEquals(500, crop.top)
        assertEquals(2500, crop.right)
        assertEquals(2500, crop.bottom)
        assertEquals(2000, crop.width)
        assertEquals(2000, crop.height)
    }

    @Test
    fun calculateCropPosition_topLeftClamping_clampsToZero() {
        val candidate = createCandidate(boxX = 100, boxY = 100)
        val crop = calculateCropPosition(candidate, cropWidth = 2000, cropHeight = 2000)

        assertEquals(0, crop.left)
        assertEquals(0, crop.top)
        assertEquals(2000, crop.right)
        assertEquals(2000, crop.bottom)
        assertEquals(2000, crop.width)
        assertEquals(2000, crop.height)
    }

    @Test
    fun calculateCropPosition_bottomRightClamping_clampsToImageDimensions() {
        val candidate = createCandidate(boxX = 2900, boxY = 2900, imageW = 3000, imageH = 3000)
        val crop = calculateCropPosition(candidate, cropWidth = 2000, cropHeight = 2000)

        assertEquals(1000, crop.left)
        assertEquals(1000, crop.top)
        assertEquals(3000, crop.right)
        assertEquals(3000, crop.bottom)
        assertEquals(2000, crop.width)
        assertEquals(2000, crop.height)
    }

    @Test
    fun calculateCropPosition_zeroImageDimensions_fallsBackToUnconstrainedCentering() {
        val candidate = createCandidate(boxX = 1500, boxY = 1500, imageW = 0, imageH = 0)
        val crop = calculateCropPosition(candidate, cropWidth = 2000, cropHeight = 2000)

        assertEquals(500, crop.left)
        assertEquals(500, crop.top)
        assertEquals(2500, crop.right)
        assertEquals(2500, crop.bottom)
        assertEquals(2000, crop.width)
        assertEquals(2000, crop.height)
    }

    @Test
    fun calculateCropPosition_negativeImageDimensions_fallsBackToUnconstrainedCentering() {
        val candidate = createCandidate(boxX = 500, boxY = 600, imageW = -1, imageH = -1)
        val crop = calculateCropPosition(candidate, cropWidth = 1000, cropHeight = 1000)

        assertEquals(0, crop.left)
        assertEquals(100, crop.top)
        assertEquals(1000, crop.right)
        assertEquals(1100, crop.bottom)
        assertEquals(1000, crop.width)
        assertEquals(1000, crop.height)
    }

    @Test
    fun calculateCropPosition_imageSmallerThanCrop_clampsToImageBounds() {
        val candidate = createCandidate(boxX = 500, boxY = 500, imageW = 1200, imageH = 1000)
        val crop = calculateCropPosition(candidate, cropWidth = 2000, cropHeight = 2000)

        assertEquals(0, crop.left)
        assertEquals(0, crop.top)
        assertEquals(1200, crop.right)
        assertEquals(1000, crop.bottom)
        assertEquals(1200, crop.width)
        assertEquals(1000, crop.height)
    }

    @Test
    fun calculateCropPosition_customCropDimensions_computesExpectedBounds() {
        val candidate = createCandidate(boxX = 1500, boxY = 1500, imageW = 3000, imageH = 3000)
        val crop = calculateCropPosition(candidate, cropWidth = 1000, cropHeight = 800)

        assertEquals(1000, crop.left)
        assertEquals(1100, crop.top)
        assertEquals(2000, crop.right)
        assertEquals(1900, crop.bottom)
        assertEquals(1000, crop.width)
        assertEquals(800, crop.height)
    }

    @Test
    fun calculateBoxInCrop_normalCase_convertsCenterToCropRelativeCoordinates() {
        val candidate = createCandidate(boxX = 1500, boxY = 1500, boxW = 100, boxH = 100)
        val crop =
            CropBounds(
                left = 500,
                top = 500,
                right = 2500,
                bottom = 2500,
                width = 2000,
                height = 2000,
            )
        val boxRect = calculateBoxInCrop(candidate, crop)

        assertEquals(950f, boxRect.x, 0.001f)
        assertEquals(950f, boxRect.y, 0.001f)
        assertEquals(100f, boxRect.width, 0.001f)
        assertEquals(100f, boxRect.height, 0.001f)
    }

    @Test
    fun calculateBoxInCrop_clampedCrop_convertsCorrectly() {
        val candidate = createCandidate(boxX = 100, boxY = 100, boxW = 80, boxH = 60)
        val crop =
            CropBounds(
                left = 0,
                top = 0,
                right = 2000,
                bottom = 2000,
                width = 2000,
                height = 2000,
            )
        val boxRect = calculateBoxInCrop(candidate, crop)

        assertEquals(60f, boxRect.x, 0.001f)
        assertEquals(70f, boxRect.y, 0.001f)
        assertEquals(80f, boxRect.width, 0.001f)
        assertEquals(60f, boxRect.height, 0.001f)
    }

    @Test
    fun calculateBoxInCrop_oddDimensions_preservesSubpixelPrecision() {
        val candidate = createCandidate(boxX = 1500, boxY = 1500, boxW = 45, boxH = 33)
        val crop =
            CropBounds(
                left = 500,
                top = 500,
                right = 2500,
                bottom = 2500,
                width = 2000,
                height = 2000,
            )
        val boxRect = calculateBoxInCrop(candidate, crop)

        assertEquals(977.5f, boxRect.x, 0.001f)
        assertEquals(983.5f, boxRect.y, 0.001f)
        assertEquals(45f, boxRect.width, 0.001f)
        assertEquals(33f, boxRect.height, 0.001f)
    }
}
