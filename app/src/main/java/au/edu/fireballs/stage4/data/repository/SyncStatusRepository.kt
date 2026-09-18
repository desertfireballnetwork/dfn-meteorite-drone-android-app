package au.edu.fireballs.stage4.data.repository

import androidx.work.WorkInfo
import au.edu.fireballs.stage4.data.local.SyncRunEntity
import au.edu.fireballs.stage4.data.local.dao.SyncRunDao
import au.edu.fireballs.stage4.di.ApplicationScope
import au.edu.fireballs.stage4.di.IoDispatcher
import au.edu.fireballs.stage4.sync.SyncOrchestrator
import au.edu.fireballs.stage4.sync.SyncWorker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface DurableSyncStatus {
    data object Idle : DurableSyncStatus

    data class Pending(
        val decisions: Int,
        val photos: Int,
    ) : DurableSyncStatus

    data class WaitingForNetwork(
        val workId: UUID,
        val decisions: Int,
        val photos: Int,
    ) : DurableSyncStatus

    data class Running(
        val workId: UUID,
        val progress: SyncProgress?,
    ) : DurableSyncStatus

    data class Resuming(
        val workId: UUID,
    ) : DurableSyncStatus

    data class Complete(
        val workId: UUID,
    ) : DurableSyncStatus

    data class Failed(
        val workId: UUID,
        val message: String?,
    ) : DurableSyncStatus

    data class SessionExpired(
        val workId: UUID,
    ) : DurableSyncStatus
}

val DurableSyncStatus.isSyncGated: Boolean
    get() =
        this is DurableSyncStatus.Running ||
            this is DurableSyncStatus.Resuming ||
            this is DurableSyncStatus.WaitingForNetwork

enum class SyncPhase {
    Photo,
    Decision,
    Unknown,
}

data class SyncProgress(
    val phase: SyncPhase,
    val done: Int,
    val total: Int?,
)

data class PendingSyncCounts(
    val decisions: Int,
    val photos: Int,
) {
    val hasPending: Boolean
        get() = decisions > 0 || photos > 0
}

data class SyncWorkOutput(
    val sessionExpired: Boolean,
    val errorMessage: String?,
)

enum class SyncWorkState {
    Running,
    Enqueued,
    Blocked,
    Succeeded,
    Failed,
    Cancelled,
}

data class SyncWorkSnapshot(
    val id: UUID,
    val state: SyncWorkState,
    val generation: Int?,
    val enqueueOrRunTimestampMillis: Long?,
    val runAttemptCount: Int?,
    val output: SyncWorkOutput,
    val returnedListIndex: Int,
)

enum class ValidatedNetworkState {
    Online,
    Offline,
}

data class ActiveSyncRun(
    val workId: UUID?,
    val progress: SyncProgress?,
)

data class SyncResolutionInput(
    val workInfos: List<SyncWorkSnapshot>,
    val activeRun: ActiveSyncRun?,
    val pending: PendingSyncCounts,
    val networkState: ValidatedNetworkState,
)

data class SyncResolution(
    val selectedWorkId: UUID?,
    val status: DurableSyncStatus,
)

data class SyncCompletion(
    val workId: UUID,
)

interface SyncStatusSource {
    val status: StateFlow<DurableSyncStatus>

    val completions: Flow<SyncCompletion>
}

