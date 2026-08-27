package au.edu.fireballs.stage4.ui.screen.stage4map

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal const val OVERLAY_PADDING_DP = 12

@Composable
internal fun MapTopOverlay(
    loaded: Stage4MapUiState.Loaded,
    locationPermission: LocationPermissionUiState,
    locating: Boolean,
    locationMessage: String?,
    locationServicesDisabled: Boolean,
    onDismissMessage: () -> Unit,
    onOpenLocationSettings: () -> Unit,
) {
    val context = LocalContext.current

    Column(
        modifier =
            Modifier
                .statusBarsPadding()
                .padding(OVERLAY_PADDING_DP.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SurveyInfoChip(loaded = loaded)
        if (locating) {
            Spacer(modifier = Modifier.height(8.dp))
            LocatingChip()
        }
        locationMessage?.let {
            Spacer(modifier = Modifier.height(8.dp))
            if (locationServicesDisabled) {
                LocationServicesDisabledChip(
                    onOpenSettings = onOpenLocationSettings,
                    onDismiss = onDismissMessage,
                )
            } else {
                LocationMessageChip(message = it, onDismiss = onDismissMessage)
            }
        }
        if (locationPermission.showDeniedNotice) {
            Spacer(modifier = Modifier.height(8.dp))
            LocationDeniedNotice(
                status = locationPermission.status,
                onRequestAgain = locationPermission.requestPermissions,
                onOpenSettings = { openAppSettings(context) },
                onDismiss = locationPermission.dismissDeniedNotice,
            )
        }
    }
}

@Composable
private fun SurveyInfoChip(loaded: Stage4MapUiState.Loaded) {
    val state = loaded.state
    val totalCandidates =
        state.unprocessedCandidates.size + state.yesMeteorites.size + state.noMeteorites.size

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = state.survey.eventId,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "$totalCandidates candidates loaded",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun LocatingChip() {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Locating...",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun LocationMessageChip(
    message: String,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        tonalElevation = 2.dp,
        onClick = onDismiss,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun LocationServicesDisabledChip(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = LOCATION_SERVICES_DISABLED_MESSAGE,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(modifier = Modifier.width(4.dp))
            TextButton(onClick = onOpenSettings) {
                Text(text = "Open settings")
            }
            IconButton(onClick = onDismiss) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = "Dismiss")
            }
        }
    }
}

@Composable
private fun LocationDeniedNotice(
    status: LocationPermissionStatus,
    onRequestAgain: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = deniedNoticeText(status),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            DeniedNoticeActions(
                status = status,
                onRequestAgain = onRequestAgain,
                onOpenSettings = onOpenSettings,
            )
            IconButton(onClick = onDismiss) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = "Dismiss")
            }
        }
    }
}

@Composable
private fun DeniedNoticeActions(
    status: LocationPermissionStatus,
    onRequestAgain: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    when (status) {
        LocationPermissionStatus.DENIED_RATIONALE_AVAILABLE,
        LocationPermissionStatus.REVOKED,
        -> {
            Spacer(modifier = Modifier.width(4.dp))
            TextButton(onClick = onRequestAgain) {
                Text(text = "Allow")
            }
        }

        LocationPermissionStatus.DENIED_PERMANENT -> {
            Spacer(modifier = Modifier.width(4.dp))
            TextButton(onClick = onOpenSettings) {
                Text(text = "Open settings")
            }
        }

        LocationPermissionStatus.GRANTED,
        LocationPermissionStatus.COARSE_ONLY,
        LocationPermissionStatus.NEVER_REQUESTED,
        -> Unit
    }
}

private fun deniedNoticeText(status: LocationPermissionStatus): String =
    when (status) {
        LocationPermissionStatus.DENIED_PERMANENT -> "Location permission disabled"
        LocationPermissionStatus.REVOKED -> "Location permission was revoked"
        else -> "Location permission declined"
    }
