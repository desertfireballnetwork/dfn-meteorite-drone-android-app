package au.edu.fireballs.stage4

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import au.edu.fireballs.stage4.ui.screen.login.LoginScreen
import au.edu.fireballs.stage4.ui.screen.login.LoginViewModel
import au.edu.fireballs.stage4.ui.theme.Stage4Theme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

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

                    when (authState) {
                        is AuthState.Loading -> {
                            // Neutral splash/loading screen during signing in
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                        }

                        is AuthState.Resolved -> {
                            val startDestination =
                                if ((authState as AuthState.Resolved).isSignedIn) {
                                    "surveys"
                                } else {
                                    "login"
                                }

                            val navController = rememberNavController()

                            NavHost(
                                navController = navController,
                                startDestination = startDestination,
                            ) {
                                composable("login") {
                                    val loginViewModel: LoginViewModel = hiltViewModel()
                                    LoginScreen(
                                        viewModel = loginViewModel,
                                        onLoginSuccess = {
                                            navController.navigate("surveys") {
                                                popUpTo("login") { inclusive = true }
                                            }
                                        },
                                    )
                                }

                                composable("surveys") {
                                    // Placeholder screen for Survey Picker (A-5)
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = "Survey Picker Placeholder (A-5)",
                                            style = MaterialTheme.typography.titleLarge,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
