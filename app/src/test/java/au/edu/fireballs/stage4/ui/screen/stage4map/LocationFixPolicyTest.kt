package au.edu.fireballs.stage4.ui.screen.stage4map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationFixPolicyTest {
    private val now = 1_000_000L

    @Test
    fun `null last fix requests fresh location`() {
        assertEquals(
            LocationFixSource.REQUEST_FRESH,
            chooseLocationSource(lastFixTimeMs = null, nowMs = now),
        )
    }

    @Test
    fun `fresh last fix uses cache`() {
        assertEquals(
            LocationFixSource.CACHE,
            chooseLocationSource(lastFixTimeMs = now - 30_000L, nowMs = now),
        )
    }

    @Test
    fun `fix exactly at max age uses cache`() {
        assertEquals(
            LocationFixSource.CACHE,
            chooseLocationSource(lastFixTimeMs = now - MAX_LOCATION_AGE_MS, nowMs = now),
        )
    }

    @Test
    fun `stale last fix requests fresh location`() {
        assertEquals(
            LocationFixSource.REQUEST_FRESH,
            chooseLocationSource(lastFixTimeMs = now - MAX_LOCATION_AGE_MS - 1L, nowMs = now),
        )
    }

    @Test
    fun `fix accuracy just under threshold is usable`() {
        assertTrue(
            isLocationFixUsable(
                latitude = -31.96,
                longitude = 116.35,
                accuracyMeters = MAX_FIX_ACCURACY_METERS.toFloat() - 0.1f,
            ),
        )
    }

    @Test
    fun `fix accuracy at threshold is usable`() {
        assertTrue(
            isLocationFixUsable(
                latitude = -31.96,
                longitude = 116.35,
                accuracyMeters = MAX_FIX_ACCURACY_METERS.toFloat(),
            ),
        )
    }

    @Test
    fun `fix accuracy just over threshold is rejected`() {
        assertFalse(
            isLocationFixUsable(
                latitude = -31.96,
                longitude = 116.35,
                accuracyMeters = MAX_FIX_ACCURACY_METERS.toFloat() + 0.1f,
            ),
        )
    }

    @Test
    fun `fix without accuracy is rejected`() {
        assertFalse(
            isLocationFixUsable(
                latitude = -31.96,
                longitude = 116.35,
                accuracyMeters = null,
            ),
        )
    }

    @Test
    fun `fix with negative accuracy is rejected`() {
        assertFalse(
            isLocationFixUsable(
                latitude = -31.96,
                longitude = 116.35,
                accuracyMeters = -1f,
            ),
        )
    }

    @Test
    fun `fix with NaN accuracy is rejected`() {
        assertFalse(
            isLocationFixUsable(
                latitude = -31.96,
                longitude = 116.35,
                accuracyMeters = Float.NaN,
            ),
        )
    }

    @Test
    fun `latitude above range is rejected`() {
        assertFalse(
            isLocationFixUsable(
                latitude = 90.1,
                longitude = 0.0,
                accuracyMeters = 10f,
            ),
        )
    }

    @Test
    fun `latitude boundary values are valid`() {
        assertTrue(isCoordinateValid(latitude = -90.0, longitude = 0.0))
        assertTrue(isCoordinateValid(latitude = 90.0, longitude = 0.0))
    }

    @Test
    fun `longitude boundary values are valid`() {
        assertTrue(isCoordinateValid(latitude = 0.0, longitude = -180.0))
        assertTrue(isCoordinateValid(latitude = 0.0, longitude = 180.0))
    }

    @Test
    fun `longitude out of range is rejected`() {
        assertFalse(
            isLocationFixUsable(
                latitude = 0.0,
                longitude = 180.1,
                accuracyMeters = 10f,
            ),
        )
    }

    @Test
    fun `NaN coordinate is rejected`() {
        assertFalse(isCoordinateValid(latitude = Double.NaN, longitude = 0.0))
        assertFalse(isCoordinateValid(latitude = 0.0, longitude = Double.NaN))
    }

    @Test
    fun `infinite coordinate is rejected`() {
        assertFalse(isCoordinateValid(latitude = Double.POSITIVE_INFINITY, longitude = 0.0))
        assertFalse(isCoordinateValid(latitude = 0.0, longitude = Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `no location provider enabled reports disabled`() {
        assertFalse(anyLocationProviderEnabled(gpsEnabled = false, networkEnabled = false))
    }

    @Test
    fun `gps provider alone reports enabled`() {
        assertTrue(anyLocationProviderEnabled(gpsEnabled = true, networkEnabled = false))
    }

    @Test
    fun `network provider alone reports enabled`() {
        assertTrue(anyLocationProviderEnabled(gpsEnabled = false, networkEnabled = true))
    }

    @Test
    fun `both providers enabled reports enabled`() {
        assertTrue(anyLocationProviderEnabled(gpsEnabled = true, networkEnabled = true))
    }
}
