package au.edu.fireballs.stage4.ui.screen.dataentry

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import au.edu.fireballs.stage4.data.tiles.BufferRadiusRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var repository: BufferRadiusRepository
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        preferences =
            context.getSharedPreferences(
                "test_settings",
                Context.MODE_PRIVATE,
            )
        preferences.edit().clear().commit()
        repository = BufferRadiusRepository(preferences)
        viewModel = SettingsViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `default radius 100 shown`() {
        assertEquals(100.0f, viewModel.uiState.value.currentRadius)
        assertEquals("100", viewModel.uiState.value.inputText)
        assertNull(viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.saved)
    }

    @Test
    fun `invalid input shows error and does not save`() {
        viewModel.onRadiusInput("0")
        assertEquals("Radius must be between 1 and 2000 m", viewModel.uiState.value.error)
        assertEquals(100.0f, repository.getBufferRadiusMeters())

        viewModel.onRadiusInput("2001")
        assertNotNull(viewModel.uiState.value.error)
        assertEquals(100.0f, repository.getBufferRadiusMeters())

        viewModel.onRadiusInput("abc")
        assertNotNull(viewModel.uiState.value.error)
        assertEquals(100.0f, repository.getBufferRadiusMeters())
    }

    @Test
    fun `valid input saves via repository`() {
        viewModel.onRadiusInput("250")
        viewModel.save()

        assertEquals(250.0f, repository.getBufferRadiusMeters())
        assertEquals(250.0f, viewModel.uiState.value.currentRadius)
        assertNull(viewModel.uiState.value.error)
        assertTrue(viewModel.uiState.value.saved)
    }

    @Test
    fun `invalid save does not persist`() {
        viewModel.onRadiusInput("0")
        viewModel.save()

        assertEquals(100.0f, repository.getBufferRadiusMeters())
        assertFalse(viewModel.uiState.value.saved)
        assertNotNull(viewModel.uiState.value.error)
    }
}
