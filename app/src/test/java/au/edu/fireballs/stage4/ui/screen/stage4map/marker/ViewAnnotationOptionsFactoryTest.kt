package au.edu.fireballs.stage4.ui.screen.stage4map.marker

import au.edu.fireballs.stage4.domain.model.GeoCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewAnnotationOptionsFactoryTest {
    private val coordinate = GeoCoordinate(latitude = -31.95, longitude = 141.45)

    @Test
    fun viewAnnotationOptions_allowsOverlapWithOtherAnnotations() {
        val options = viewAnnotationOptions(coordinate)
        assertEquals(true, options.getAllowOverlap())
    }

    @Test
    fun viewAnnotationOptions_allowsOverlapWithPuck() {
        val options = viewAnnotationOptions(coordinate)
        assertEquals(true, options.getAllowOverlapWithPuck())
    }

    @Test
    fun viewAnnotationOptions_anchorsToBottom() {
        val options = viewAnnotationOptions(coordinate)
        val anchors = options.getVariableAnchors()!!
        assertEquals(1, anchors.size)
        assertEquals(
            com.mapbox.maps.ViewAnnotationAnchor.BOTTOM,
            anchors.first().anchor,
        )
    }

    @Test
    fun viewAnnotationOptions_setsWidthAndHeightWhenProvided() {
        val options = viewAnnotationOptions(coordinate, widthDp = 40.0, heightDp = 40.0)
        assertEquals(40.0, options.getWidth())
        assertEquals(40.0, options.getHeight())
    }

    @Test
    fun viewAnnotationOptions_leavesWidthAndHeightNullWhenNotProvided() {
        val options = viewAnnotationOptions(coordinate)
        assertNull(options.getWidth())
        assertNull(options.getHeight())
    }

    @Test
    fun viewAnnotationOptions_usesProvidedCoordinate() {
        val options = viewAnnotationOptions(coordinate)
        val feature = options.getAnnotatedFeature()
        assertTrue(feature is com.mapbox.maps.AnnotatedFeature)
    }
}
