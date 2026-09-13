package au.edu.fireballs.stage4.ui.session

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.flow.SharedFlow

@Composable
fun SessionExpiredHandler(
    events: SharedFlow<Unit>,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(events) {
        events.collect {
            if (!visible) {
                visible = true
            }
        }
    }

    if (visible) {
        AlertDialog(
            onDismissRequest = {},
            title = {
                Text(
                    text = "Session expired",
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            text = {
                Text(
                    text = "Your session has expired. Please sign in again.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        visible = false
                        onSignIn()
                    },
                    modifier = Modifier.testTag("session-expired-sign-in"),
                ) {
                    Text("Sign in")
                }
            },
            modifier = modifier,
            properties =
                DialogProperties(
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                ),
        )
    }
}
