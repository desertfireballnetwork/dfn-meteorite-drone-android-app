package au.edu.fireballs.stage4.ui.screen.stage4map

import com.mapbox.maps.plugin.PuckBearing
import com.mapbox.maps.plugin.locationcomponent.createDefault2DPuck
import com.mapbox.maps.plugin.locationcomponent.generated.LocationComponentSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UserLocationDisplayTest {
    @Test
    fun `location enabled shows puck heading and accuracy`() {
        val settings = buildSettings(enabled = true, showAccuracyRing = true)

        assertTrue(settings.enabled)
        assertTrue(settings.pulsingEnabled)
        assertTrue(settings.showAccuracyRing)
        assertTrue(settings.puckBearingEnabled)
        assertEquals(PuckBearing.HEADING, settings.puckBearing)
    }

    @Test
    fun `location disabled hides puck heading and accuracy`() {
        val settings = buildSettings(enabled = false, showAccuracyRing = false)

        assertFalse(settings.enabled)
        assertFalse(settings.pulsingEnabled)
        assertFalse(settings.showAccuracyRing)
        assertFalse(settings.puckBearingEnabled)
    }

    private fun buildSettings(
        enabled: Boolean,
        showAccuracyRing: Boolean,
    ): LocationComponentSettings =
        LocationComponentSettings
            .Builder(createDefault2DPuck(withBearing = false))
            .apply {
                applyUserLocationDisplay(
                    enabled = enabled,
                    showAccuracyRing = showAccuracyRing,
                )
            }.build()
}
