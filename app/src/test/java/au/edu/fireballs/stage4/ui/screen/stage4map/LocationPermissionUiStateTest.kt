package au.edu.fireballs.stage4.ui.screen.stage4map

import android.Manifest
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocationPermissionUiStateTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var activity: ComponentActivity

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    }

    private fun shadowApplication(): ShadowApplication =
        shadowOf(activity.application as Application)

    private fun revokeAllPermissions() {
        shadowApplication().denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    private fun grantAllPermissions() {
        shadowApplication().grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    private fun composePermissionHarness(): MutableList<LocationPermissionUiState> {
        val states = mutableListOf<LocationPermissionUiState>()
        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides activity,
                LocalActivityResultRegistryOwner provides activity,
            ) {
                val tick = remember { mutableIntStateOf(0) }
                val state = rememberLocationPermission()
                states.add(state)
                Text(text = "tick ${tick.intValue}")
                Button(onClick = { tick.intValue++ }) {
                    Text("bump")
                }
            }
        }
        composeRule.waitForIdle()
        return states
    }

    private fun recompose(states: MutableList<LocationPermissionUiState>) {
        composeRule.onNodeWithText("bump").performClick()
        composeRule.waitForIdle()
        assertTrue(states.isNotEmpty())
    }

    @Test
    fun firstComposition_autoRequestRecordsHistory_acrossRecomposition() {
        revokeAllPermissions()
        val states = composePermissionHarness()

        val preEffect = states.first()
        assertEquals(LocationPermissionStatus.NEVER_REQUESTED, preEffect.status)
        assertFalse(preEffect.hadRequestedBefore)

        val postEffect = states.last()
        assertTrue(postEffect.hadRequestedBefore)
        assertFalse(postEffect.wasGrantedPreviously)

        recompose(states)
        assertTrue(states.last().hadRequestedBefore)
        assertEquals(LocationPermissionStatus.DENIED_PERMANENT, states.last().status)
    }

    @Test
    fun grantThenRevoke_classifiesAsRevoked_notNeverRequested_andShowsNotice() {
        revokeAllPermissions()
        val states = composePermissionHarness()
        assertTrue(states.last().hadRequestedBefore)

        grantAllPermissions()
        recompose(states)
        assertEquals(LocationPermissionStatus.GRANTED, states.last().status)
        assertTrue(states.last().wasGrantedPreviously)

        revokeAllPermissions()
        recompose(states)
        assertEquals(LocationPermissionStatus.REVOKED, states.last().status)
        assertTrue(states.last().showDeniedNotice)
        assertFalse(states.last().locationPermissionGranted)
    }

    @Test
    fun dismissedNotice_staysDismissedAcrossUnrelatedRecomposition() {
        revokeAllPermissions()
        val states = composePermissionHarness()
        assertTrue(states.last().hadRequestedBefore)

        grantAllPermissions()
        recompose(states)
        revokeAllPermissions()
        recompose(states)
        assertTrue(states.last().showDeniedNotice)

        states.last().dismissDeniedNotice()
        recompose(states)
        assertFalse(states.last().showDeniedNotice)
    }

    @Test
    fun newDenialStatus_resetsDismissal() {
        revokeAllPermissions()
        val states = composePermissionHarness()
        assertTrue(states.last().hadRequestedBefore)

        grantAllPermissions()
        recompose(states)
        revokeAllPermissions()
        recompose(states)
        states.last().dismissDeniedNotice()
        recompose(states)
        assertFalse(states.last().showDeniedNotice)

        grantAllPermissions()
        recompose(states)
        revokeAllPermissions()
        recompose(states)
        assertTrue(states.last().showDeniedNotice)
    }
}
