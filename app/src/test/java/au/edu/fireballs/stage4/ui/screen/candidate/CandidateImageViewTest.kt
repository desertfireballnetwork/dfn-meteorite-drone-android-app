package au.edu.fireballs.stage4.ui.screen.candidate

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.advanceEventTime
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import au.edu.fireballs.stage4.domain.model.BoundingBox
import au.edu.fireballs.stage4.domain.model.ImageDims
import au.edu.fireballs.stage4.domain.model.Stage4Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CandidateImageViewTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun createCandidate(
        inferenceResultId: Long = 42L,
        boxX: Int = 1500,
        boxY: Int = 1500,
        boxW: Int = 100,
        boxH: Int = 100,
        imageW: Int = 3000,
        imageH: Int = 3000,
    ): Stage4Candidate =
        Stage4Candidate(
            inferenceResultId = inferenceResultId,
            imageId = 7L,
            imageFilename = "drone_image_001.jpg",
            imageDims = ImageDims(w = imageW, h = imageH),
            geoCentroid = null,
            geoArea = null,
            box = BoundingBox(x = boxX, y = boxY, w = boxW, h = boxH),
            confidence = 0.95,
            sizeM = null,
        )

    private fun performCustomAction(label: String) {
        val node = composeRule.onNodeWithTag("candidate-image-view").fetchSemanticsNode()
        val actions = node.config.get(SemanticsActions.CustomActions)
        actions.first { it.label == label }.action()
        composeRule.waitForIdle()
    }

    @Test
    fun candidateImageView_rendersAllExpectedNodes() {
        val candidate = createCandidate()
        composeRule.setContent {
            CandidateImageView(
                candidate = candidate,
                imageModel = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("candidate-image-view").assertExists()
        composeRule.onNodeWithTag("candidate-async-image").assertExists()
        composeRule.onNodeWithTag("candidate-box-canvas").assertExists()
    }

    @Test
    fun candidateImageView_doubleTapGestureCanBePerformed() {
        val candidate = createCandidate()
        composeRule.setContent {
            CandidateImageView(
                candidate = candidate,
                imageModel = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        composeRule.waitForIdle()

        val imageView = composeRule.onNodeWithTag("candidate-image-view")
        imageView.performTouchInput {
            click()
            advanceEventTime(50)
            click()
        }
        composeRule.waitForIdle()
        imageView.assertExists()
    }

    @Test
    fun candidateImageView_exposesAccessibilitySemantics() {
        val candidate = createCandidate()
        composeRule.setContent {
            CandidateImageView(
                candidate = candidate,
                imageModel = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        composeRule.waitForIdle()

        val imageView = composeRule.onNodeWithTag("candidate-image-view")
        imageView.assertContentDescriptionEquals("Candidate 42")
        imageView.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Zoom 100%"),
        )
    }

    @Test
    fun candidateImageView_customZoomInActionChangesZoomState() {
        val candidate = createCandidate()
        composeRule.setContent {
            CandidateImageView(
                candidate = candidate,
                imageModel = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        composeRule.waitForIdle()

        performCustomAction("Zoom in")

        val imageView = composeRule.onNodeWithTag("candidate-image-view")
        imageView.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Zoom 150%"),
        )
    }

    @Test
    fun candidateImageView_customResetZoomActionReturnsToIdentity() {
        val candidate = createCandidate()
        composeRule.setContent {
            CandidateImageView(
                candidate = candidate,
                imageModel = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        composeRule.waitForIdle()

        performCustomAction("Zoom in")

        val imageView = composeRule.onNodeWithTag("candidate-image-view")
        imageView.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Zoom 150%"),
        )

        performCustomAction("Reset zoom")

        imageView.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Zoom 100%"),
        )
    }

    @Test
    fun candidateImageView_errorState_showsRetryAndInvokesCallback() {
        val candidate = createCandidate()
        var retried = false
        composeRule.setContent {
            CandidateImageView(
                candidate = candidate,
                imageModel = File("/nonexistent/crop.jpg"),
                onRetry = { retried = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodesWithTag("candidate-image-retry")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeRule.onNodeWithTag("candidate-image-retry").performClick()
        composeRule.waitForIdle()
        assertTrue(retried)
    }

    @Test
    fun calculateDisplayedBox_atIdentityScale_centersBoxInFittedArea() {
        val candidate = createCandidate(boxX = 1500, boxY = 1500, boxW = 100, boxH = 100)
        val box =
            calculateDisplayedBox(
                candidate = candidate,
                containerSize = Size(1000f, 1000f),
                scale = 1.0f,
                panOffset = Offset.Zero,
            )

        assertEquals(475f, box.x, 0.001f)
        assertEquals(475f, box.y, 0.001f)
        assertEquals(50f, box.width, 0.001f)
        assertEquals(50f, box.height, 0.001f)
    }

    @Test
    fun calculateDisplayedBox_withZoomScale_scalesAroundCenter() {
        val candidate = createCandidate(boxX = 1500, boxY = 1500, boxW = 100, boxH = 100)
        val box =
            calculateDisplayedBox(
                candidate = candidate,
                containerSize = Size(1000f, 1000f),
                scale = 2.0f,
                panOffset = Offset.Zero,
            )

        assertEquals(450f, box.x, 0.001f)
        assertEquals(450f, box.y, 0.001f)
        assertEquals(100f, box.width, 0.001f)
        assertEquals(100f, box.height, 0.001f)
    }

    @Test
    fun calculateDisplayedBox_withPanOffset_translatesBoxPosition() {
        val candidate = createCandidate(boxX = 1500, boxY = 1500, boxW = 100, boxH = 100)
        val box =
            calculateDisplayedBox(
                candidate = candidate,
                containerSize = Size(1000f, 1000f),
                scale = 2.0f,
                panOffset = Offset(30f, -20f),
            )

        assertEquals(480f, box.x, 0.001f)
        assertEquals(430f, box.y, 0.001f)
        assertEquals(100f, box.width, 0.001f)
        assertEquals(100f, box.height, 0.001f)
    }

    @Test
    fun calculateDisplayedBox_landscapeAspectContainer_fitsWithinHeight() {
        val candidate = createCandidate(boxX = 1500, boxY = 1500, boxW = 100, boxH = 100)
        val box =
            calculateDisplayedBox(
                candidate = candidate,
                containerSize = Size(1200f, 800f),
                scale = 1.0f,
                panOffset = Offset.Zero,
            )

        assertEquals(580f, box.x, 0.001f)
        assertEquals(380f, box.y, 0.001f)
        assertEquals(40f, box.width, 0.001f)
        assertEquals(40f, box.height, 0.001f)
    }

    @Test
    fun calculateDisplayedBox_portraitAspectContainer_fitsWithinWidth() {
        val candidate = createCandidate(boxX = 1500, boxY = 1500, boxW = 100, boxH = 100)
        val box =
            calculateDisplayedBox(
                candidate = candidate,
                containerSize = Size(800f, 1200f),
                scale = 1.0f,
                panOffset = Offset.Zero,
            )

        assertEquals(380f, box.x, 0.001f)
        assertEquals(580f, box.y, 0.001f)
        assertEquals(40f, box.width, 0.001f)
        assertEquals(40f, box.height, 0.001f)
    }

    @Test
    fun calculateDisplayedBox_zeroContainerSize_returnsZeroRect() {
        val candidate = createCandidate()
        val box =
            calculateDisplayedBox(
                candidate = candidate,
                containerSize = Size.Zero,
            )

        assertEquals(0f, box.x, 0.001f)
        assertEquals(0f, box.y, 0.001f)
        assertEquals(0f, box.width, 0.001f)
        assertEquals(0f, box.height, 0.001f)
    }

    @Test
    fun calculateDisplayedBox_clampedCandidateNearTopLeftEdge() {
        val candidate =
            createCandidate(
                boxX = 100,
                boxY = 100,
                boxW = 80,
                boxH = 60,
                imageW = 3000,
                imageH = 3000,
            )
        val box =
            calculateDisplayedBox(
                candidate = candidate,
                containerSize = Size(1000f, 1000f),
                scale = 1.0f,
                panOffset = Offset.Zero,
            )

        assertEquals(30f, box.x, 0.001f)
        assertEquals(35f, box.y, 0.001f)
        assertEquals(40f, box.width, 0.001f)
        assertEquals(30f, box.height, 0.001f)
    }
}
