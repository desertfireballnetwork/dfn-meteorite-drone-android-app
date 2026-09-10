package au.edu.fireballs.stage4.ui.screen.surveylist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import au.edu.fireballs.stage4.R
import au.edu.fireballs.stage4.domain.model.Survey
import au.edu.fireballs.stage4.ui.theme.Stage4Theme
import au.edu.fireballs.stage4.ui.util.RelativeTimeFormatter
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurveyListScreen(
    onSurveySelected: (Long) -> Unit,
    onBasecampSelected: (Long) -> Unit,
    onAuthExpired: () -> Unit,
    viewModel: SurveyListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.loadSurveys()
        onPauseOrDispose {
            // May be later
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notReadyMessage = stringResource(R.string.survey_stage4_not_ready)

    LaunchedEffect(uiState) {
        if (uiState is SurveyListUiState.AuthExpired) {
            onAuthExpired()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.surveys_title)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) {
            when (val state = uiState) {
                is SurveyListUiState.Loading -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(text = stringResource(R.string.surveys_loading))
                    }
                }

                is SurveyListUiState.Loaded -> {
                    LaunchedEffect(state.userMessage) {
                        state.userMessage?.let { message ->
                            snackbarHostState.showSnackbar(message)
                            viewModel.userMessageShown()
                        }
                    }

                    PullToRefreshBox(
                        isRefreshing = state.isRefreshing,
                        onRefresh = { viewModel.loadSurveys(isPullToRefresh = true) },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(16.dp),
                        ) {
                            items(
                                items = state.surveys,
                                key = { it.id },
                            ) { survey ->
                                SurveyListItem(
                                    survey = survey,
                                    onSurveyClick = {
                                        if (survey.hasStage4) {
                                            onSurveySelected(survey.id)
                                        } else {
                                            scope.launch {
                                                snackbarHostState.showSnackbar(notReadyMessage)
                                            }
                                        }
                                    },
                                    onBasecampClick = {
                                        if (survey.hasStage4) {
                                            onBasecampSelected(survey.id)
                                        } else {
                                            scope.launch {
                                                snackbarHostState.showSnackbar(notReadyMessage)
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                is SurveyListUiState.Empty -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.surveys_empty),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { viewModel.loadSurveys() }) {
                            Text(text = stringResource(R.string.retry))
                        }
                    }
                }

                is SurveyListUiState.Error -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { viewModel.loadSurveys() }) {
                            Text(text = stringResource(R.string.retry))
                        }
                    }
                }

                is SurveyListUiState.AuthExpired -> {
                    // Handled via LaunchedEffect navigation callback
                }
            }
        }
    }
}

@Composable
fun SurveyListItem(
    survey: Survey,
    onSurveyClick: () -> Unit,
    onBasecampClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val alpha = if (survey.hasStage4) 1.0f else 0.5f

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(enabled = true, onClick = onSurveyClick),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha),
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = survey.eventId,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (survey.isActive) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = stringResource(R.string.survey_active_badge),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = RelativeTimeFormatter.formatRelativeTime(survey.createdIso),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }

            if (survey.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = survey.description,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color =
                        if (survey.hasStage4) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                        },
                ) {
                    Text(
                        text =
                            if (survey.hasStage4) {
                                stringResource(R.string.survey_stage4_ready)
                            } else {
                                stringResource(R.string.survey_stage4_not_ready)
                            },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            if (survey.hasStage4) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            },
                    )
                }

                if (survey.hasStage4) {
                    Button(
                        onClick = onBasecampClick,
                        modifier = Modifier.height(32.dp),
                    ) {
                        Text(text = "Basecamp")
                    }
                }
            }
        }
    }
}

// =========
// PREVIEWS
// =========

@Preview(name = "Item - Stage 4 Ready & Active", showBackground = true)
@Composable
private fun SurveyListItemActivePreview() {
    Stage4Theme {
        SurveyListItem(
            survey =
                Survey(
                    id = 1L,
                    eventId = "DN240703-02",
                    description = "Murchison search April 2026",
                    createdIso = "2026-04-08T10:15:00Z",
                    hasStage4 = true,
                    isActive = true,
                ),
            onSurveyClick = {},
            onBasecampClick = {},
        )
    }
}

@Preview(name = "Item - Very Long Name (Truncated)", showBackground = true)
@Composable
private fun SurveyListItemLongTextPreview() {
    Stage4Theme {
        SurveyListItem(
            survey =
                Survey(
                    id = 2L,
                    eventId = "DN240703-02-EXTREMELY-LONG-EVENT-NAME-TESTING-LAYOUT-CONSTRAINTS",
                    description = "Detailed search grid targeting high density fragmentation zone.",
                    createdIso = "2026-03-01T08:00:00Z",
                    hasStage4 = true,
                    isActive = false,
                ),
            onSurveyClick = {},
            onBasecampClick = {},
        )
    }
}

@Preview(name = "Item - Stage 4 Not Ready", showBackground = true)
@Composable
private fun SurveyListItemNotReadyPreview() {
    Stage4Theme {
        SurveyListItem(
            survey =
                Survey(
                    id = 3L,
                    eventId = "DN240812-01",
                    description = "Preliminary candidate search",
                    createdIso = "2026-08-10T12:00:00Z",
                    hasStage4 = false,
                    isActive = false,
                ),
            onSurveyClick = {},
            onBasecampClick = {},
        )
    }
}

@Preview(name = "Full List - Loaded State", showBackground = true, heightDp = 600)
@Composable
private fun SurveyListLoadedPreview() {
    val sampleSurveys =
        listOf(
            Survey(
                id = 1L,
                eventId = "DN240703-02",
                description = "Murchison search April 2026",
                createdIso = "2026-04-08T10:15:00Z",
                hasStage4 = true,
                isActive = true,
            ),
            Survey(
                id = 2L,
                eventId = "DN240801-01",
                description = "Kalgoorlie field search",
                createdIso = "2026-08-01T09:30:00Z",
                hasStage4 = true,
                isActive = false,
            ),
            Survey(
                id = 3L,
                eventId = "DN240810-03",
                description = "Mundrabilla preliminary scan",
                createdIso = "2026-08-10T15:45:00Z",
                hasStage4 = false,
                isActive = false,
            ),
        )

    Stage4Theme {
        Surface {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                items(sampleSurveys, key = { it.id }) { survey ->
                    SurveyListItem(
                        survey = survey,
                        onSurveyClick = {},
                        onBasecampClick = {},
                    )
                }
            }
        }
    }
}
