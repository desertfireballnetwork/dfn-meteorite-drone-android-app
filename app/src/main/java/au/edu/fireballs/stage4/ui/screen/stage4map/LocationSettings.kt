package au.edu.fireballs.stage4.ui.screen.stage4map

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

internal fun openAppSettings(context: Context) {
    startGuardedIntent(
        context,
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ),
    )
}

internal fun openLocationSettings(context: Context) {
    startGuardedIntent(context, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
}

private fun startGuardedIntent(
    context: Context,
    intent: Intent,
) {
    if (intent.resolveActivity(context.packageManager) != null) {
        context.startActivity(intent)
    }
}
