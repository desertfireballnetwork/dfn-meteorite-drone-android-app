package au.edu.fireballs.stage4.ui.screen.basecamp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.edu.fireballs.stage4.data.repository.ClaimRepository
import au.edu.fireballs.stage4.data.repository.ClaimResult
import au.edu.fireballs.stage4.data.repository.Stage4FetchResult
import au.edu.fireballs.stage4.data.repository.Stage4Repository
import au.edu.fireballs.stage4.domain.model.Claim
import au.edu.fireballs.stage4.domain.model.GeoCoordinate
import au.edu.fireballs.stage4.domain.model.Stage4Candidate
import au.edu.fireballs.stage4.domain.model.Stage4State
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface BasecampUiState {
    data object Loading : BasecampUiState

    data class Loaded(
        val state: Stage4State,
        val polygonVertices: List<GeoCoordinate>?,
        val claims: List<Claim>,
        val mineOnly: Boolean,
        val isRefreshing: Boolean,
        val userMessage: String?,
    ) : BasecampUiState

    data class Error(
        val message: String,
    ) : BasecampUiState

    data object AuthExpired : BasecampUiState
}

sealed interface BasecampEvent {
    data class ShowMessage(
        val message: String,
    ) : BasecampEvent
}

private const val MAX_BATCH_IDS = 1000

private data class UiStatus(
    val mineOnly: Boolean,
    val isRefreshing: Boolean,
    val userMessage: String?,
    val error: String?,
    val authExpired: Boolean,
)

