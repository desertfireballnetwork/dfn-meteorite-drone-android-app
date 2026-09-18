package au.edu.fireballs.stage4.ui.screen.stage4map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import au.edu.fireballs.stage4.data.local.LocalDecisionEntity
import au.edu.fireballs.stage4.data.local.dao.ClaimDao
import au.edu.fireballs.stage4.data.local.dao.LocalDecisionDao
import au.edu.fireballs.stage4.data.local.dao.OfflineBundleDao
import au.edu.fireballs.stage4.data.repository.CandidateImageRepository
import au.edu.fireballs.stage4.data.repository.DurableSyncStatus
import au.edu.fireballs.stage4.data.repository.NetworkState
import au.edu.fireballs.stage4.data.repository.NetworkStateRepository
import au.edu.fireballs.stage4.data.repository.Stage4FetchResult
import au.edu.fireballs.stage4.data.repository.Stage4Repository
import au.edu.fireballs.stage4.data.repository.SyncStatusSource
import au.edu.fireballs.stage4.data.repository.isSyncGated
import au.edu.fireballs.stage4.data.tiles.AuthenticatedTileHttpInterceptor
import au.edu.fireballs.stage4.data.tiles.TileStore
import au.edu.fireballs.stage4.domain.model.MapCameraTarget
import au.edu.fireballs.stage4.domain.model.Stage4Candidate
import au.edu.fireballs.stage4.domain.model.Stage4State
import au.edu.fireballs.stage4.domain.model.resolveInitialCamera
import au.edu.fireballs.stage4.ui.screen.basecamp.PreDownloadWorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.cos

private const val OFFLINE_CAMERA_ZOOM = 18.0
private const val OVERLAY_MIN_ZOOM = 14.0
private const val OVERLAY_MAX_COUNT = 30

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

sealed interface ConnectionBannerState {
    data object Online : ConnectionBannerState

    data object Offline : ConnectionBannerState

    data object Downloading : ConnectionBannerState
}

private fun computeOverlayCandidates(
    source: Stage4State?,
    claims: Set<Long>,
    camera: MapCameraTarget?,
    selected: Long?,
    urlFor: (Long) -> String,
): List<Pair<Long, String>> {
    if (source == null || camera == null || camera.zoom < OVERLAY_MIN_ZOOM) {
        return emptyList()
    }
    val wanted = claims + (selected?.let { setOf(it) } ?: emptySet())
    val candidates =
        (source.unprocessedCandidates + source.yesMeteorites + source.noMeteorites)
            .filter { it.inferenceResultId in wanted && it.geoCentroid != null }
    if (candidates.isEmpty()) {
        return emptyList()
    }
    val camLat = camera.latitude
    val camLon = camera.longitude
    val cosLat = cos(Math.toRadians(camLat)).coerceAtLeast(0.1)
    val ranked =
        candidates.sortedBy { candidate ->
            val centroid = candidate.geoCentroid ?: return@sortedBy Double.MAX_VALUE
            val dx = (centroid.longitude - camLon) * cosLat
            val dy = centroid.latitude - camLat
            dx * dx + dy * dy
        }
    return ranked
        .take(OVERLAY_MAX_COUNT)
        .map { it.inferenceResultId to urlFor(it.inferenceResultId) }
}

private fun resolveOfflineCamera(
    state: Stage4State,
    ownClaimedIds: Set<Long>,
): MapCameraTarget? {
    val claimed =
        (state.unprocessedCandidates + state.yesMeteorites + state.noMeteorites)
            .filter { it.inferenceResultId in ownClaimedIds }
    val centroids = claimed.mapNotNull { it.geoCentroid }
    if (centroids.isEmpty()) {
        return null
    }
    val latitude = centroids.map { it.latitude }.average()
    val longitude = centroids.map { it.longitude }.average()
    return MapCameraTarget(latitude, longitude, OFFLINE_CAMERA_ZOOM)
}

