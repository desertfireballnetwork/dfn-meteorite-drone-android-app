package au.edu.fireballs.stage4.ui.screen.stage4map

import org.junit.Assert.assertEquals
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
}
