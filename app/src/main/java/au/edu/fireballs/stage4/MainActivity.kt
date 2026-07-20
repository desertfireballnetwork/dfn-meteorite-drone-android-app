package au.edu.fireballs.stage4

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import au.edu.fireballs.stage4.ui.Stage4RootScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Stage4RootScreen()
        }
    }
}
