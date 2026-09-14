package au.edu.fireballs.stage4.ui.screen.stage4map

import au.edu.fireballs.stage4.data.repository.NetworkState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage4MapScreenGateTest {
    @Test
    fun `allows selection when offline bundle exists`() {
        assertTrue(
            shouldAllowCandidateSelection(
                hasOfflineBundle = true,
                networkState = NetworkState.Offline,
            ),
        )
    }

    @Test
    fun `allows selection when online without bundle`() {
        assertTrue(
            shouldAllowCandidateSelection(
                hasOfflineBundle = false,
                networkState = NetworkState.Online,
            ),
        )
    }

    @Test
    fun `allows selection when online with bundle`() {
        assertTrue(
            shouldAllowCandidateSelection(
                hasOfflineBundle = true,
                networkState = NetworkState.Online,
            ),
        )
    }

    @Test
    fun `blocks selection when offline without bundle`() {
        assertFalse(
            shouldAllowCandidateSelection(
                hasOfflineBundle = false,
                networkState = NetworkState.Offline,
            ),
        )
    }
}
