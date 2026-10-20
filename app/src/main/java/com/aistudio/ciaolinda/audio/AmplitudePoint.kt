package com.aistudio.ciaolinda.audio

/**
 * Data model for a single audio amplitude data point on the live waveform visualizer.
 */
data class AmplitudePoint(
    val timestamp: Long,
    val amplitudeDb: Float,
    val isTriggered: Boolean = false,
    val isKeyword: Boolean = false
)
