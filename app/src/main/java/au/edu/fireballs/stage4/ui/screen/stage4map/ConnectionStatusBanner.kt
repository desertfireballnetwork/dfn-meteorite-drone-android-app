package au.edu.fireballs.stage4.ui.screen.stage4map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal val BANNER_ONLINE_COLOR = Color(0xFF2E7D32)
internal val BANNER_ONLINE_CONTENT_COLOR = Color(0xFFFFFFFF)
internal val BANNER_OFFLINE_COLOR = Color(0xFFC62828)
internal val BANNER_OFFLINE_CONTENT_COLOR = Color(0xFFFFFFFF)
internal val BANNER_DOWNLOADING_COLOR = Color(0xFFF9A825)
internal val BANNER_DOWNLOADING_CONTENT_COLOR = Color(0xFF000000)

@Composable
internal fun ConnectionStatusBanner(
    state: ConnectionBannerState,
    modifier: Modifier = Modifier,
) {
    val (backgroundColor, contentColor, label) =
        when (state) {
            ConnectionBannerState.Online ->
                Triple(BANNER_ONLINE_COLOR, BANNER_ONLINE_CONTENT_COLOR, "Online")

            ConnectionBannerState.Offline ->
                Triple(BANNER_OFFLINE_COLOR, BANNER_OFFLINE_CONTENT_COLOR, "Offline mode")

            ConnectionBannerState.Downloading ->
                Triple(
                    BANNER_DOWNLOADING_COLOR,
                    BANNER_DOWNLOADING_CONTENT_COLOR,
                    "Downloading…",
                )
        }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = backgroundColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (state == ConnectionBannerState.Downloading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = contentColor,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
            )
        }
    }
}
