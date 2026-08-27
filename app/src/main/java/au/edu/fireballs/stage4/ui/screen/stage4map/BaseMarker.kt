package au.edu.fireballs.stage4.ui.screen.stage4map

import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.runtime.Composable
import au.edu.fireballs.stage4.R
import au.edu.fireballs.stage4.domain.model.GeoCoordinate
import com.mapbox.geojson.Point
import com.mapbox.maps.AnnotatedFeature
import com.mapbox.maps.MapView
import com.mapbox.maps.ViewAnnotationAnchor
import com.mapbox.maps.ViewAnnotationAnchorConfig
import com.mapbox.maps.ViewAnnotationOptions
import com.mapbox.maps.extension.compose.DisposableMapEffect
import kotlin.math.roundToInt

private const val MARKER_SIZE_DP = 40.0

@Composable
fun BaseMarker(base: GeoCoordinate?) {
    base ?: return

    DisposableMapEffect(base) { mapView ->
        val markerView = createCarMarkerView(mapView)
        val options =
            ViewAnnotationOptions
                .Builder()
                .annotatedFeature(
                    AnnotatedFeature(
                        Point.fromLngLat(base.longitude, base.latitude),
                    ),
                ).variableAnchors(
                    listOf(
                        ViewAnnotationAnchorConfig
                            .Builder()
                            .anchor(ViewAnnotationAnchor.BOTTOM)
                            .build(),
                    ),
                ).width(MARKER_SIZE_DP)
                .height(MARKER_SIZE_DP)
                .build()

        mapView.viewAnnotationManager.addViewAnnotation(markerView, options)

        onDispose {
            mapView.viewAnnotationManager.removeViewAnnotation(markerView)
        }
    }
}

private fun createCarMarkerView(mapView: MapView): ImageView {
    val density = mapView.resources.displayMetrics.density
    val sizePx = (MARKER_SIZE_DP * density).roundToInt()
    return ImageView(mapView.context).apply {
        setImageResource(R.drawable.marker_car)
        layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
    }
}