@HiltViewModel
class BasecampViewModel
    @Inject
    constructor(
        private val stage4Repository: Stage4Repository,
        private val claimRepository: ClaimRepository,
    ) : ViewModel() {
        private val sourceStateFlow = MutableStateFlow<Stage4State?>(null)
        private val claimsFlow = MutableStateFlow<List<Claim>>(emptyList())
        private val polygonVerticesFlow = MutableStateFlow<List<GeoCoordinate>?>(null)
        private val mineOnlyFlow = MutableStateFlow(false)
        private val isRefreshingFlow = MutableStateFlow(false)
        private val userMessageFlow = MutableStateFlow<String?>(null)
        private val errorFlow = MutableStateFlow<String?>(null)
        private val authExpiredFlow = MutableStateFlow(false)
        private val surveyIdFlow = MutableStateFlow<Long?>(null)

        private val _events = MutableSharedFlow<BasecampEvent>(extraBufferCapacity = 8)
        val events: SharedFlow<BasecampEvent> = _events.asSharedFlow()

        val uiState: StateFlow<BasecampUiState> =
            combine(
                combine(sourceStateFlow, claimsFlow) { source, claims -> source to claims },
                polygonVerticesFlow,
                combine(
                    mineOnlyFlow,
                    isRefreshingFlow,
                    userMessageFlow,
                    errorFlow,
                    authExpiredFlow,
                ) { mineOnly, refreshing, msg, error, authExpired ->
                    UiStatus(
                        mineOnly = mineOnly,
                        isRefreshing = refreshing,
                        userMessage = msg,
                        error = error,
                        authExpired = authExpired,
                    )
                },
            ) { content, polygon, status ->
                val (source, claims) = content
                when {
                    status.authExpired -> BasecampUiState.AuthExpired
                    status.error != null -> BasecampUiState.Error(status.error)
                    source != null ->
                        BasecampUiState.Loaded(
                            state = mergeClaims(source, claims),
                            polygonVertices = polygon,
                            claims =
                                if (status.mineOnly) {
                                    claims.filter { it.isMe }
                                } else {
                                    claims
                                },
                            mineOnly = status.mineOnly,
                            isRefreshing = status.isRefreshing,
                            userMessage = status.userMessage,
                        )

                    else -> BasecampUiState.Loading
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = BasecampUiState.Loading,
            )

        fun openSurvey(surveyId: Long) {
            surveyIdFlow.value = surveyId
            claimRepository.setSurveyId(surveyId)
            loadInitial()
        }

        fun onCandidateTap(candidateId: Long) {
            val source = sourceStateFlow.value ?: return
            val candidate =
                allCandidates(source).firstOrNull { it.inferenceResultId == candidateId }
                    ?: return
            when {
                candidate.claimedByMe -> releaseCandidates(listOf(candidateId))
                candidate.claimedByOther -> emitMessage("This candidate is claimed by another user")
                else -> claimCandidates(listOf(candidateId))
            }
        }

        fun startPolygon() {
            polygonVerticesFlow.value = emptyList()
        }

        fun onPolygonVertex(point: GeoCoordinate) {
            val current = polygonVerticesFlow.value ?: return
            polygonVerticesFlow.value = current + point
        }

        fun commitPolygon() {
            val vertices = polygonVerticesFlow.value ?: return
            if (vertices.size < 3) {
                emitMessage("At least 3 vertices are required to form a polygon")
                return
            }
            val source = sourceStateFlow.value ?: return
            val contained =
                allCandidates(source).filter { candidate ->
                    candidate.geoCentroid?.let { isPointInPolygon(it, vertices) } == true
                }
            val eligible =
                contained.filter { !it.claimedByMe && !it.claimedByOther }
            polygonVerticesFlow.value = null
            if (eligible.isEmpty()) {
                emitMessage("No unclaimed candidates inside the drawn region")
                return
            }
            if (eligible.size > MAX_BATCH_IDS) {
                emitMessage("Selected region contains more than $MAX_BATCH_IDS candidates")
                return
            }
            claimCandidates(eligible.map { it.inferenceResultId })
        }

        fun cancelPolygon() {
            polygonVerticesFlow.value = null
        }

        fun setMineFilter(mine: Boolean) {
            mineOnlyFlow.value = mine
        }

        fun releaseClaim(inferenceResultId: Long) {
            if (claimsFlow.value.none {
                    it.inferenceResultId == inferenceResultId && it.isMe
                }
            ) {
                emitMessage("Only your own claims can be released")
                return
            }
            releaseCandidates(listOf(inferenceResultId))
        }

        fun refresh() {
            val surveyId = surveyIdFlow.value ?: return
            viewModelScope.launch {
                isRefreshingFlow.value = true
                when (val result = stage4Repository.getCandidatesState(surveyId)) {
                    is Stage4FetchResult.Success -> sourceStateFlow.value = result.state
                    is Stage4FetchResult.Error ->
                        emitMessage(result.message ?: "Failed to refresh candidates")

                    is Stage4FetchResult.NetworkError ->
                        emitMessage("Network error during refresh")

                    is Stage4FetchResult.AuthExpired -> authExpiredFlow.value = true
                }
                loadClaims()
                isRefreshingFlow.value = false
            }
        }

        fun userMessageShown() {
            userMessageFlow.value = null
        }

        private fun loadInitial() {
            viewModelScope.launch {
                sourceStateFlow.value = null
                errorFlow.value = null
                authExpiredFlow.value = false
                claimsFlow.value = emptyList()
                polygonVerticesFlow.value = null
                val surveyId = surveyIdFlow.value ?: return@launch
                when (val result = stage4Repository.getCandidatesState(surveyId)) {
                    is Stage4FetchResult.Success -> {
                        sourceStateFlow.value = result.state
                        if (result.isOffline) {
                            userMessageFlow.value = "Offline — displaying previous map data"
                        }
                    }

                    is Stage4FetchResult.Error ->
                        errorFlow.value =
                            result.message ?: "Failed to load survey candidates"

                    is Stage4FetchResult.NetworkError ->
                        errorFlow.value = "Network error. Please check your connection."

                    is Stage4FetchResult.AuthExpired -> authExpiredFlow.value = true
                }
                loadClaims()
            }
        }

        private suspend fun loadClaims() {
            when (val result = claimRepository.listClaims()) {
                is ClaimResult.Listed -> claimsFlow.value = result.claims
                is ClaimResult.AuthExpired -> authExpiredFlow.value = true
                is ClaimResult.NetworkError -> emitMessage("Network error loading claims")
                is ClaimResult.Error ->
                    emitMessage(result.message ?: "Failed to load claims")

                else -> Unit
            }
        }

        private fun claimCandidates(ids: List<Long>) {
            if (ids.isEmpty()) return
            viewModelScope.launch {
                when (val result = claimRepository.claim(ids)) {
                    is ClaimResult.Claimed -> {
                        emitMessage(
                            if (result.alreadyClaimed.isNotEmpty()) {
                                "Claimed ${result.claimed.size}; " +
                                    "${result.alreadyClaimed.size} already claimed"
                            } else {
                                "Claimed ${result.claimed.size} candidate(s)"
                            },
                        )
                        loadClaims()
                    }

                    is ClaimResult.AuthExpired -> authExpiredFlow.value = true
                    is ClaimResult.NetworkError -> emitMessage("Network error during claim")
                    is ClaimResult.Error ->
                        emitMessage(result.message ?: "Claim failed")

                    else -> Unit
                }
            }
        }

        private fun releaseCandidates(ids: List<Long>) {
            if (ids.isEmpty()) return
            viewModelScope.launch {
                when (val result = claimRepository.release(ids)) {
                    is ClaimResult.Released -> {
                        emitMessage("Released ${result.released.size} candidate(s)")
                        loadClaims()
                    }

                    is ClaimResult.AuthExpired -> authExpiredFlow.value = true
                    is ClaimResult.NetworkError -> emitMessage("Network error during release")
                    is ClaimResult.Error ->
                        emitMessage(result.message ?: "Release failed")

                    else -> Unit
                }
            }
        }

        private fun emitMessage(message: String) {
            _events.tryEmit(BasecampEvent.ShowMessage(message))
        }

        private fun mergeClaims(
            state: Stage4State,
            claims: List<Claim>,
        ): Stage4State {
            val claimMap = claims.associateBy { it.inferenceResultId }

            fun apply(candidate: Stage4Candidate): Stage4Candidate {
                val claim = claimMap[candidate.inferenceResultId] ?: return candidate
                return candidate.copy(
                    claimedByMe = claim.isMe,
                    claimedByOther = !claim.isMe,
                )
            }
            return state.copy(
                unprocessedCandidates = state.unprocessedCandidates.map(::apply),
                yesMeteorites = state.yesMeteorites.map(::apply),
                noMeteorites = state.noMeteorites.map(::apply),
            )
        }

        private fun allCandidates(state: Stage4State): List<Stage4Candidate> =
            state.unprocessedCandidates + state.yesMeteorites + state.noMeteorites
    }

internal fun isPointInPolygon(
    point: GeoCoordinate,
    polygon: List<GeoCoordinate>,
): Boolean {
    if (polygon.size < 3) return false
    val px = point.longitude
    val py = point.latitude
    val n = polygon.size
    val eps = 1e-10

    for (i in 0 until n) {
        val p1 = polygon[i]
        val p2 = polygon[(i + 1) % n]
        if (isPointOnSegment(px, py, p1, p2, eps)) return true
    }

    var inside = false
    var j = n - 1
    for (i in 0 until n) {
        val xi = polygon[i].longitude
        val yi = polygon[i].latitude
        val xj = polygon[j].longitude
        val yj = polygon[j].latitude
        val intersect =
            (yi > py) != (yj > py) &&
                px < (xj - xi) * (py - yi) / (yj - yi) + xi
        if (intersect) inside = !inside
        j = i
    }
    return inside
}

private fun isPointOnSegment(
    px: Double,
    py: Double,
    p1: GeoCoordinate,
    p2: GeoCoordinate,
    eps: Double,
): Boolean {
    if (Math.abs(p1.longitude - px) < eps && Math.abs(p1.latitude - py) < eps) return true
    val minX = Math.min(p1.longitude, p2.longitude) - eps
    val maxX = Math.max(p1.longitude, p2.longitude) + eps
    val minY = Math.min(p1.latitude, p2.latitude) - eps
    val maxY = Math.max(p1.latitude, p2.latitude) + eps
    if (px < minX || px > maxX || py < minY || py > maxY) return false
    val cross =
        (px - p1.longitude) * (p2.latitude - p1.latitude) -
            (py - p1.latitude) * (p2.longitude - p1.longitude)
    return Math.abs(cross) < 1e-12
}
