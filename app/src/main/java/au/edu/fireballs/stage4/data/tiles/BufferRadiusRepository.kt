package au.edu.fireballs.stage4.data.tiles

import android.content.SharedPreferences

class BufferRadiusRepository(
    private val preferences: SharedPreferences,
) {
    fun getBufferRadiusMeters(): Float =
        preferences.getFloat(
            KEY_BUFFER_RADIUS_METERS,
            DEFAULT_BUFFER_RADIUS_METERS,
        )

    fun setBufferRadiusMeters(value: Float) {
        preferences.edit().putFloat(KEY_BUFFER_RADIUS_METERS, value).apply()
    }

    companion object {
        const val DEFAULT_BUFFER_RADIUS_METERS = 100.0f
        const val KEY_BUFFER_RADIUS_METERS = "buffer_radius_meters"
    }
}
