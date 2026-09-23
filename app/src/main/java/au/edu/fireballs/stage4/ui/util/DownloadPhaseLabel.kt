package au.edu.fireballs.stage4.ui.util

import au.edu.fireballs.stage4.sync.PreDownloadOrchestrator

internal fun downloadPhaseLabel(phase: String?): String =
    when (phase) {
        PreDownloadOrchestrator.PHASE_SATELLITE -> "Downloading satellite imagery"
        PreDownloadOrchestrator.PHASE_TILES -> "Downloading survey tiles"
        PreDownloadOrchestrator.PHASE_CROPS -> "Downloading candidate crops"
        PreDownloadOrchestrator.PHASE_FINALISING -> "Finalising download"
        else -> "Downloading offline data"
    }
