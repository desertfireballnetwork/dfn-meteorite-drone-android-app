package au.edu.fireballs.stage4.ui.screen.stage4map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationPermissionPolicyTest {
    @Test
    fun `first open with no permissions auto-launches request`() {
        val decision =
            decideLocationPermission(
                fineGranted = false,
                coarseGranted = false,
                hadRequestedBefore = false,
                shouldShowRationale = false,
                wasGrantedPreviously = false,
            )

        assertEquals(LocationPermissionStatus.NEVER_REQUESTED, decision.status)
        assertTrue(decision.shouldRequestNow)
    }

    @Test
    fun `already granted never auto-launches`() {
        val decision =
            decideLocationPermission(
                fineGranted = true,
                coarseGranted = false,
                hadRequestedBefore = false,
                shouldShowRationale = false,
                wasGrantedPreviously = false,
            )

        assertEquals(LocationPermissionStatus.GRANTED, decision.status)
        assertFalse(decision.shouldRequestNow)
    }

    @Test
    fun `coarse only is sufficient and never auto-launches`() {
        val decision =
            decideLocationPermission(
                fineGranted = false,
                coarseGranted = true,
                hadRequestedBefore = false,
                shouldShowRationale = false,
                wasGrantedPreviously = false,
            )

        assertEquals(LocationPermissionStatus.COARSE_ONLY, decision.status)
        assertFalse(decision.shouldRequestNow)
    }

    @Test
    fun `denied with rationale never auto-relaunches`() {
        val decision =
            decideLocationPermission(
                fineGranted = false,
                coarseGranted = false,
                hadRequestedBefore = true,
                shouldShowRationale = true,
                wasGrantedPreviously = false,
            )

        assertEquals(LocationPermissionStatus.DENIED_RATIONALE_AVAILABLE, decision.status)
        assertFalse(decision.shouldRequestNow)
    }

    @Test
    fun `permanent denial offers no relaunch`() {
        val decision =
            decideLocationPermission(
                fineGranted = false,
                coarseGranted = false,
                hadRequestedBefore = true,
                shouldShowRationale = false,
                wasGrantedPreviously = false,
            )

        assertEquals(LocationPermissionStatus.DENIED_PERMANENT, decision.status)
        assertFalse(decision.shouldRequestNow)
    }

    @Test
    fun `revoked permission is re-derived without relaunch`() {
        val decision =
            decideLocationPermission(
                fineGranted = false,
                coarseGranted = false,
                hadRequestedBefore = true,
                shouldShowRationale = false,
                wasGrantedPreviously = true,
            )

        assertEquals(LocationPermissionStatus.REVOKED, decision.status)
        assertFalse(decision.shouldRequestNow)
    }
}
