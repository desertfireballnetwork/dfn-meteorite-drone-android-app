package au.edu.fireballs.stage4.ui.screen.stage4map.marker

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import au.edu.fireballs.stage4.R
import au.edu.fireballs.stage4.domain.model.Stage4Candidate

data class MarkerStyle(
    val iconRes: Int,
    val size: Dp,
    val opacity: Float,
    val claimBorderColor: Color?,
)

object MarkerStyleDefaults {
    val YES_SIZE = 50.dp
    val UNPROCESSED_SIZE = 50.dp
    val NO_SIZE = 30.dp
    const val NO_OPACITY = 0.8f

    val CLAIM_ME_COLOR = Color(0xFF28A745)
    val CLAIM_OTHER_COLOR = Color(0xFFDC3545)
}

fun getCandidateMarkerStyle(
    candidate: Stage4Candidate,
    verdict: Int, // 0=unprocessed, 1=yes, 2=no
): MarkerStyle {
    val iconRes =
        when (verdict) {
            1 -> R.drawable.marker_yes
            2 -> R.drawable.marker_no
            else -> R.drawable.marker_unprocessed
        }

    val size =
        when (verdict) {
            1 -> MarkerStyleDefaults.YES_SIZE
            2 -> MarkerStyleDefaults.NO_SIZE
            else -> MarkerStyleDefaults.UNPROCESSED_SIZE
        }

    val opacity = if (verdict == 2) MarkerStyleDefaults.NO_OPACITY else 1.0f

    val claimBorderColor =
        when {
            candidate.claimedByMe -> MarkerStyleDefaults.CLAIM_ME_COLOR
            candidate.claimedByOther -> MarkerStyleDefaults.CLAIM_OTHER_COLOR
            else -> null
        }

    return MarkerStyle(
        iconRes = iconRes,
        size = size,
        opacity = opacity,
        claimBorderColor = claimBorderColor,
    )
}
