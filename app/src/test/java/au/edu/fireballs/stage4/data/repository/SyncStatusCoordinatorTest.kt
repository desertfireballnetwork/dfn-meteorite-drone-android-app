package au.edu.fireballs.stage4.data.repository

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import app.cash.turbine.test
import au.edu.fireballs.stage4.data.local.SyncRunEntity
import au.edu.fireballs.stage4.data.local.dao.SyncRunDao
import au.edu.fireballs.stage4.sync.SyncOrchestrator
import au.edu.fireballs.stage4.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class SyncStatusCoordinatorTest {
    private val workA = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val workB = UUID.fromString("00000000-0000-0000-0000-00000000000b")

    @Test
    fun `status starts idle and re-resolves on each input change`() =
        runTest {
            val harness = harness()

            assertEquals(DurableSyncStatus.Idle, harness.coordinator.status.value)

            harness.pending.value = PendingSyncCounts(decisions = 2, photos = 3)
            testScheduler.runCurrent()
            assertEquals(
                DurableSyncStatus.Pending(decisions = 2, photos = 3),
                harness.coordinator.status.value,
            )

            harness.workInfos.value = listOf(info(workA, WorkInfo.State.RUNNING))
            testScheduler.runCurrent()
            assertEquals(DurableSyncStatus.Running(workA, null), harness.coordinator.status.value)
        }

    @Test
    fun `new status collector receives the current status`() =
        runTest {
            val harness = harness()
            harness.pending.value = PendingSyncCounts(decisions = 4, photos = 5)
            testScheduler.runCurrent()

            harness.coordinator.status.test {
                assertEquals(DurableSyncStatus.Pending(decisions = 4, photos = 5), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `offline enqueued resolves waiting for network`() =
        runTest {
            val harness = harness()
            harness.network.value = NetworkState.Offline
            harness.pending.value = PendingSyncCounts(decisions = 2, photos = 3)
            harness.workInfos.value = listOf(info(workA, WorkInfo.State.ENQUEUED))
            testScheduler.runCurrent()

            assertEquals(
                DurableSyncStatus.WaitingForNetwork(workA, decisions = 2, photos = 3),
                harness.coordinator.status.value,
            )
        }

    @Test
    fun `stale run does not override terminal work`() =
        runTest {
            val harness = harness()
            harness.currentRun.value =
                SyncRunEntity(
                    surveyId = 1L,
                    phase = SyncOrchestrator.PHASE_PHOTOS,
                    total = 10,
                    done = 4,
                )
            harness.workInfos.value = listOf(info(workA, WorkInfo.State.RUNNING))
            testScheduler.runCurrent()
            val expectedRun =
                DurableSyncStatus.Running(
                    workA,
                    SyncProgress(phase = SyncPhase.Photo, done = 4, total = 10),
                )
            assertEquals(expectedRun, harness.coordinator.status.value)

            harness.workInfos.value = listOf(info(workA, WorkInfo.State.SUCCEEDED))
            testScheduler.runCurrent()
            assertEquals(DurableSyncStatus.Complete(workA), harness.coordinator.status.value)
        }

    @Test
    fun `selected identity emits one completion and collector recreation does not repeat it`() =
        runTest {
            val harness = harness()
            harness.workInfos.value = listOf(info(workA, WorkInfo.State.RUNNING))
            testScheduler.runCurrent()

            harness.coordinator.completions.test {
                harness.workInfos.value = listOf(info(workA, WorkInfo.State.SUCCEEDED))
                testScheduler.runCurrent()
                assertEquals(SyncCompletion(workA), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }

            harness.coordinator.completions.test {
                testScheduler.runCurrent()
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a distinct later identity emits its own completion`() =
        runTest {
            val harness = harness()
            harness.workInfos.value = listOf(info(workA, WorkInfo.State.RUNNING))
            testScheduler.runCurrent()

            harness.coordinator.completions.test {
                harness.workInfos.value = listOf(info(workA, WorkInfo.State.SUCCEEDED))
                testScheduler.runCurrent()
                assertEquals(SyncCompletion(workA), awaitItem())

                harness.workInfos.value = listOf(info(workB, WorkInfo.State.SUCCEEDED))
                testScheduler.runCurrent()
                assertEquals(SyncCompletion(workB), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `succeeded with pending rows resolves pending and still completes once`() =
        runTest {
            val harness = harness()
            harness.pending.value = PendingSyncCounts(decisions = 1, photos = 1)
            harness.workInfos.value = listOf(info(workA, WorkInfo.State.RUNNING))
            testScheduler.runCurrent()

            harness.coordinator.completions.test {
                harness.workInfos.value = listOf(info(workA, WorkInfo.State.SUCCEEDED))
                testScheduler.runCurrent()
                assertEquals(SyncCompletion(workA), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(
                DurableSyncStatus.Pending(decisions = 1, photos = 1),
                harness.coordinator.status.value,
            )
        }

    @Test
    fun `session expiry resolves session expired and suppresses completion`() =
        runTest {
            val harness = harness()
            harness.workInfos.value = listOf(info(workA, WorkInfo.State.RUNNING))
            testScheduler.runCurrent()

            harness.coordinator.completions.test {
                harness.workInfos.value =
                    listOf(
                        info(
                            workA,
                            WorkInfo.State.SUCCEEDED,
                            outputData = workDataOf(SyncWorker.KEY_AUTH_EXPIRED to true),
                        ),
                    )
                testScheduler.runCurrent()
                assertEquals(
                    DurableSyncStatus.SessionExpired(workA),
                    harness.coordinator.status.value,
                )
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `rapid input changes settle to one coherent result`() =
        runTest {
            val harness = harness()
            val updates =
                listOf(
                    info(workA, WorkInfo.State.ENQUEUED),
                    info(workA, WorkInfo.State.RUNNING),
                    info(workA, WorkInfo.State.RUNNING),
                    info(workA, WorkInfo.State.SUCCEEDED),
                )
            updates.forEach { harness.workInfos.value = listOf(it) }
            testScheduler.runCurrent()

            assertEquals(DurableSyncStatus.Complete(workA), harness.coordinator.status.value)
        }

    private fun TestScope.harness(): Harness = Harness(backgroundScope, testScheduler)

    private class Harness(
        scope: CoroutineScope,
        scheduler: TestCoroutineScheduler,
    ) {
        val workInfos = MutableStateFlow<List<WorkInfo>>(emptyList())
        val currentRun = MutableStateFlow<SyncRunEntity?>(null)
        val pending = MutableStateFlow(PendingSyncCounts(decisions = 0, photos = 0))
        val network = MutableStateFlow<NetworkState>(NetworkState.Online)

        val coordinator =
            SyncStatusCoordinator(
                syncWorkInfoObserver = SyncWorkInfoObserver { workInfos },
                syncRunDao = FakeSyncRunDao(currentRun),
                pendingSyncCountSource = PendingSyncCountSource { pending },
                networkStateRepository =
                    mock<NetworkStateRepository> {
                        on { networkState } doReturn network
                    },
                ioDispatcher = UnconfinedTestDispatcher(scheduler),
                applicationScope = scope,
            )
    }

    private class FakeSyncRunDao(
        private val currentRun: MutableStateFlow<SyncRunEntity?>,
    ) : SyncRunDao {
        override fun observeCurrentRun(): Flow<SyncRunEntity?> = currentRun

        override fun observeRun(surveyId: Long): Flow<SyncRunEntity?> = flowOf(null)

        override suspend fun upsert(run: SyncRunEntity) = Unit

        override suspend fun clear(surveyId: Long) = Unit
    }

    private fun info(
        id: UUID,
        state: WorkInfo.State,
        outputData: Data = Data.EMPTY,
        runAttemptCount: Int = 0,
        generation: Int = 0,
    ): WorkInfo =
        WorkInfo(
            id,
            state,
            emptySet(),
            outputData,
            Data.EMPTY,
            runAttemptCount,
            generation,
        )
}
