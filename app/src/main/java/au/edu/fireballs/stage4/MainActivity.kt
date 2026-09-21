package au.edu.fireballs.stage4

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import au.edu.fireballs.stage4.ui.PendingSyncBadge
import au.edu.fireballs.stage4.ui.screen.basecamp.BasecampScreen
import au.edu.fireballs.stage4.ui.screen.dataentry.SettingsScreen
import au.edu.fireballs.stage4.ui.screen.login.LoginScreen
import au.edu.fireballs.stage4.ui.screen.login.LoginViewModel
import au.edu.fireballs.stage4.ui.screen.stage4map.Stage4MapScreen
import au.edu.fireballs.stage4.ui.screen.surveylist.SurveyListScreen
import au.edu.fireballs.stage4.ui.screen.sync.SyncScreen
import au.edu.fireballs.stage4.ui.screen.sync.SyncViewModel
import au.edu.fireballs.stage4.ui.session.SessionExpiredBus
import au.edu.fireballs.stage4.ui.session.SessionExpiredHandler
import au.edu.fireballs.stage4.ui.sync.PendingSyncSnackbar
import au.edu.fireballs.stage4.ui.theme.Stage4Theme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    @Inject
    lateinit var sessionExpiredBus: SessionExpiredBus

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            Stage4Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val authState by viewModel.authState.collectAsStateWithLifecycle()
                    val selectedSurveyId by viewModel.selectedSurveyId.collectAsStateWithLifecycle()

                    when (authState) {
                        is AuthState.Loading -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                        }

                        is AuthState.Resolved -> {
                            val startDestination =
                                if (!(authState as AuthState.Resolved).isSignedIn) {
                                    "login"
                                } else if (selectedSurveyId == null) {
                                    "surveys"
                                } else {
                                    "map"
                                }

                            val navController = rememberNavController()

                            SessionExpiredHandler(
                                events = sessionExpiredBus.events,
                                onSignIn = {
                                    navController.navigate("login") {
                                        popUpTo(0) { inclusive = true }
                                    }
                                },
                            )

                            MainScaffold(
                                navController = navController,
                                startDestination = startDestination,
                                viewModel = viewModel,
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val topLevelDestinations =
    listOf(
        TopLevelDestination("surveys", "Surveys", Icons.AutoMirrored.Filled.List),
        TopLevelDestination("map", "Map", Icons.Filled.Map),
        TopLevelDestination("sync", "Sync", Icons.Filled.Sync),
        TopLevelDestination("basecamp", "Basecamp", Icons.Outlined.Home),
        TopLevelDestination("settings", "Settings", Icons.Filled.Settings),
    )

@Composable
private fun NoSurveySelectedPlaceholder(navController: NavHostController) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "No survey selected", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = {
            navController.navigate("surveys") {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }) {
            Text(text = "Go to Surveys")
        }
    }
}

internal fun pendingSyncRoute(selectedSurveyId: Long?): String =
    if (selectedSurveyId != null) "sync" else "map"

internal const val FAILED_SYNC_ROUTE = "sync"

internal fun globalSyncRoute(
    surfaceState: GlobalSyncState,
    selectedSurveyId: Long?,
): String =
    if (surfaceState is GlobalSyncState.Failed) {
        FAILED_SYNC_ROUTE
    } else {
        pendingSyncRoute(selectedSurveyId)
    }

private const val COMPLETION_DISPLAY_MS = 4_000L

@Composable
private fun MainScaffold(
    navController: NavHostController,
    startDestination: String,
    viewModel: MainViewModel,
) {
    val selectedSurveyId by
        viewModel.selectedSurveyId.collectAsStateWithLifecycle(initialValue = null)
    val globalSyncState by viewModel.globalSyncState.collectAsStateWithLifecycle()

    val pendingBadgeCount =
        (globalSyncState as? GlobalSyncState.Pending)?.let { it.decisions + it.photos } ?: 0

    var completionVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.syncCompletionEvents.collect {
            completionVisible = true
        }
    }
    LaunchedEffect(completionVisible) {
        if (completionVisible) {
            delay(COMPLETION_DISPLAY_MS)
            completionVisible = false
        }
    }
    val surfaceState =
        if (completionVisible) GlobalSyncState.Complete else globalSyncState

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val showBottomBar =
        topLevelDestinations.any { destination ->
            currentDestination?.hierarchy?.any { it.route == destination.route } == true
        }

    val openSync = {
        navController.navigate(globalSyncRoute(surfaceState, selectedSurveyId)) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
        Unit
    }

    Scaffold(
        contentWindowInsets =
            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        bottomBar = {
            if (showBottomBar) {
                Column {
                    PendingSyncBadge(
                        state = surfaceState,
                        onAction = openSync,
                    )
                    PendingSyncSnackbar(
                        state = surfaceState,
                        onAction = openSync,
                    )
                    NavigationBar {
                        topLevelDestinations.forEach { destination ->
                            val selected =
                                currentDestination?.hierarchy?.any {
                                    it.route == destination.route
                                } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(destination.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = destination.icon,
                                        contentDescription = destination.label,
                                    )
                                },
                                label = { Text(destination.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable("login") {
                val loginViewModel: LoginViewModel = hiltViewModel()
                LoginScreen(
                    viewModel = loginViewModel,
                    onLoginSuccess = {
                        viewModel.resumeSyncIfNeeded()
                        navController.navigate("surveys") {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                )
            }

            composable("surveys") {
                SurveyListScreen(
                    onSurveySelected = { id ->
                        viewModel.setSelectedSurvey(id)
                        navController.navigate("map") {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onBasecampSelected = { id ->
                        viewModel.setSelectedSurvey(id)
                        navController.navigate("basecamp") {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onAuthExpired = {
                        navController.navigate("login") {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                )
            }

            composable("map") {
                val surveyId = selectedSurveyId
                if (surveyId != null) {
                    Stage4MapScreen(
                        surveyId = surveyId,
                        onAuthExpired = {
                            navController.navigate("login") {
                                popUpTo(0) { inclusive = true }
                            }
                        },
                        onOpenDownloads = {
                            navController.navigate("basecamp") {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        pendingCount = pendingBadgeCount,
                    )
                } else {
                    NoSurveySelectedPlaceholder(navController)
                }
            }

            composable("sync") {
                val syncViewModel: SyncViewModel = hiltViewModel()
                val syncUiState by syncViewModel.uiState.collectAsStateWithLifecycle()
                SyncScreen(
                    uiState = syncUiState,
                    onSyncNow = { syncViewModel.syncNow() },
                    onDeleteDecision = { syncViewModel.deleteDecision(it) },
                    onDeletePhoto = { syncViewModel.deletePhoto(it) },
                    onSignIn = {
                        navController.navigate("login") {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                )
            }

            composable("basecamp") {
                val surveyId = selectedSurveyId
                if (surveyId != null) {
                    BasecampScreen(
                        surveyId = surveyId,
                        onAuthExpired = {
                            navController.navigate("login") {
                                popUpTo(0) { inclusive = true }
                            }
                        },
                    )
                } else {
                    NoSurveySelectedPlaceholder(navController)
                }
            }

            composable("settings") {
                SettingsScreen()
            }
        }
    }
}
