package au.edu.fireballs.stage4.ui.screen.stage4map

import au.edu.fireballs.stage4.data.repository.DurableSyncStatus

sealed interface SyncStatus {
    data object Idle : SyncStatus

    data object Pending : SyncStatus

    data object Running : SyncStatus

    data object Resuming : SyncStatus

    data object WaitingForNetwork : SyncStatus

    data object Complete : SyncStatus

    data object Failed : SyncStatus

    data object AuthExpired : SyncStatus
}

internal fun DurableSyncStatus.toMapSyncStatus(): SyncStatus =
    when (this) {
        DurableSyncStatus.Idle -> SyncStatus.Idle
        is DurableSyncStatus.Pending -> SyncStatus.Pending
        is DurableSyncStatus.Running -> SyncStatus.Running
        is DurableSyncStatus.Resuming -> SyncStatus.Resuming
        is DurableSyncStatus.WaitingForNetwork -> SyncStatus.WaitingForNetwork
        is DurableSyncStatus.Complete -> SyncStatus.Complete
        is DurableSyncStatus.Failed -> SyncStatus.Failed
        is DurableSyncStatus.SessionExpired -> SyncStatus.AuthExpired
    }
