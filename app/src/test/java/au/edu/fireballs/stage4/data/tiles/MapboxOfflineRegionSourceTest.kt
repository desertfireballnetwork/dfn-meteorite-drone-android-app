package au.edu.fireballs.stage4.data.tiles

import au.edu.fireballs.stage4.BuildConfig
import org.junit.Assert.assertTrue
import org.junit.Test

class MapboxOfflineRegionSourceTest {
    @Test
    fun defaultDeviceTileLimitExceedsMapboxDefault() {
        assertTrue(
            "MAPBOX_MAX_TILES_DEVICE=${BuildConfig.MAPBOX_MAX_TILES_DEVICE} must exceed " +
                "Mapbox's device-wide default of $MAPBOX_DEFAULT_TILE_LIMIT tiles, otherwise " +
                "real campaign offline sets are rejected with TILE_COUNT_LIMIT_EXCEEDED",
            BuildConfig.MAPBOX_MAX_TILES_DEVICE > MAPBOX_DEFAULT_TILE_LIMIT,
        )
    }

    companion object {
        private const val MAPBOX_DEFAULT_TILE_LIMIT = 6000L
    }
}
