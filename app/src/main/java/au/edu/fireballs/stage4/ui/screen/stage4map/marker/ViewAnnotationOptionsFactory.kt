package au.edu.fireballs.stage4.ui.screen.stage4map.marker

import au.edu.fireballs.stage4.domain.model.GeoCoordinate
import com.mapbox.geojson.Point
import com.mapbox.maps.AnnotatedFeature
import com.mapbox.maps.ViewAnnotationAnchor
import com.mapbox.maps.ViewAnnotationAnchorConfig
import com.mapbox.maps.ViewAnnotationOptions

fun viewAnnotationOptions(
    coordinate: GeoCoordinate,
    widthDp: Double? = null,
    heightDp: Double? = null,
): ViewAnnotationOptions =
    ViewAnnotationOptions
        .Builder()
        .annotatedFeature(
            AnnotatedFeature(
                Point.fromLngLat(coordinate.longitude, coordinate.latitude),
            ),
        ).variableAnchors(
            listOf(
                ViewAnnotationAnchorConfig
                    .Builder()
                    .anchor(ViewAnnotationAnchor.BOTTOM)
                    .build(),
            ),
        ).allowOverlap(true)
        .allowOverlapWithPuck(true)
        .apply {
            if (widthDp != null) width(widthDp)
            if (heightDp != null) height(heightDp)
        }.build()
