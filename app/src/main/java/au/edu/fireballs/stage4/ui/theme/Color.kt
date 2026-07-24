package au.edu.fireballs.stage4.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val dfnMarkerYes = Color(0xFF28A745)
val dfnMarkerNo = Color(0xFFDC3545)
val dfnMarkerUnprocessed = Color(0xFF007BFF)
val dfnMarkerCar = Color(0xFFFFC107)
val dfnSurveyedAreaPolygon = Color(0xFF000000)
val dfnMarkerClaimedByMeOutline = Color(0xFF28A745)
val dfnMarkerClaimedByOtherOutline = Color(0xFFDC3545)

@Immutable
data class DFNColors(
    val markerYes: Color = dfnMarkerYes,
    val markerNo: Color = dfnMarkerNo,
    val markerUnprocessed: Color = dfnMarkerUnprocessed,
    val markerCar: Color = dfnMarkerCar,
    val surveyedAreaPolygon: Color = dfnSurveyedAreaPolygon,
    val markerClaimedByMeOutline: Color = dfnMarkerClaimedByMeOutline,
    val markerClaimedByOtherOutline: Color = dfnMarkerClaimedByOtherOutline,
)

val LocalDFNColors = staticCompositionLocalOf { DFNColors() }
