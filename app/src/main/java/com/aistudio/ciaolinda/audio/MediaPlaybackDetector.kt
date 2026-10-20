package com.aistudio.ciaolinda.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build

/**
 * State representing media playback on the device.
 */
data class MediaPlaybackStatus(
    val isMediaPlaying: Boolean,
    val activeMediaCount: Int,
    val description: String
)

/**
 * Detector for tracking whether background music, podcasts, or audiobooks are currently playing on the device.
 */
class MediaPlaybackDetector(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /**
     * Checks if media is currently actively playing on the Android device.
     */
    fun checkPlaybackStatus(): MediaPlaybackStatus {
        if (audioManager == null) {
            return MediaPlaybackStatus(isMediaPlaying = false, activeMediaCount = 0, description = "Audio service unavailable")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val configs = audioManager.activePlaybackConfigurations
                val isMusicActive = audioManager.isMusicActive
                return evaluatePlaybackState(configs, isMusicActive)
            } catch (_: Exception) {
                val isMusicActive = audioManager.isMusicActive
                return evaluatePlaybackState(null, isMusicActive)
            }
        } else {
            val isMusicActive = audioManager.isMusicActive
            return evaluatePlaybackState(null, isMusicActive)
        }
    }

    companion object {
        fun isRelevantMediaUsage(usage: Int): Boolean {
            return usage == AudioAttributes.USAGE_MEDIA ||
                    usage == AudioAttributes.USAGE_GAME ||
                    usage == AudioAttributes.USAGE_UNKNOWN
        }

        fun evaluatePlaybackState(
            configs: List<AudioPlaybackConfiguration>?,
            isMusicActive: Boolean
        ): MediaPlaybackStatus {
            if (configs != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                var mediaCount = 0
                for (config in configs) {
                    if (isRelevantMediaUsage(config.audioAttributes.usage)) {
                        mediaCount++
                    }
                }

                if (mediaCount > 0 || isMusicActive) {
                    val count = if (mediaCount > 0) mediaCount else 1
                    return MediaPlaybackStatus(
                        isMediaPlaying = true,
                        activeMediaCount = count,
                        description = "Media playing ($count stream${if (count > 1) "s" else ""})"
                    )
                }
            } else if (isMusicActive) {
                return MediaPlaybackStatus(
                    isMediaPlaying = true,
                    activeMediaCount = 1,
                    description = "Music/podcast playing"
                )
            }

            return MediaPlaybackStatus(
                isMediaPlaying = false,
                activeMediaCount = 0,
                description = "No media playing"
            )
        }
    }
}
