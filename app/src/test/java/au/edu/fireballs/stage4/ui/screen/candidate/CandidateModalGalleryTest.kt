package au.edu.fireballs.stage4.ui.screen.candidate

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import au.edu.fireballs.stage4.data.local.PendingPhotoUploadEntity
import au.edu.fireballs.stage4.data.remote.dto.EvidencePhotoDto
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CandidateModalGalleryTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun localPhoto(
        rowId: Long,
        serverPhotoId: Long? = null,
    ): PendingPhotoUploadEntity =
        PendingPhotoUploadEntity(
            rowId = rowId,
            surveyId = 1L,
            inferenceResultId = 42L,
            localFilePath = "/tmp/photo_$rowId.jpg",
            capturedAt = "2026-01-01T00:00:00Z",
            serverPhotoId = serverPhotoId,
        )

    private fun serverPhoto(
        id: Long,
        capturedAt: String? = null,
    ): EvidencePhotoDto =
        EvidencePhotoDto(
            id = id,
            capturedAt = capturedAt,
            created = "2026-01-01T00:00:00Z",
            userId = 1L,
            username = "searcher-$id",
        )

    private fun setGallery(
        photos: List<PendingPhotoUploadEntity> = emptyList(),
        serverGalleryState: EvidenceGalleryUiState = EvidenceGalleryUiState.Loading,
        onRetryServerGallery: () -> Unit = {},
        onAuthExpired: () -> Unit = {},
        serverUrl: String = "https://example.com",
    ) {
        composeRule.setContent {
            PhotoGallery(
                photos = photos,
                onPhotoCaptured = {},
                onPhotoPicked = {},
                serverGalleryState = serverGalleryState,
                onRetryServerGallery = onRetryServerGallery,
                serverUrl = serverUrl,
                onAuthExpired = onAuthExpired,
            )
        }
    }

    @Test
    fun localAndServerSectionsRenderTogether() {
        setGallery(
            photos = listOf(localPhoto(rowId = 1L)),
            serverGalleryState =
                EvidenceGalleryUiState.Content(
                    listOf(serverPhoto(id = 10L, capturedAt = "2026-01-02T00:00:00Z")),
                ),
        )

        composeRule.onNodeWithTag("photo-gallery").assertExists()
        composeRule.onNodeWithTag("server-evidence-gallery").assertExists()
        composeRule.onNodeWithText("Server evidence").assertExists()
    }

    @Test
    fun uploadedLocalRowIsNotShownLocally() {
        setGallery(photos = listOf(localPhoto(rowId = 1L, serverPhotoId = 99L)))

        composeRule.onNodeWithTag("photo-gallery-empty").assertExists()
        composeRule.onNodeWithTag("photo-gallery").assertDoesNotExist()
    }

    @Test
    fun nonUploadedLocalRowIsShownLocally() {
        setGallery(photos = listOf(localPhoto(rowId = 1L)))

        composeRule.onNodeWithTag("photo-gallery").assertExists()
        composeRule.onNodeWithTag("photo-gallery-empty").assertDoesNotExist()
    }

    @Test
    fun emptyServerGalleryRendersCleanly() {
        setGallery(serverGalleryState = EvidenceGalleryUiState.Empty)

        composeRule.onNodeWithTag("server-evidence-empty").assertExists()
        composeRule.onNodeWithText("No server evidence").assertExists()
        composeRule.onNodeWithTag("server-evidence-gallery").assertDoesNotExist()
    }

    @Test
    fun loadingStateShowsIndicator() {
        setGallery(serverGalleryState = EvidenceGalleryUiState.Loading)

        composeRule.onNodeWithTag("server-evidence-loading").assertExists()
        composeRule.onNodeWithText("Loading server evidence").assertExists()
    }

    @Test
    fun offlineShowsLocalOnlyNoticeAndLocalPhotosUsable() {
        setGallery(
            photos = listOf(localPhoto(rowId = 1L)),
            serverGalleryState = EvidenceGalleryUiState.Offline,
        )

        composeRule.onNodeWithTag("server-evidence-offline").assertExists()
        composeRule.onNodeWithText("Offline - showing local photos only").assertExists()
        composeRule.onNodeWithTag("photo-gallery").assertExists()
    }

    @Test
    fun errorShowsNoticeAndRetryTriggersCallback() {
        var retried = false
        setGallery(
            serverGalleryState = EvidenceGalleryUiState.Error("boom"),
            onRetryServerGallery = { retried = true },
        )

        composeRule.onNodeWithTag("server-evidence-error").assertExists()
        composeRule.onNodeWithText("boom").assertExists()
        composeRule.onNodeWithTag("server-evidence-retry").performClick()
        composeRule.waitForIdle()
        assertTrue(retried)
    }

    @Test
    fun ownerLabelAndUnknownTimeRender() {
        setGallery(
            serverGalleryState =
                EvidenceGalleryUiState.Content(
                    listOf(serverPhoto(id = 10L, capturedAt = null)),
                ),
        )

        composeRule.onNodeWithText("searcher-10").assertExists()
        composeRule.onNodeWithText("unknown time").assertExists()
    }

    @Test
    fun capturedAtLabelRendersWhenPresent() {
        setGallery(
            serverGalleryState =
                EvidenceGalleryUiState.Content(
                    listOf(serverPhoto(id = 10L, capturedAt = "2026-01-02T00:00:00Z")),
                ),
        )

        composeRule.onNodeWithText("2026-01-02T00:00:00Z").assertExists()
    }

    @Test
    fun tappingPhotoOpensFullImage() {
        setGallery(
            serverGalleryState =
                EvidenceGalleryUiState.Content(
                    listOf(serverPhoto(id = 10L)),
                ),
        )

        composeRule.onNodeWithTag("server-evidence-photo-10").performClick()
        composeRule.waitForIdle()
        composeRule
            .onNodeWithTag(
                "server-evidence-full-image",
                useUnmergedTree = true,
            ).assertExists()
    }

    @Test
    fun galleryContentIsAccessible() {
        setGallery(
            serverGalleryState =
                EvidenceGalleryUiState.Content(
                    listOf(serverPhoto(id = 10L)),
                ),
        )

        composeRule.onNodeWithContentDescription("Evidence photo by searcher-10").assertExists()
    }

    @Test
    fun authExpiredSurfacesCallback() {
        var authExpired = false
        setGallery(
            serverGalleryState = EvidenceGalleryUiState.AuthExpired,
            onAuthExpired = { authExpired = true },
        )

        composeRule.waitForIdle()
        assertTrue(authExpired)
    }
}
