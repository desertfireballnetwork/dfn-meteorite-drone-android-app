package au.edu.fireballs.stage4.ui.screen.basecamp

import au.edu.fireballs.stage4.domain.model.GeoCoordinate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolygonMathTest {
    private val square =
        listOf(
            GeoCoordinate(0.0, 0.0),
            GeoCoordinate(0.0, 10.0),
            GeoCoordinate(10.0, 10.0),
            GeoCoordinate(10.0, 0.0),
        )

    @Test
    fun pointInsidePolygonReturnsTrue() {
        assertTrue(isPointInPolygon(GeoCoordinate(5.0, 5.0), square))
    }

    @Test
    fun pointOutsidePolygonReturnsFalse() {
        assertFalse(isPointInPolygon(GeoCoordinate(15.0, 5.0), square))
        assertFalse(isPointInPolygon(GeoCoordinate(5.0, -5.0), square))
    }

    @Test
    fun vertexOnBoundaryIsInside() {
        assertTrue(isPointInPolygon(GeoCoordinate(0.0, 0.0), square))
    }

    @Test
    fun pointOnEdgeIsInside() {
        assertTrue(isPointInPolygon(GeoCoordinate(5.0, 0.0), square))
    }

    @Test
    fun polygonWithFewerThanThreeVerticesReturnsFalse() {
        val two = listOf(GeoCoordinate(0.0, 0.0), GeoCoordinate(1.0, 1.0))
        assertFalse(isPointInPolygon(GeoCoordinate(0.5, 0.5), two))
    }

    @Test
    fun concavePolygonHandlesRayCasting() {
        val concave =
            listOf(
                GeoCoordinate(0.0, 0.0),
                GeoCoordinate(10.0, 0.0),
                GeoCoordinate(10.0, 10.0),
                GeoCoordinate(5.0, 5.0),
                GeoCoordinate(0.0, 10.0),
            )
        assertTrue(isPointInPolygon(GeoCoordinate(2.0, 2.0), concave))
        assertFalse(isPointInPolygon(GeoCoordinate(8.0, 9.0), concave))
    }
}
