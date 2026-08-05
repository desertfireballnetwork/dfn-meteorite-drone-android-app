package au.edu.fireballs.stage4.ui.util

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * Sealed interface representing text that can be resolved dynamically
 * or via String Resources in Jetpack Compose.
 */
sealed interface UiText {
    data class DynamicString(
        val value: String,
    ) : UiText

    data class StringResource(
        @StringRes val resId: Int,
    ) : UiText

    @Composable
    fun asString(): String =
        when (this) {
            is DynamicString -> value
            is StringResource -> stringResource(resId)
        }
}
