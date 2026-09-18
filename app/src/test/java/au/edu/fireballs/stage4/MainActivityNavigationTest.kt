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

    @Test
    fun `failed global surface routes to the sync tab`() {
        assertEquals("sync", globalSyncRoute(GlobalSyncState.Failed, selectedSurveyId = null))
        assertEquals("sync", globalSyncRoute(GlobalSyncState.Failed, selectedSurveyId = 7L))
    }

    @Test
    fun `non-failed global surface routes by selected survey`() {
        assertEquals("sync", globalSyncRoute(GlobalSyncState.Pending(1, 0), selectedSurveyId = 7L))
        assertEquals(
            "map",
            globalSyncRoute(GlobalSyncState.WaitingForNetwork, selectedSurveyId = null),
        )
    }
}