internal fun filterCandidatesForNetwork(
    state: Stage4State,
    networkState: NetworkState,
    ownClaimedIds: Set<Long>,
): Stage4State {
    if (networkState == NetworkState.Online) {
        return state
    }
    return state.copy(
        unprocessedCandidates =
            state.unprocessedCandidates.filter { it.isVisibleOffline(ownClaimedIds) },
        yesMeteorites = state.yesMeteorites.filter { it.isVisibleOffline(ownClaimedIds) },
        noMeteorites = state.noMeteorites.filter { it.isVisibleOffline(ownClaimedIds) },
    )
}

private fun Stage4Candidate.isVisibleOffline(ownClaimedIds: Set<Long>): Boolean =
    claimedByOther || inferenceResultId in ownClaimedIds

@HiltViewModel
class Stage4MapViewModel
    @Inject
    constructor(
        private val stage4Repository: Stage4Repository,
        private val claimDao: ClaimDao,
        private val localDecisionDao: LocalDecisionDao,
        private val offlineBundleDao: OfflineBundleDao,
        private val candidateImageRepository: CandidateImageRepository,
        val tileStore: TileStore,
        private val tileHttpInterceptor: AuthenticatedTileHttpInterceptor,
        private val syncWorkManager: SyncWorkManager,
        private val networkStateRepository: NetworkStateRepository,
        private val preDownloadWorkManager: PreDownloadWorkManager,
        private val syncStatusSource: SyncStatusSource,
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
        private val cameraStateFlow = MutableStateFlow<MapCameraTarget?>(null)
        private val selectedCandidateFlow = MutableStateFlow<Long?>(null)
        private val ownClaimsFlow: Flow<Set<Long>> =
            surveyIdFlow
                .flatMapLatest { id ->
                    if (id == null) {
                        flowOf(emptySet())
                    } else {
                        claimDao.getClaims(id, onlyActive = true).map { claims ->
                            claims
                                .filter { it.isMine }
                                .map { it.inferenceResultId }
                                .toSet()
                        }
                    }
                }

        val overlayCandidates: StateFlow<List<Pair<Long, String>>> =
            surveyIdFlow
                .flatMapLatest { id ->
                    if (id == null) {
                        flowOf(emptyList())
                    } else {
                        combine(
                            ownClaimsFlow,
                            cameraStateFlow.debounce(250).distinctUntilChanged(),
                            selectedCandidateFlow,
                            sourceStateFlow,
                        ) { claims, camera, selected, source ->
                            computeOverlayCandidates(
                                source,
                                claims,
                                camera,
                                selected,
                            ) { candidateId ->
                                candidateTileUrlPattern(id, candidateId)
                            }
                        }.distinctUntilChanged()
                    }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000),
                    initialValue = emptyList(),
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

        private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
        val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

        init {
            viewModelScope.launch {
                syncStatusSource.status.collect { status ->
                    if (status is DurableSyncStatus.SessionExpired) {
                        authExpiredFlow.value = true
                    }
                    _syncStatus.value = status.toMapSyncStatus()
                }
            }
        }

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
                        val networkFilteredFlow =
                            combine(
                                loadedFlow,
                                networkStateRepository.networkState,
                                ownClaimsFlow,
                                hasOfflineBundle,
                            ) { loaded, network, ownClaims, hasBundle ->
                                loaded?.let { current ->
                                    val offlineCamera =
                                        if (network == NetworkState.Offline && hasBundle) {
                                            resolveOfflineCamera(current.state, ownClaims)
                                        } else {
                                            null
                                        }
                                    current.copy(
                                        state =
                                            filterCandidatesForNetwork(
                                                current.state,
                                                network,
                                                ownClaims,
                                            ),
                                        cameraTarget = offlineCamera ?: current.cameraTarget,
                                    )
                                }
                            }

                        combine(
                            networkFilteredFlow,
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

        fun updateCamera(target: MapCameraTarget?) {
            cameraStateFlow.value = target
        }

        fun setSelectedCandidate(candidateId: Long?) {
            selectedCandidateFlow.value = candidateId
        }

        fun candidateTileUrlPattern(
            surveyId: Long,
            candidateId: Long,
        ): String = candidateImageRepository.getCandidateTileUrlPattern(surveyId, candidateId)

        fun syncNow() {
            if (syncStatusSource.status.value.isSyncGated) {
                return
            }
            syncWorkManager.enqueueSync()
        }
    }
