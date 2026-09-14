package au.edu.fireballs.stage4

import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityNavigationTest {
    @Test
    fun `pending sync routes to sync when a survey is selected`() {
        assertEquals("sync", pendingSyncRoute(7L))
    }

    @Test
    fun `pending sync routes to map when no survey is selected`() {
        assertEquals("map", pendingSyncRoute(null))
    }
}
