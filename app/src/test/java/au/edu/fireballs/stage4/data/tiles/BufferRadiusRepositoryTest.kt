package au.edu.fireballs.stage4.data.tiles

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BufferRadiusRepositoryTest {
    private lateinit var preferences: android.content.SharedPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        preferences =
            context.getSharedPreferences(
                "test_buffer_radius",
                Context.MODE_PRIVATE,
            )
        preferences.edit().clear().commit()
    }

    @Test
    fun `returns default when unset`() {
        val repository = BufferRadiusRepository(preferences)

        assertEquals(
            BufferRadiusRepository.DEFAULT_BUFFER_RADIUS_METERS,
            repository.getBufferRadiusMeters(),
        )
    }

    @Test
    fun `get set round trip`() {
        val repository = BufferRadiusRepository(preferences)

        repository.setBufferRadiusMeters(250.0f)

        assertEquals(250.0f, repository.getBufferRadiusMeters())
    }

    @Test
    fun `persists across new repository instance`() {
        val first = BufferRadiusRepository(preferences)
        first.setBufferRadiusMeters(500.0f)

        val second = BufferRadiusRepository(preferences)

        assertEquals(500.0f, second.getBufferRadiusMeters())
    }
}
