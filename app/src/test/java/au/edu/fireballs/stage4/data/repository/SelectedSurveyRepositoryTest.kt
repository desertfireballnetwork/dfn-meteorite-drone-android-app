package au.edu.fireballs.stage4.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SelectedSurveyRepositoryTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var repository: SelectedSurveyRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dataStoreScope = CoroutineScope(testDispatcher + Job())
        dataStore =
            PreferenceDataStoreFactory.create(
                scope = dataStoreScope,
                produceFile = { context.preferencesDataStoreFile("test_selected_survey") },
            )
        repository = SelectedSurveyRepository(dataStore)
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
    }

    @Test
    fun `returns null when unset`() =
        runTest(testDispatcher) {
            assertNull(repository.selectedSurveyId.first())
        }

    @Test
    fun `set then read back returns the survey id`() =
        runTest(testDispatcher) {
            repository.set(42L)

            assertEquals(42L, repository.selectedSurveyId.first())
        }

    @Test
    fun `set overwrites previous value`() =
        runTest(testDispatcher) {
            repository.set(42L)
            repository.set(99L)

            assertEquals(99L, repository.selectedSurveyId.first())
        }

    @Test
    fun `persists across new repository instance`() =
        runTest(testDispatcher) {
            repository.set(42L)

            val second = SelectedSurveyRepository(dataStore)

            assertEquals(42L, second.selectedSurveyId.first())
        }
}
