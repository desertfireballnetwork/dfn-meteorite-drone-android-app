package au.edu.fireballs.stage4.ui.screen.stage4map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import au.edu.fireballs.stage4.data.local.LocalDecisionEntity
import au.edu.fireballs.stage4.data.local.dao.LocalDecisionDao
import au.edu.fireballs.stage4.data.local.dao.OfflineBundleDao
import au.edu.fireballs.stage4.data.repository.CandidateImageRepository
import au.edu.fireballs.stage4.data.repository.NetworkState
import au.edu.fireballs.stage4.data.repository.NetworkStateRepository
import au.edu.fireballs.stage4.data.repository.Stage4FetchResult
import au.edu.fireballs.stage4.data.repository.Stage4Repository
import au.edu.fireballs.stage4.data.tiles.AuthenticatedTileHttpInterceptor
import au.edu.fireballs.stage4.data.tiles.TileStore
import au.edu.fireballs.stage4.domain.model.MapCameraTarget
import au.edu.fireballs.stage4.domain.model.Stage4Candidate
import au.edu.fireballs.stage4.domain.model.Stage4State
import au.edu.fireballs.stage4.domain.model.resolveInitialCamera
import au.edu.fireballs.stage4.sync.SyncWorker
import au.edu.fireballs.stage4.ui.screen.basecamp.PreDownloadWorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LayerToggleState(
    val showYes: Boolean = true,
    val showNo: Boolean = true,
    val showUnprocessed: Boolean = true,
    val showSurveyedAreas: Boolean = true,
)

enum class LayerType {
    YES,
    NO,
    UNPROCESSED,
    SURVEYED_AREAS,
}

sealed interface Stage4MapUiState {
    data object Loading : Stage4MapUiState

    data class Loaded(
        val state: Stage4State,
        val cameraTarget: MapCameraTarget?,
        val layerToggleState: LayerToggleState = LayerToggleState(),
        val isRefreshing: Boolean = false,
        val userMessage: String? = null,
    ) : Stage4MapUiState

    data class Error(
        val message: String,
    ) : Stage4MapUiState

    data object AuthExpired : Stage4MapUiState
}

sealed interface SyncStatus {
    data object Idle : SyncStatus

    data object Syncing : SyncStatus

    data object Complete : SyncStatus

    data object Failed : SyncStatus

    data object AuthExpired : SyncStatus
}

sealed interface ConnectionBannerState {
    data object Online : ConnectionBannerState

    data object Offline : ConnectionBannerState

    data object Downloading : ConnectionBannerState
}