fun resolveSyncStatus(input: SyncResolutionInput): SyncResolution {
    val selected = selectSyncWork(input.workInfos)
    if (selected == null) {
        return SyncResolution(selectedWorkId = null, status = pendingStatus(input.pending))
    }
    if (selected.output.sessionExpired) {
        return SyncResolution(
            selectedWorkId = selected.id,
            status = DurableSyncStatus.SessionExpired(selected.id),
        )
    }
    val run = input.activeRun?.takeIf { it.matches(selected.id) }
    return when (selected.state) {
        SyncWorkState.Running ->
            SyncResolution(
                selectedWorkId = selected.id,
                status = DurableSyncStatus.Running(selected.id, run?.progress),
            )
        SyncWorkState.Enqueued ->
            SyncResolution(
                selectedWorkId = selected.id,
                status = resolveEnqueuedStatus(selected, input, run),
            )
        SyncWorkState.Blocked ->
            SyncResolution(
                selectedWorkId = null,
                status = pendingStatus(input.pending),
            )
        SyncWorkState.Failed,
        SyncWorkState.Cancelled,
        ->
            SyncResolution(
                selectedWorkId = selected.id,
                status = DurableSyncStatus.Failed(selected.id, selected.output.errorMessage),
            )
        SyncWorkState.Succeeded ->
            SyncResolution(
                selectedWorkId = selected.id,
                status = succeededStatus(selected, input.pending),
            )
    }
}

private fun succeededStatus(
    work: SyncWorkSnapshot,
    pending: PendingSyncCounts,
): DurableSyncStatus =
    if (pending.hasPending) {
        DurableSyncStatus.Pending(decisions = pending.decisions, photos = pending.photos)
    } else {
        DurableSyncStatus.Complete(work.id)
    }

private fun resolveEnqueuedStatus(
    work: SyncWorkSnapshot,
    input: SyncResolutionInput,
    run: ActiveSyncRun?,
): DurableSyncStatus =
    when (input.networkState) {
        ValidatedNetworkState.Offline ->
            DurableSyncStatus.WaitingForNetwork(
                workId = work.id,
                decisions = input.pending.decisions,
                photos = input.pending.photos,
            )
        ValidatedNetworkState.Online ->
            run?.let { DurableSyncStatus.Running(work.id, it.progress) }
                ?: DurableSyncStatus.Resuming(work.id)
    }

private fun pendingStatus(pending: PendingSyncCounts): DurableSyncStatus =
    if (pending.hasPending) {
        DurableSyncStatus.Pending(decisions = pending.decisions, photos = pending.photos)
    } else {
        DurableSyncStatus.Idle
    }

private fun ActiveSyncRun.matches(workId: UUID): Boolean =
    this.workId == null || this.workId == workId

private fun selectSyncWork(workInfos: List<SyncWorkSnapshot>): SyncWorkSnapshot? {
    workInfos.filter { it.state == SyncWorkState.Running }.latestByListIndex()?.let { return it }
    workInfos.filter { it.state == SyncWorkState.Enqueued }.latestByListIndex()?.let { return it }
    return workInfos.filter { it.state.isTerminal }.maxWithOrNull(terminalComparator)
}

private fun List<SyncWorkSnapshot>.latestByListIndex(): SyncWorkSnapshot? =
    maxByOrNull { it.returnedListIndex }

private val terminalComparator: Comparator<SyncWorkSnapshot> =
    compareBy<SyncWorkSnapshot> { it.generation ?: Int.MIN_VALUE }
        .thenBy { it.enqueueOrRunTimestampMillis ?: Long.MIN_VALUE }
        .thenBy { it.runAttemptCount ?: Int.MIN_VALUE }
        .thenBy { it.returnedListIndex }

private val SyncWorkState.isTerminal: Boolean
    get() =
        this == SyncWorkState.Succeeded ||
            this == SyncWorkState.Failed ||
            this == SyncWorkState.Cancelled

fun interface SyncWorkInfoObserver {
    fun observe(): Flow<List<WorkInfo>>
}

fun interface PendingSyncCountSource {
    fun observe(): Flow<PendingSyncCounts>
}

