package au.edu.fireballs.stage4.data.tiles

import android.content.SharedPreferences

class GeotiffRadiusRepository(
    private val preferences: SharedPreferences,
) {
    fun getRadiusMeters(): Float =
        preferences
            .getFloat(KEY_RADIUS_METERS, DEFAULT_RADIUS_METERS)
            .takeIf { it.isFinite() && it in MIN_RADIUS_METERS..MAX_RADIUS_METERS }
            ?: DEFAULT_RADIUS_METERS

    fun setRadiusMeters(value: Float) {
        require(value.isFinite() && value in MIN_RADIUS_METERS..MAX_RADIUS_METERS) {
            "Georeferenced imagery radius must be between 1 and 100 metres"
        }
        preferences.edit().putFloat(KEY_RADIUS_METERS, value).apply()
    }

    companion object {
        const val DEFAULT_RADIUS_METERS = 15.0f
        const val MIN_RADIUS_METERS = 1.0f
        const val MAX_RADIUS_METERS = 100.0f
        const val KEY_RADIUS_METERS = "geotiff_radius_meters"
    }
}
