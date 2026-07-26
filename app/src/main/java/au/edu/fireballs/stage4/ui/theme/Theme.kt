package au.edu.fireballs.stage4.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

private val LightColorScheme =
    lightColorScheme(
        primary = dfnMarkerUnprocessed,
        secondary = dfnMarkerYes,
        tertiary = dfnMarkerCar,
        error = dfnMarkerNo,
    )

private val DarkColorScheme =
    darkColorScheme(
        primary = dfnMarkerUnprocessed,
        secondary = dfnMarkerYes,
        tertiary = dfnMarkerCar,
        error = dfnMarkerNo,
    )

@Composable
fun Stage4Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val dfnColors = DFNColors()

    CompositionLocalProvider(
        LocalDFNColors provides dfnColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}

object Stage4Theme {
    val colors: DFNColors
        @Composable
        @ReadOnlyComposable
        get() = LocalDFNColors.current
}
