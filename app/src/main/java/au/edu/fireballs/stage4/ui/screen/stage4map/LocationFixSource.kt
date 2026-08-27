package au.edu.fireballs.stage4.ui.screen.stage4map

internal enum class LocationFixSource {
    CACHE,
    REQUEST_FRESH,
}

internal const val MAX_LOCATION_AGE_MS = 60_000L

internal fun chooseLocationSource(
    lastFixTimeMs: Long?,
    nowMs: Long,
): LocationFixSource =
    when {
        lastFixTimeMs == null -> LocationFixSource.REQUEST_FRESH
        nowMs - lastFixTimeMs <= MAX_LOCATION_AGE_MS -> LocationFixSource.CACHE
        else -> LocationFixSource.REQUEST_FRESH
    }
