package au.edu.fireballs.stage4.ui.screen.candidate

import com.mapbox.maps.MapView
import com.mapbox.maps.plugin.Plugin
import com.mapbox.maps.plugin.locationcomponent.LocationComponentPlugin

internal data class CandidateLocationDisplay(
    val enabled: Boolean,
    val pulsingEnabled: Boolean,
    val showAccuracyRing: Boolean,
)

internal fun candidateLocationDisplay(permissionGranted: Boolean): CandidateLocationDisplay =
    CandidateLocationDisplay(
        enabled = permissionGranted,
        pulsingEnabled = permissionGranted,
        showAccuracyRing = permissionGranted,
    )

internal fun setCandidateLocationPuckEnabled(
    mapView: MapView,
    permissionGranted: Boolean,
) {
    val display = candidateLocationDisplay(permissionGranted)
    val locationPlugin =
        mapView.getPlugin(Plugin.MAPBOX_LOCATION_COMPONENT_PLUGIN_ID) as? LocationComponentPlugin
    locationPlugin?.updateSettings {
        enabled = display.enabled
        pulsingEnabled = display.pulsingEnabled
        showAccuracyRing = display.showAccuracyRing
    }
}
