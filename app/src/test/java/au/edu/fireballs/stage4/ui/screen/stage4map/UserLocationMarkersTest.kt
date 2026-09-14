package au.edu.fireballs.stage4.ui.screen.stage4map

import au.edu.fireballs.stage4.domain.model.GeoCoordinate
import au.edu.fireballs.stage4.domain.model.UserLocation
import com.mapbox.geojson.Feature
import com.mapbox.geojson.Point
import org.junit.Assert.assertEquals
import org.junit.Test

class UserLocationMarkersTest {
    private fun createUser(
        userId: Long,
        username: String = "user$userId",
        lat: Double = -37.8,
        lon: Double = 145.0,
    ): UserLocation =
        UserLocation(
            username = username,
            fullName = "Full Name $userId",
            userId = userId,
            coordinate = GeoCoordinate(latitude = lat, longitude = lon),
            processedAt = "2026-09-14T10:00:00Z",
        )

    @Test
    fun buildUserLocationFeatures_buildsOneFeaturePerLocation() {
        val locations = listOf(createUser(1L), createUser(2L), createUser(3L))

        val features = buildUserLocationFeatures(locations)

        assertEquals(3, features.size)
        val ids = features.map { it.getNumberProperty("userId")!!.toLong() }.toSet()
        assertEquals(setOf(1L, 2L, 3L), ids)
    }

    @Test
    fun buildUserLocationFeatures_setsGeometryAndProperties() {
        val location = createUser(7L, username = "alice", lat = -33.86, lon = 151.21)

        val feature = buildUserLocationFeatures(listOf(location)).single()

        val point = feature.geometry() as Point
        assertEquals(151.21, point.longitude(), 0.0001)
        assertEquals(-33.86, point.latitude(), 0.0001)
        assertEquals(7L, feature.getNumberProperty("userId")!!.toLong())
        assertEquals(null, feature.getStringProperty("username"))
        assertEquals(null, feature.getStringProperty("fullName"))
        assertEquals(null, feature.getStringProperty("processedAt"))
    }

    @Test
    fun buildUserLocationFeatures_returnsEmptyForEmptyList() {
        assertEquals(emptyList<Feature>(), buildUserLocationFeatures(emptyList()))
    }
}
