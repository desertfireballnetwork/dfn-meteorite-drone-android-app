package au.edu.fireballs.stage4.ui.util

import au.edu.fireballs.stage4.sync.PreDownloadOrchestrator

internal fun downloadPhaseLabel(phase: String?): String =
    when (phase) {
        PreDownloadOrchestrator.PHASE_SATELLITE -> "Downloading satellite imagery"
        PreDownloadOrchestrator.PHASE_TILES -> "Downloading survey tiles"
        PreDownloadOrchestrator.PHASE_CROPS -> "Downloading candidate crops"
        else -> "Downloading offline data"
    }
