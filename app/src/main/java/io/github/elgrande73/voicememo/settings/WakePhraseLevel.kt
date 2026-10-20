package io.github.elgrande73.voicememo.settings

/**
 * Optional "Minimum audio level" condition for the wake phrase (in addition to the match confidence threshold).
 *
 * Levels are digital levels in dBFS: RMS of a 50 ms frame relative to full scale (0 dBFS = loudest possible signal).
 * They are not acoustic sound pressure (dB SPL): the same voice gives different dBFS values on different phones,
 * microphones and gain settings. Internally the detector uses the VAD scale, dBFS + 90.
 */
object WakePhraseLevel {
    const val MIN_DBFS = -60f
    /** −15 dBFS covers the loudest activation threshold earlier versions allowed (75 on the old scale). */
    const val MAX_DBFS = -15f

    /** Default for new installations; chosen by measurement (see docs/WAKE_PHRASE_DETECTION.md, "Minimum audio level"). */
    const val DEFAULT_DBFS = -50f
    const val DEFAULT_ENABLED = true

    /** Offset between dBFS and the detector's VAD dB scale. */
    const val VAD_SCALE_OFFSET = 90f

    fun clampDbfs(dbfs: Float): Float = if (dbfs.isNaN()) DEFAULT_DBFS else dbfs.coerceIn(MIN_DBFS, MAX_DBFS)

    fun toDetectorDb(dbfs: Float): Float = clampDbfs(dbfs) + VAD_SCALE_OFFSET

    fun fromDetectorDb(db: Float): Float = db - VAD_SCALE_OFFSET
}
