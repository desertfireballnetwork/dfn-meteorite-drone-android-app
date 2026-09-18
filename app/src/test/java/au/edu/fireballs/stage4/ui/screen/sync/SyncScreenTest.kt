package au.edu.fireballs.stage4.ui.screen.sync

import android.app.Application
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import au.edu.fireballs.stage4.data.local.LocalDecisionEntity
import au.edu.fireballs.stage4.data.local.PendingPhotoUploadEntity
import au.edu.fireballs.stage4.data.repository.SyncPhase
import au.edu.fireballs.stage4.data.repository.SyncProgress
import au.edu.fireballs.stage4.ui.theme.Stage4Theme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SyncScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun emptySummary() =
        SyncSummary(
            pendingDecisions = 0,
            pendingPhotos = 0,
            failedDecisions = emptyList(),
            failedPhotos = emptyList(),
        )

    private fun failedSummary() =
        emptySummary().copy(
            pendingDecisions = 1,
            failedDecisions = listOf(decision(id = 42L, reason = "claim_required")),
        )

    private fun decision(
        id: Long = 1L,
        reason: String? = null,
    ) = LocalDecisionEntity(
        inferenceResultId = id,
        surveyId = 7L,
        verdict = true,
        detectionTagId = null,
        capturedAt = "2026-09-02T12:00:00Z",
        evidencePhotoRowId = null,
        synced = false,
        syncFailedReason = reason,
    )

    private fun photo(
        rowId: Long = 1L,
        reason: String? = null,
    ) = PendingPhotoUploadEntity(
        rowId = rowId,
        surveyId = 7L,
        inferenceResultId = 2L,
        localFilePath = "/tmp/photo_001.jpg",
        capturedAt = "2026-09-02T12:00:00Z",
        uploaded = false,
        uploadFailedReason = reason,
    )

    private fun setContent(
        uiState: SyncUiState,
        onSyncNow: () -> Unit = {},
        onDeleteDecision: (Long) -> Unit = {},
        onDeletePhoto: (Long) -> Unit = {},
        onSignIn: () -> Unit = {},
    ) {
        composeRule.setContent {
            Stage4Theme {
                SyncScreen(
                    uiState = uiState,
                    onSyncNow = onSyncNow,
                    onDeleteDecision = onDeleteDecision,
                    onDeletePhoto = onDeletePhoto,
                    onSignIn = onSignIn,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun assertLiveRegion() {
        composeRule
            .onNodeWithTag("sync-status")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
    }

    @Test
    fun idleState_showsUpToDateAndSyncNow() {
        setContent(SyncUiState.Idle)
        composeRule.onNodeWithText("Everything is up to date").assertIsDisplayed()
        composeRule.onNodeWithText("Sync now").assertIsDisplayed()
    }

    @Test
    fun pendingState_showsCounts() {
        val summary = emptySummary().copy(pendingDecisions = 3, pendingPhotos = 2)
        setContent(SyncUiState.Pending(summary))
        composeRule.onNodeWithText("3").assertIsDisplayed()
        composeRule.onNodeWithText("2").assertIsDisplayed()
        composeRule.onNodeWithText("Decisions").assertIsDisplayed()
        composeRule.onNodeWithText("Photos").assertIsDisplayed()
    }

    @Test
    fun runningPhotoPhase_showsExactCopy() {
        setContent(
            SyncUiState.Running(
                summary = emptySummary(),
                progress = SyncProgress(SyncPhase.Photo, done = 3, total = 5),
            ),
        )
        composeRule.onNodeWithText("Uploading photo 3 of 5").assertIsDisplayed()
    }

    @Test
    fun runningDecisionPhase_showsExactCopy() {
        setContent(
            SyncUiState.Running(
                summary = emptySummary(),
                progress = SyncProgress(SyncPhase.Decision, done = 2, total = 4),
            ),
        )
        composeRule.onNodeWithText("Synchronising decision 2 of 4").assertIsDisplayed()
    }

    @Test
    fun runningUnknownPhase_showsUnknownCopyEvenWithNumbers() {
        setContent(
            SyncUiState.Running(
                summary = emptySummary(),
                progress = SyncProgress(SyncPhase.Unknown, done = 2, total = 4),
            ),
        )
        composeRule.onNodeWithText("Synchronising").assertIsDisplayed()
        assertLiveRegion()
    }

    @Test
    fun runningNullProgress_showsUnknownCopy() {
        setContent(SyncUiState.Running(summary = emptySummary(), progress = null))
        composeRule.onNodeWithText("Synchronising").assertIsDisplayed()
    }

    @Test
    fun runningPositiveTotal_isDeterminate() {
        setContent(
            SyncUiState.Running(
                summary = emptySummary(),
                progress = SyncProgress(SyncPhase.Photo, done = 1, total = 2),
            ),
        )
        composeRule
            .onNodeWithTag("sync-progress")
            .assertRangeInfoEquals(ProgressBarRangeInfo(0.5f, 0f..1f, 0))
    }

    @Test
    fun runningNullTotal_isIndeterminate() {
        setContent(
            SyncUiState.Running(
                summary = emptySummary(),
                progress = SyncProgress(SyncPhase.Photo, done = 1, total = null),
            ),
        )
        composeRule
            .onNodeWithTag("sync-progress")
            .assertRangeInfoEquals(ProgressBarRangeInfo.Indeterminate)
    }

    @Test
    fun runningZeroTotal_isIndeterminate() {
        setContent(
            SyncUiState.Running(
                summary = emptySummary(),
                progress = SyncProgress(SyncPhase.Photo, done = 0, total = 0),
            ),
        )
        composeRule
            .onNodeWithTag("sync-progress")
            .assertRangeInfoEquals(ProgressBarRangeInfo.Indeterminate)
    }

    @Test
    fun runningInvalidTotal_isIndeterminate() {
        setContent(
            SyncUiState.Running(
                summary = emptySummary(),
                progress = SyncProgress(SyncPhase.Decision, done = 1, total = -3),
            ),
        )
        composeRule
            .onNodeWithTag("sync-progress")
            .assertRangeInfoEquals(ProgressBarRangeInfo.Indeterminate)
    }

    @Test
    fun runningState_showsSyncingActionDisabled() {
        var synced = 0
        setContent(
            uiState =
                SyncUiState.Running(
                    summary = failedSummary(),
                    progress = SyncProgress(SyncPhase.Photo, done = 1, total = 2),
                ),
            onSyncNow = { synced++ },
        )
        composeRule.onNodeWithText("Syncing…").assertIsDisplayed()
        composeRule.onNodeWithText("Syncing…").assertIsNotEnabled()
        composeRule.onNodeWithText("Syncing…").performClick()
        assertEquals(0, synced)
        composeRule.onNodeWithTag("delete-Decision-42").assertIsNotEnabled()
        assertLiveRegion()
    }

    @Test
    fun resumingState_showsSyncingActionDisabled() {
        var synced = 0
        setContent(
            uiState = SyncUiState.Resuming(failedSummary()),
            onSyncNow = { synced++ },
        )
        composeRule.onNodeWithText("Syncing…").assertIsDisplayed()
        composeRule.onNodeWithText("Syncing…").assertIsNotEnabled()
        composeRule.onNodeWithText("Syncing…").performClick()
        assertEquals(0, synced)
        composeRule.onNodeWithTag("delete-Decision-42").assertIsNotEnabled()
        assertLiveRegion()
    }

    @Test
    fun resumingState_isIndeterminate() {
        setContent(SyncUiState.Resuming(emptySummary()))
        composeRule
            .onNodeWithTag("sync-progress")
            .assertRangeInfoEquals(ProgressBarRangeInfo.Indeterminate)
    }

    @Test
    fun waitingForNetworkState_showsSyncingActionDisabled() {
        var synced = 0
        setContent(
            uiState = SyncUiState.WaitingForNetwork(failedSummary()),
            onSyncNow = { synced++ },
        )
        composeRule.onNodeWithText("Syncing…").assertIsDisplayed()
        composeRule.onNodeWithText("Syncing…").assertIsNotEnabled()
        composeRule.onNodeWithText("Syncing…").performClick()
        assertEquals(0, synced)
        composeRule.onNodeWithText("Waiting for network").assertIsDisplayed()
        composeRule.onNodeWithTag("delete-Decision-42").assertIsNotEnabled()
        composeRule
            .onNodeWithTag("sync-progress")
            .assertRangeInfoEquals(ProgressBarRangeInfo.Indeterminate)
        assertLiveRegion()
    }

    @Test
    fun failedState_showsFailedCount() {
        val summary =
            emptySummary().copy(
                pendingDecisions = 1,
                failedDecisions =
                    listOf(
                        decision(id = 1L, reason = "claim_required"),
                        decision(id = 2L, reason = "file_missing"),
                    ),
                failedPhotos = listOf(photo(rowId = 9L, reason = "cross_campaign")),
            )
        setContent(SyncUiState.Failed(summary))
        composeRule.onNodeWithText("3 failed items").assertIsDisplayed()
    }

    @Test
    fun failedState_showsReasonChips() {
        val summary =
            emptySummary().copy(
                pendingDecisions = 1,
                failedDecisions =
                    listOf(
                        decision(id = 1L, reason = "claim_required"),
                        decision(id = 2L, reason = "file_missing"),
                        decision(id = 3L, reason = "Server returned code: 500"),
                    ),
                failedPhotos = listOf(photo(rowId = 9L, reason = "cross_campaign")),
            )
        setContent(SyncUiState.Failed(summary))
        composeRule.onNodeWithText("Claim required").assertExists()
        composeRule.onNodeWithText("Photo file missing").assertExists()
        composeRule.onNodeWithText("Server error").assertExists()
        composeRule.onNodeWithText("Cross-campaign").assertExists()
    }

    @Test
    fun failedState_deleteRequiresConfirmation() {
        var deletedId: Long? = null
        setContent(
            uiState = SyncUiState.Failed(failedSummary()),
            onDeleteDecision = { deletedId = it },
        )

        composeRule.onNodeWithText("Decision 42").assertIsDisplayed()
        composeRule.onNodeWithTag("delete-Decision-42").performClick()
        composeRule.onNodeWithText("Delete Decision 42?").assertIsDisplayed()
        composeRule.onNodeWithTag("delete-cancel").performClick()
        assertEquals(null, deletedId)

        composeRule.onNodeWithTag("delete-Decision-42").performClick()
        composeRule.onNodeWithTag("delete-confirm").performClick()
        assertEquals(42L, deletedId)
    }

    @Test
    fun failedState_deletePhotoCallsPhotoCallback() {
        var deletedRowId: Long? = null
        val summary =
            emptySummary().copy(
                pendingPhotos = 1,
                failedPhotos = listOf(photo(rowId = 7L, reason = "file_missing")),
            )
        setContent(
            uiState = SyncUiState.Failed(summary),
            onDeletePhoto = { deletedRowId = it },
        )

        composeRule.onNodeWithText("Photo 2: photo_001.jpg").assertIsDisplayed()
        composeRule.onNodeWithTag("delete-Photo-7").performClick()
        composeRule.onNodeWithTag("delete-confirm").performClick()
        assertEquals(7L, deletedRowId)
    }

    @Test
    fun pendingState_deleteDecisionRequiresConfirmation() {
        var deletedId: Long? = null
        setContent(
            uiState = SyncUiState.Pending(failedSummary()),
            onDeleteDecision = { deletedId = it },
        )

        composeRule.onNodeWithText("Decision 42").assertIsDisplayed()
        composeRule.onNodeWithTag("delete-Decision-42").performClick()
        composeRule.onNodeWithTag("delete-confirm").performClick()
        assertEquals(42L, deletedId)
    }

    @Test
    fun sessionExpiredState_showsSignIn() {
        var signedIn = false
        setContent(
            uiState = SyncUiState.SessionExpired,
            onSignIn = { signedIn = true },
        )
        composeRule.onNodeWithText("Session expired — sign in to resume").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in").performClick()
        assertEquals(true, signedIn)
    }

    @Test
    fun completeState_showsComplete() {
        setContent(SyncUiState.Complete)
        composeRule.onNodeWithText("Sync complete").assertIsDisplayed()
    }

    @Test
    fun syncNowButton_invokesCallback() {
        var synced = 0
        setContent(
            uiState = SyncUiState.Pending(emptySummary()),
            onSyncNow = { synced++ },
        )
        composeRule.onNodeWithText("Sync now").performClick()
        assertEquals(1, synced)
    }
}