@Singleton
class SyncStatusCoordinator
    @Inject
    constructor(
        private val syncWorkInfoObserver: SyncWorkInfoObserver,
        private val syncRunDao: SyncRunDao,
        private val pendingSyncCountSource: PendingSyncCountSource,
        private val networkStateRepository: NetworkStateRepository,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
        @ApplicationScope private val applicationScope: CoroutineScope,
    ) : SyncStatusSource {
        private val mutableStatus = MutableStateFlow<DurableSyncStatus>(DurableSyncStatus.Idle)

        private val mutableCompletions =
            MutableSharedFlow<SyncCompletion>(
                replay = 0,
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        private val emittedCompletions = mutableSetOf<UUID>()

        override val status: StateFlow<DurableSyncStatus> = mutableStatus.asStateFlow()

        override val completions: Flow<SyncCompletion> = mutableCompletions.asSharedFlow()

        init {
            applicationScope.launch(ioDispatcher) {
                combine(
                    syncWorkInfoObserver.observe().map { it.toSyncWorkSnapshots() },
                    syncRunDao.observeCurrentRun().map { it?.toActiveSyncRun() },
                    pendingSyncCountSource.observe(),
                    networkStateRepository.networkState.map(::toValidatedNetworkState),
                ) { workInfos, activeRun, pending, networkState ->
                    SyncResolutionInput(
                        workInfos = workInfos,
                        activeRun = activeRun,
                        pending = pending,
                        networkState = networkState,
                    )
                }.collect { input ->
                    val resolution = resolveSyncStatus(input)
                    mutableStatus.value = resolution.status
                    recordCompletion(resolution, input.workInfos)
                }
            }
        }

        private fun recordCompletion(
            resolution: SyncResolution,
            workInfos: List<SyncWorkSnapshot>,
        ) {
            val selectedId = resolution.selectedWorkId ?: return
            val selected = workInfos.firstOrNull { it.id == selectedId } ?: return
            if (selected.state != SyncWorkState.Succeeded || selected.output.sessionExpired) {
                return
            }
            if (!emittedCompletions.add(selected.id)) {
                return
            }
            mutableCompletions.tryEmit(SyncCompletion(selected.id))
        }
    }

private fun List<WorkInfo>.toSyncWorkSnapshots(): List<SyncWorkSnapshot> =
    mapIndexed { index, info -> info.toSyncWorkSnapshot(index) }

private fun WorkInfo.toSyncWorkSnapshot(returnedListIndex: Int): SyncWorkSnapshot =
    SyncWorkSnapshot(
        id = id,
        state = state.toSyncWorkState(),
        generation = generation,
        enqueueOrRunTimestampMillis = null,
        runAttemptCount = runAttemptCount,
        output =
            SyncWorkOutput(
                sessionExpired = outputData.getBoolean(SyncWorker.KEY_AUTH_EXPIRED, false),
                errorMessage = null,
            ),
        returnedListIndex = returnedListIndex,
    )

private fun WorkInfo.State.toSyncWorkState(): SyncWorkState =
    when (this) {
        WorkInfo.State.RUNNING -> SyncWorkState.Running
        WorkInfo.State.ENQUEUED -> SyncWorkState.Enqueued
        WorkInfo.State.BLOCKED -> SyncWorkState.Blocked
        WorkInfo.State.SUCCEEDED -> SyncWorkState.Succeeded
        WorkInfo.State.FAILED -> SyncWorkState.Failed
        WorkInfo.State.CANCELLED -> SyncWorkState.Cancelled
    }

private fun SyncRunEntity.toActiveSyncRun(): ActiveSyncRun =
    ActiveSyncRun(workId = null, progress = toSyncProgress())

private fun SyncRunEntity.toSyncProgress(): SyncProgress =
    SyncProgress(
        phase =
            when (phase) {
                SyncOrchestrator.PHASE_PHOTOS -> SyncPhase.Photo
                SyncOrchestrator.PHASE_VERDICTS -> SyncPhase.Decision
                else -> SyncPhase.Unknown
            },
        done = done,
        total = total.takeIf { it > 0 },
    )

private fun toValidatedNetworkState(state: NetworkState): ValidatedNetworkState =
    when (state) {
        NetworkState.Online -> ValidatedNetworkState.Online
        NetworkState.Offline -> ValidatedNetworkState.Offline
    }
