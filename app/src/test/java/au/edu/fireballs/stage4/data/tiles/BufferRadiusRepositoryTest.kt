package au.edu.fireballs.stage4.data.tiles

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    @Test
    fun `rejects non finite and out of range values`() {
        val repository = BufferRadiusRepository(preferences)

        assertThrows(IllegalArgumentException::class.java) {
            repository.setBufferRadiusMeters(Float.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.setBufferRadiusMeters(-1.0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.setBufferRadiusMeters(0.0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.setBufferRadiusMeters(20_000.0f)
        }
    }

    @Test
    fun `invalid persisted value falls back to default`() {
        val repository = BufferRadiusRepository(preferences)

        preferences
            .edit()
            .putFloat(BufferRadiusRepository.KEY_BUFFER_RADIUS_METERS, Float.NaN)
            .commit()
        assertEquals(
            BufferRadiusRepository.DEFAULT_BUFFER_RADIUS_METERS,
            repository.getBufferRadiusMeters(),
        )

        preferences
            .edit()
            .putFloat(BufferRadiusRepository.KEY_BUFFER_RADIUS_METERS, Float.POSITIVE_INFINITY)
            .commit()
        assertEquals(
            BufferRadiusRepository.DEFAULT_BUFFER_RADIUS_METERS,
            repository.getBufferRadiusMeters(),
        )

        preferences
            .edit()
            .putFloat(BufferRadiusRepository.KEY_BUFFER_RADIUS_METERS, Float.NEGATIVE_INFINITY)
            .commit()
        assertEquals(
            BufferRadiusRepository.DEFAULT_BUFFER_RADIUS_METERS,
            repository.getBufferRadiusMeters(),
        )

        preferences
            .edit()
            .putFloat(BufferRadiusRepository.KEY_BUFFER_RADIUS_METERS, -5.0f)
            .commit()
        assertEquals(
            BufferRadiusRepository.DEFAULT_BUFFER_RADIUS_METERS,
            repository.getBufferRadiusMeters(),
        )

        preferences
            .edit()
            .putFloat(BufferRadiusRepository.KEY_BUFFER_RADIUS_METERS, 0.0f)
            .commit()
        assertEquals(
            BufferRadiusRepository.DEFAULT_BUFFER_RADIUS_METERS,
            repository.getBufferRadiusMeters(),
        )

        preferences
            .edit()
            .putFloat(BufferRadiusRepository.KEY_BUFFER_RADIUS_METERS, 20_000.0f)
            .commit()
        assertEquals(
            BufferRadiusRepository.DEFAULT_BUFFER_RADIUS_METERS,
            repository.getBufferRadiusMeters(),
        )
    }
}