@HiltViewModel
class Stage4MapViewModel
    @Inject
    constructor(
        private val stage4Repository: Stage4Repository,
        private val localDecisionDao: LocalDecisionDao,
        private val offlineBundleDao: OfflineBundleDao,
        private val candidateImageRepository: CandidateImageRepository,
        val tileStore: TileStore,
        private val tileHttpInterceptor: AuthenticatedTileHttpInterceptor,
        private val syncWorkManager: SyncWorkManager,
        private val networkStateRepository: NetworkStateRepository,
        private val preDownloadWorkManager: PreDownloadWorkManager,
    ) : ViewModel() {
        init {
            tileHttpInterceptor.onAuthLost = { authExpiredFlow.value = true }
        }

        private val layerToggleStateFlow = MutableStateFlow(LayerToggleState())
        private val sourceStateFlow = MutableStateFlow<Stage4State?>(null)
        private val isRefreshingFlow = MutableStateFlow(false)
        private val userMessageFlow = MutableStateFlow<String?>(null)
        private val errorFlow = MutableStateFlow<String?>(null)
        private val authExpiredFlow = MutableStateFlow(false)

        private val surveyIdFlow = MutableStateFlow<Long?>(null)

        private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
        val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

        private var syncObserveJob: Job? = null
        private var observedWorkId: java.util.UUID? = null

        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState: StateFlow<Stage4MapUiState> =
            surveyIdFlow
                .flatMapLatest { id ->
                    if (id == null) {
                        combine(errorFlow, authExpiredFlow) { error, authExpired ->
                            when {
                                authExpired -> Stage4MapUiState.AuthExpired
                                error != null -> Stage4MapUiState.Error(error)
                                else -> Stage4MapUiState.Loading
                            }
                        }
                    } else {
                        val loadedFlow =
                            combine(
                                sourceStateFlow,
                                localDecisionDao.observeDecisionsForSurvey(id),
                                layerToggleStateFlow,
                                isRefreshingFlow,
                                userMessageFlow,
                            ) { source, decisions, toggles, refreshing, msg ->
                                source?.let {
                                    Stage4MapUiState.Loaded(
                                        state = mergeStateWithDecisions(it, decisions),
                                        cameraTarget = resolveInitialCamera(it),
                                        layerToggleState = toggles,
                                        isRefreshing = refreshing,
                                        userMessage = msg,
                                    )
                                }
                            }

                        combine(
                            loadedFlow,
                            errorFlow,
                            authExpiredFlow,
                        ) { loaded, error, authExpired ->
                            when {
                                authExpired -> Stage4MapUiState.AuthExpired
                                error != null -> Stage4MapUiState.Error(error)
                                loaded != null -> loaded
                                else -> Stage4MapUiState.Loading
                            }
                        }
                    }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000),
                    initialValue = Stage4MapUiState.Loading,
                )

        private var fetchJob: Job? = null

        @OptIn(ExperimentalCoroutinesApi::class)
        val connectionBannerState: StateFlow<ConnectionBannerState> =
            surveyIdFlow
                .flatMapLatest { surveyId ->
                    if (surveyId == null) {
                        flowOf(ConnectionBannerState.Online)
                    } else {
                        combine(
                            networkStateRepository.networkState,
                            preDownloadWorkManager.getWorkInfosForUniqueWorkFlow(
                                PreDownloadWorkManager.UNIQUE_WORK_PREFIX + surveyId,
                            ),
                        ) { network, workInfos ->
                            val downloading =
                                workInfos.any {
                                    it.state == WorkInfo.State.ENQUEUED ||
                                        it.state == WorkInfo.State.RUNNING
                                }
                            when {
                                downloading -> ConnectionBannerState.Downloading
                                network is NetworkState.Online -> ConnectionBannerState.Online
                                else -> ConnectionBannerState.Offline
                            }
                        }
                    }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000),
                    initialValue = ConnectionBannerState.Online,
                )

        val networkState: StateFlow<NetworkState> =
            networkStateRepository.networkState.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = NetworkState.Offline,
            )

        @OptIn(ExperimentalCoroutinesApi::class)
        val hasOfflineBundle: StateFlow<Boolean> =
            surveyIdFlow
                .flatMapLatest { surveyId ->
                    if (surveyId == null) {
                        flowOf(false)
                    } else {
                        offlineBundleDao
                            .observeLatestBundleForSurvey(surveyId)
                            .map { it != null }
                    }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000),
                    initialValue = false,
                )

        fun toggleLayer(
            type: LayerType,
            visible: Boolean,
        ) {
            layerToggleStateFlow.value =
                when (type) {
                    LayerType.YES -> layerToggleStateFlow.value.copy(showYes = visible)
                    LayerType.NO -> layerToggleStateFlow.value.copy(showNo = visible)
                    LayerType.UNPROCESSED ->
                        layerToggleStateFlow.value.copy(
                            showUnprocessed = visible,
                        )

                    LayerType.SURVEYED_AREAS ->
                        layerToggleStateFlow.value.copy(
                            showSurveyedAreas = visible,
                        )
                }
        }

        fun openSurvey(surveyId: Long) {
            fetchJob?.cancel()

            fetchJob =
                viewModelScope.launch {
                    val currentState = sourceStateFlow.value
                    val refreshInPlace = currentState != null && currentState.survey.id == surveyId

                    if (refreshInPlace) {
                        isRefreshingFlow.value = true
                        userMessageFlow.value = null
                    } else {
                        // Clear source state and errors so the combine pipeline resets cleanly
                        sourceStateFlow.value = null
                        errorFlow.value = null
                        authExpiredFlow.value = false
                        surveyIdFlow.value = surveyId
                    }

                    when (val result = stage4Repository.getCandidatesState(surveyId)) {
                        is Stage4FetchResult.Success -> {
                            sourceStateFlow.value = result.state
                            errorFlow.value = null
                            isRefreshingFlow.value = false
                            if (result.isOffline) {
                                userMessageFlow.value = "Offline — displaying previous map data"
                            }
                        }

                        is Stage4FetchResult.Error -> {
                            if (refreshInPlace) {
                                isRefreshingFlow.value = false
                                userMessageFlow.value =
                                    result.message ?: "Failed to refresh survey map"
                            } else {
                                errorFlow.value =
                                    result.message ?: "Failed to load survey candidates"
                            }
                        }

                        is Stage4FetchResult.NetworkError -> {
                            if (refreshInPlace) {
                                isRefreshingFlow.value = false
                                userMessageFlow.value = "Offline — displaying previous map data"
                            } else {
                                errorFlow.value = "Network error. Please check your connection."
                            }
                        }

                        is Stage4FetchResult.AuthExpired -> {
                            authExpiredFlow.value = true
                        }
                    }
                }
        }

        private fun mergeStateWithDecisions(
            state: Stage4State,
            decisions: List<LocalDecisionEntity>,
        ): Stage4State {
            val decisionMap = decisions.associateBy { it.inferenceResultId }
            val yesSet = state.yesMeteorites.mapTo(hashSetOf()) { it.inferenceResultId }
            val noSet = state.noMeteorites.mapTo(hashSetOf()) { it.inferenceResultId }
            val allCandidates =
                (state.unprocessedCandidates + state.yesMeteorites + state.noMeteorites)
                    .distinctBy { it.inferenceResultId }

            val newUnprocessed = mutableListOf<Stage4Candidate>()
            val newYes = mutableListOf<Stage4Candidate>()
            val newNo = mutableListOf<Stage4Candidate>()

            allCandidates.forEach { candidate ->
                val decision = decisionMap[candidate.inferenceResultId]
                val id = candidate.inferenceResultId
                when {
                    decision?.verdict == true -> newYes.add(candidate)
                    decision != null -> newNo.add(candidate)
                    id in yesSet -> newYes.add(candidate)
                    id in noSet -> newNo.add(candidate)
                    else -> newUnprocessed.add(candidate)
                }
            }

            return state.copy(
                unprocessedCandidates = newUnprocessed,
                yesMeteorites = newYes,
                noMeteorites = newNo,
            )
        }

        fun retry() {
            val surveyId = surveyIdFlow.value ?: return
            openSurvey(surveyId)
        }

        fun candidateTileUrlPattern(
            surveyId: Long,
            candidateId: Long,
        ): String = candidateImageRepository.getCandidateTileUrlPattern(surveyId, candidateId)

        fun syncNow() {
            val request = syncWorkManager.enqueueSync()
            if (observedWorkId != null) {
                _syncStatus.value = SyncStatus.Syncing
                return
            }
            observedWorkId = request.id
            syncObserveJob?.cancel()
            syncObserveJob =
                viewModelScope.launch {
                    syncWorkManager
                        .getWorkInfoByIdFlow(request.id)
                        .collect { info -> info?.let { handleSyncInfo(it) } }
                }
        }

        private fun handleSyncInfo(info: WorkInfo) {
            _syncStatus.value =
                when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING -> SyncStatus.Syncing
                    WorkInfo.State.SUCCEEDED -> {
                        observedWorkId = null
                        if (info.outputData.getBoolean(SyncWorker.KEY_AUTH_EXPIRED, false)) {
                            authExpiredFlow.value = true
                            SyncStatus.AuthExpired
                        } else {
                            SyncStatus.Complete
                        }
                    }
                    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                        observedWorkId = null
                        SyncStatus.Failed
                    }
                    WorkInfo.State.BLOCKED -> _syncStatus.value
                }
        }
    }
