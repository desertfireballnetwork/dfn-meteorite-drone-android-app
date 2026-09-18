package au.edu.fireballs.stage4.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SyncStatusRepositoryTest {
    private val workA = uuid(1L)
    private val workB = uuid(2L)
    private val workC = uuid(3L)
    private val staleRun = uuid(99L)

    @Test
    fun `no work and no pending rows resolves to idle`() {
        val resolution = resolveSyncStatus(input())

        assertNull(resolution.selectedWorkId)
        assertEquals(DurableSyncStatus.Idle, resolution.status)
    }

    @Test
    fun `no work with pending rows resolves to pending`() {
        val resolution =
            resolveSyncStatus(
                input(pending = PendingSyncCounts(decisions = 2, photos = 5)),
            )

        assertNull(resolution.selectedWorkId)
        assertEquals(DurableSyncStatus.Pending(decisions = 2, photos = 5), resolution.status)
    }

    @Test
    fun `running is selected ahead of enqueued and terminal`() {
        val resolution =
            resolveSyncStatus(
                input(
                    workInfos =
                        listOf(
                            snapshot(workA, SyncWorkState.Succeeded, returnedListIndex = 0),
                            snapshot(workB, SyncWorkState.Enqueued, returnedListIndex = 1),
                            snapshot(workC, SyncWorkState.Running, returnedListIndex = 2),
                        ),
                ),
            )

        assertEquals(workC, resolution.selectedWorkId)
        assertEquals(DurableSyncStatus.Running(workC, null), resolution.status)
    }

    @Test
    fun `enqueued is selected ahead of terminal`() {
        val resolution =
            resolveSyncStatus(
                input(
                    workInfos =
                        listOf(
                            snapshot(workA, SyncWorkState.Succeeded, returnedListIndex = 0),
                            snapshot(workB, SyncWorkState.Enqueued, returnedListIndex = 1),
                        ),
                ),
            )

        assertEquals(workB, resolution.selectedWorkId)
        assertEquals(DurableSyncStatus.Resuming(workB), resolution.status)
    }

    @Test
    fun `enqueued is selected ahead of blocked`() {
        val resolution =
            resolveSyncStatus(
                input(
                    workInfos =
                        listOf(
                            snapshot(workA, SyncWorkState.Blocked, returnedListIndex = 0),
                            snapshot(workB, SyncWorkState.Enqueued, returnedListIndex = 1),
                        ),
                    networkState = ValidatedNetworkState.Offline,
                ),
            )

        assertEquals(workB, resolution.selectedWorkId)
        assertEquals(DurableSyncStatus.WaitingForNetwork(workB, 0, 0), resolution.status)
    }

    @Test
    fun `blocked does not supersede terminal work`() {
        val resolution =
            resolveSyncStatus(
                input(
                    workInfos =
                        listOf(
                            snapshot(workA, SyncWorkState.Succeeded, returnedListIndex = 0),
                            snapshot(workB, SyncWorkState.Blocked, returnedListIndex = 1),
                        ),
                    networkState = ValidatedNetworkState.Offline,
                ),
            )

        assertEquals(workA, resolution.selectedWorkId)
        assertEquals(DurableSyncStatus.Complete(workA), resolution.status)
    }

    @Test
    fun `latest running in returned list order is selected`() {
        val resolution =
            resolveSyncStatus(
                input(
                    workInfos =
                        listOf(
                            snapshot(workA, SyncWorkState.Running, returnedListIndex = 0),
                            snapshot(workB, SyncWorkState.Running, returnedListIndex = 1),
                        ),
                ),
            )

        assertEquals(workB, resolution.selectedWorkId)
    }

    @Test
    fun `terminal record with the newest generation wins`() {
        val resolution =
            resolveSyncStatus(
                input(
                    workInfos =
                        listOf(
                            snapshot(
                                workA,
                                SyncWorkState.Failed,
                                generation = 1,
                                returnedListIndex = 0,
                            ),
                            snapshot(
                                workB,
                                SyncWorkState.Failed,
                                generation = 2,
                                returnedListIndex = 1,
                            ),
                        ),
                ),
            )

        assertEquals(workB, resolution.selectedWorkId)
    }

    @Test
    fun `null generation is older than an exposed generation`() {
        val resolution =
            resolveSyncStatus(
                input(
                    workInfos =
                        listOf(
                            snapshot(
                                workA,
                                SyncWorkState.Failed,
                                generation = null,
                                returnedListIndex = 1,
                            ),
                            snapshot(
                                workB,
                                SyncWorkState.Failed,
                                generation = 0,
                                returnedListIndex = 0,
                            ),
                        ),
                ),
            )

        assertEquals(workB, resolution.selectedWorkId)
    }

    @Test
    fun `terminal run attempt count breaks generation ties`() {
        val resolution =
            resolveSyncStatus(
                input(
                    workInfos =
                        listOf(
                            snapshot(
                                workA,
                                SyncWorkState.Failed,
                                generation = 1,
                                runAttemptCount = 1,
                                returnedListIndex = 0,
                            ),
                            snapshot(
                                workB,
                                SyncWorkState.Failed,
                                generation = 1,
                                runAttemptCount = 2,
                                returnedListIndex = 1,
                            ),
                        ),
                ),
            )

        assertEquals(workB, resolution.selectedWorkId)
    }

    @Test
    fun `last terminal in returned list order wins without stable metadata`() {
        val resolution =
            resolveSyncStatus(
                input(
                    workInfos =
                        listOf(
                            snapshot(workA, SyncWorkState.Failed, returnedListIndex = 0),
                            snapshot(workB, SyncWorkState.Failed, returnedListIndex = 1),
                        ),
                ),
            )

        assertEquals(workB, resolution.selectedWorkId)
    }

    @Test
    fun `session expiry outranks running`() {
        val resolution =
            resolveSyncStatus(
                input(
                    workInfos =
                        listOf(
                            snapshot(workA, SyncWorkState.Running, sessionExpired = true),
                        ),
                ),
            )

        assertEquals(workA, resolution.selectedWorkId)
        assertEquals(DurableSyncStatus.SessionExpired(workA), resolution.status)
    }

    @Test
    fun `session expiry outranks offline enqueued`() {
        val resolution =
            resolveSyncStatus(
                input(
                    workInfos =
                        listOf(
                            snapshot(workA, SyncWorkState.Enqueued, sessionExpired = true),
                        ),
                    networkState = ValidatedNetworkState.Offline,
                ),
            )

        assertEquals(DurableSyncStatus.SessionExpired(workA), resolution.status)
    }

    @Test
    fun `session expiry outranks terminal state and pending rows`() {
        val resolution =
            resolveSyncStatus(
                input(
                    workInfos =
                        listOf(
                            snapshot(workA, SyncWorkState.Succeeded, sessionExpired = true),
                        ),
                    pending = PendingSyncCounts(decisions = 4, photos = 1),
                ),
            )

        assertEquals(DurableSyncStatus.SessionExpired(workA), resolution.status)
    }

    @Test
    fun `running resolves with matching active run progress`() {
        val progress = SyncProgress(SyncPhase.Decision, done = 3, total = 10)
        val resolution =
            resolveSyncStatus(
                input(
                    workInfos = listOf(snapshot(workA, SyncWorkState.Running)),
                    activeRun = ActiveSyncRun(workId = workA, progress = progress),
                ),
            )

        assertEquals(DurableSyncStatus.Running(workA, progress), resolution.status)
    }

    @Test
    fun `running enriches progress when run work id is null`() {
        val progress = SyncProgress(SyncPhase.Photo, done = 1, total = 4)
        val resolution =
            resolveSyncStatus(
                input(
                    workInfos = listOf(snapshot(workA, SyncWorkState.Running)),
                    activeRun = ActiveSyncRun(workId = null, progress = progress),
                ),
            )

        assertEquals(DurableSyncStatus.Running(workA, progress), resolution.status)
    }

    @Test
    fun `running ignores mismatched active run`() {
        val progress = SyncProgress(SyncPhase.Photo, done = 1, total = 4)
        val resolution =
            resolveSyncStatus(
                input(
                    workInfos = listOf(snapshot(workA, SyncWorkState.Running)),
                    activeRun = ActiveSyncRun(workId = staleRun, progress = progress),
                ),
            )

        assertEquals(DurableSyncStatus.Running(workA, null), resolution.status)
    }

    @Test
    fun `active work outranks terminal states and pending rows`() {
        val resolution =
            resolveSyncStatus(
                input(
                    workInfos =
                        listOf(
                            snapshot(workA, SyncWorkState.Succeeded),
                            snapshot(workB, SyncWorkState.Running),
                        ),
                    pending = PendingSyncCounts(decisions = 9, photos = 9),
                ),
            )

        assertEquals(DurableSyncStatus.Running(workB, null), resolution.status)
    }

    @Test
    fun `failed selected work never consumes an active run`() {
        val progress = SyncProgress(SyncPhase.Photo, done = 1, total = 4)
        val resolution =
            resolveSyncStatus(
                input(
                    workInfos =
                        listOf(
                            snapshot(workA, SyncWorkState.Failed, errorMessage = "boom"),
                        ),
                    activeRun = ActiveSyncRun(workId = workA, progress = progress),
                ),
            )

        assertEquals(DurableSyncStatus.Failed(workA, "boom"), resolution.status)
    }

    @Test
    fun `succeeded selected work never consumes an active run`() {
        val progress = SyncProgress(SyncPhase.Photo, done = 1, total = 4)
        val resolution =
            resolveSyncStatus(
                input(
                    workInfos = listOf(snapshot(workA, SyncWorkState.Succeeded)),
                    activeRun = ActiveSyncRun(workId = workA, progress = progress),
                ),
            )

        assertEquals(DurableSyncStatus.Complete(workA), resolution.status)
    }

    @Test
    fun `offline enqueued resolves to waiting for network with counts`() {
        val resolution =
            resolveSyncStatus(
                input(
                    workInfos = listOf(snapshot(workA, SyncWorkState.Enqueued)),
                    pending = PendingSyncCounts(decisions = 2, photos = 3),
                    networkState = ValidatedNetworkState.Offline,
                ),
            )

        assertEquals(workA, resolution.selectedWorkId)
        assertEquals(DurableSyncStatus.WaitingForNetwork(workA, 2, 3), resolution.status)
    }

    @Test
    fun `blocked only is ignored and resolves to idle`() {
        val resolution =
            resolveSyncStatus(
                input(
                    workInfos = listOf(snapshot(workA, SyncWorkState.Blocked)),
                    networkState = ValidatedNetworkState.Offline,
                ),
            )

        assertEquals(null, resolution.selectedWorkId)
        assertEquals(DurableSyncStatus.Idle, resolution.status)
    }

    @Test
    fun `offline enqueued with a matching run still waits for network`() {
        val progress = SyncProgress(SyncPhase.Decision, done = 1, total = 5)
        val resolution =
            resolveSyncStatus(
                input(
                    workInfos = listOf(snapshot(workA, SyncWorkState.Enqueued)),
                    activeRun = ActiveSyncRun(workId = workA, progress = progress),
                    networkState = ValidatedNetworkState.Offline,
                ),
            )

        assertEquals(DurableSyncStatus.WaitingForNetwork(workA, 0, 0), resolution.status)
    }

    @Test
    fun `online enqueued without a run resolves to resuming`() {
        val resolution =
            resolveSyncStatus(
                input(
                    workInfos = listOf(snapshot(workA, SyncWorkState.Enqueued)),
                    networkState = ValidatedNetworkState.Online,
                ),
            )

        assertEquals(workA, resolution.selectedWorkId)
        assertEquals(DurableSyncStatus.Resuming(workA), resolution.status)
    }

    @Test
    fun `online enqueued with a matching run resolves to running`() {
        val progress = SyncProgress(SyncPhase.Decision, done = 1, total = 5)
        val resolution =
            resolveSyncStatus(
                input(
                    workInfos = listOf(snapshot(workA, SyncWorkState.Enqueued)),
                    activeRun = ActiveSyncRun(workId = workA, progress = progress),
                    networkState = ValidatedNetworkState.Online,
                ),
            )

        assertEquals(DurableSyncStatus.Running(workA, progress), resolution.status)
    }

    @Test
    fun `online enqueued with a mismatched run resolves to resuming`() {
        val progress = SyncProgress(SyncPhase.Decision, done = 1, total = 5)
        val resolution =
            resolveSyncStatus(
                input(
                    workInfos = listOf(snapshot(workA, SyncWorkState.Enqueued)),
                    activeRun = ActiveSyncRun(workId = staleRun, progress = progress),
                    networkState = ValidatedNetworkState.Online,
                ),
            )

        assertEquals(DurableSyncStatus.Resuming(workA), resolution.status)
    }

    @Test
    fun `blocked only with a matching run does not consume the run`() {
        val progress = SyncProgress(SyncPhase.Photo, done = 2, total = 8)
        val resolution =
            resolveSyncStatus(
                input(
                    workInfos = listOf(snapshot(workA, SyncWorkState.Blocked)),
                    activeRun = ActiveSyncRun(workId = workA, progress = progress),
                    networkState = ValidatedNetworkState.Online,
                ),
            )

        assertEquals(null, resolution.selectedWorkId)
        assertEquals(DurableSyncStatus.Idle, resolution.status)
    }

    @Test
    fun `failed resolves to failed with message`() {
        val resolution =
            resolveSyncStatus(
                input(
                    workInfos = listOf(snapshot(workA, SyncWorkState.Failed, errorMessage = "bad")),
                ),
            )

        assertEquals(workA, resolution.selectedWorkId)
        assertEquals(DurableSyncStatus.Failed(workA, "bad"), resolution.status)
    }

    @Test
    fun `failed without a message preserves null`() {
        val resolution =
            resolveSyncStatus(
                input(workInfos = listOf(snapshot(workA, SyncWorkState.Failed))),
            )

        assertEquals(DurableSyncStatus.Failed(workA, null), resolution.status)
    }

    @Test
    fun `cancelled resolves to failed with or without pending rows`() {
        val withoutPending =
            resolveSyncStatus(
                input(workInfos = listOf(snapshot(workA, SyncWorkState.Cancelled))),
            )
        val withPending =
            resolveSyncStatus(
                input(
                    workInfos = listOf(snapshot(workA, SyncWorkState.Cancelled)),
                    pending = PendingSyncCounts(decisions = 1, photos = 1),
                ),
            )

        assertEquals(DurableSyncStatus.Failed(workA, null), withoutPending.status)
        assertEquals(DurableSyncStatus.Failed(workA, null), withPending.status)
    }

    @Test
    fun `succeeded with pending rows resolves to pending`() {
        val resolution =
            resolveSyncStatus(
                input(
                    workInfos = listOf(snapshot(workA, SyncWorkState.Succeeded)),
                    pending = PendingSyncCounts(decisions = 2, photos = 1),
                ),
            )

        assertEquals(workA, resolution.selectedWorkId)
        assertEquals(DurableSyncStatus.Pending(decisions = 2, photos = 1), resolution.status)
    }

    @Test
    fun `succeeded without pending rows resolves to complete`() {
        val resolution =
            resolveSyncStatus(
                input(workInfos = listOf(snapshot(workA, SyncWorkState.Succeeded))),
            )

        assertEquals(workA, resolution.selectedWorkId)
        assertEquals(DurableSyncStatus.Complete(workA), resolution.status)
    }

    @Test
    fun `running without an active run has null progress`() {
        val resolution =
            resolveSyncStatus(
                input(workInfos = listOf(snapshot(workA, SyncWorkState.Running))),
            )

        assertEquals(DurableSyncStatus.Running(workA, null), resolution.status)
    }

    @Test
    fun `missing zero and invalid totals remain indeterminate`() {
        listOf<Int?>(null, 0, -5).forEach { total ->
            val progress = SyncProgress(SyncPhase.Photo, done = 3, total = total)
            val resolution =
                resolveSyncStatus(
                    input(
                        workInfos = listOf(snapshot(workA, SyncWorkState.Running)),
                        activeRun = ActiveSyncRun(workId = workA, progress = progress),
                    ),
                )

            val status = statusOf<DurableSyncStatus.Running>(resolution)
            assertEquals(progress, status.progress)
            assertEquals(total, status.progress?.total)
        }
    }

    @Test
    fun `unknown phase is preserved even with numeric progress`() {
        val progress = SyncProgress(SyncPhase.Unknown, done = 7, total = 7)
        val resolution =
            resolveSyncStatus(
                input(
                    workInfos = listOf(snapshot(workA, SyncWorkState.Running)),
                    activeRun = ActiveSyncRun(workId = workA, progress = progress),
                ),
            )

        val status = statusOf<DurableSyncStatus.Running>(resolution)
        assertEquals(SyncPhase.Unknown, status.progress?.phase)
    }

    @Test
    fun `pending hasPending is true only when a count is positive`() {
        assertEquals(false, PendingSyncCounts(0, 0).hasPending)
        assertEquals(true, PendingSyncCounts(1, 0).hasPending)
        assertEquals(true, PendingSyncCounts(0, 1).hasPending)
    }

    private fun snapshot(
        id: UUID,
        state: SyncWorkState,
        generation: Int? = null,
        enqueueOrRunTimestampMillis: Long? = null,
        runAttemptCount: Int? = null,
        sessionExpired: Boolean = false,
        errorMessage: String? = null,
        returnedListIndex: Int = 0,
    ): SyncWorkSnapshot =
        SyncWorkSnapshot(
            id = id,
            state = state,
            generation = generation,
            enqueueOrRunTimestampMillis = enqueueOrRunTimestampMillis,
            runAttemptCount = runAttemptCount,
            output = SyncWorkOutput(sessionExpired = sessionExpired, errorMessage = errorMessage),
            returnedListIndex = returnedListIndex,
        )

    private fun input(
        workInfos: List<SyncWorkSnapshot> = emptyList(),
        activeRun: ActiveSyncRun? = null,
        pending: PendingSyncCounts = PendingSyncCounts(decisions = 0, photos = 0),
        networkState: ValidatedNetworkState = ValidatedNetworkState.Online,
    ): SyncResolutionInput =
        SyncResolutionInput(
            workInfos = workInfos,
            activeRun = activeRun,
            pending = pending,
            networkState = networkState,
        )

    private inline fun <reified T> statusOf(resolution: SyncResolution): T {
        assertTrue(resolution.status is T)
        return resolution.status as T
    }

    private fun uuid(value: Long): UUID = UUID(0L, value)
}
