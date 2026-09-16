package au.edu.fireballs.stage4.ui.screen.candidate

object CandidateMapDefaults {
    const val BASE_STYLE_URI = "mapbox://styles/mapbox/satellite-v9"
    const val CANDIDATE_ZOOM = 20.0
    const val MAX_CAMERA_ZOOM = 24.0
    const val SOURCE_KEY = "candidate_raster_tiles"
    const val LAYER_ID = "candidate-tiles"
    const val TILE_SIZE = 2048L
    const val MIN_ZOOM = 20L
    const val MAX_ZOOM = 22L
    const val ROOT_TAG = "candidate-map-root"
    const val MARKER_TAG = "candidate-map-marker"

    fun sourceKey(candidateId: Long): String = "candidate-raster-$candidateId"

    fun layerId(candidateId: Long): String = "candidate-tiles-$candidateId"
}
