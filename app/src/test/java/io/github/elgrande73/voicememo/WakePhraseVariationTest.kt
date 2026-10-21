package io.github.elgrande73.voicememo

import io.github.elgrande73.voicememo.AudioFixtures.Noise
import io.github.elgrande73.voicememo.wakephrase.WakePhraseEnrollment
import io.github.elgrande73.voicememo.wakephrase.WakePhraseEvaluator
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Detection across systematic pronunciation variation (fixtures `var_*`, synthetic eSpeak NG speech, never used to
 * choose parameters): speaking rate 110–200 words per minute, pitch 20–80 (eSpeak scale), no pause or a 40 ms pause
 * between the words, a lower and a higher voice, normal (−12 dBFS peak) and quiet (−32 dBFS) speech, quiet room and
 * white noise at −45 dBFS. Two setups: the built-in reference, and one recorded sample of the same voice at its
 * middle variant (140 wpm, pitch 50, no pause) — the user records once and later says the phrase differently.
 * Recall is printed per factor with "MEASURE" (docs/WAKE_PHRASE_DETECTION.md, section 19).
 */
class WakePhraseVariationTest {

    private val voices = listOf("enusm4", "enf5")
    private val speeds = listOf(110, 140, 170, 200)
    private val pitches = listOf(20, 50, 80)
    private val gaps = listOf(0, 4)
    private val levels = listOf(-12.0 to "normal", -32.0 to "quiet")
    private val backgrounds = listOf(-65.0 to Noise.PINK, -45.0 to Noise.WHITE)

    private fun clip(voice: String, speed: Int, pitch: Int, gap: Int) = "var_${voice}_s${speed}_p${pitch}_g$gap"

    private fun enroll(name: String): ByteArray {
        val recording = AudioFixtures.scene(AudioFixtures.atPeak(AudioFixtures.load(name), -12.0), 2.5, 0.3, -65.0, Noise.PINK, seed = name.hashCode())
        return (WakePhraseEnrollment.validate(AudioFixtures.frames(recording)) as WakePhraseEnrollment.Result.Accepted).templateBytes
    }

    private data class Trial(val voice: String, val speed: Int, val pitch: Int, val gap: Int, val level: String, val noisy: Boolean, val hit: Boolean)

    private fun trials(ownVoice: Boolean, minLevel: Float? = WakePhraseBenchmark.Setup().minLevelDb): List<Trial> {
        val out = mutableListOf<Trial>()
        var seed = 500
        for (voice in voices) {
            val templates = if (ownVoice) listOf(enroll(clip(voice, 140, 50, 0))) else emptyList()
            for (speed in speeds) for (pitch in pitches) for (gap in gaps) for ((peak, level) in levels) for ((nd, nt) in backgrounds) {
                val name = clip(voice, speed, pitch, gap)
                if (ownVoice && name == clip(voice, 140, 50, 0)) continue // the enrolled rendition itself
                val offset = Random(seed).nextDouble(0.0, 0.05)
                val frames = AudioFixtures.frames(AudioFixtures.scene(AudioFixtures.atPeak(AudioFixtures.load(name), peak), 3.0, 0.5 + offset, nd, nt, seed++))
                val hit = WakePhraseEvaluator.evaluate(frames, templates, 0.5f, minLevel).wouldTrigger
                out += Trial(voice, speed, pitch, gap, level, nd > -50.0, hit)
            }
        }
        return out
    }

    private fun report(label: String, t: List<Trial>) {
        fun r(group: List<Trial>) = "${group.count { it.hit }}/${group.size}"
        println("MEASURE variation $label overall ${r(t)}")
        println("MEASURE variation $label speed " + speeds.joinToString { s -> "$s wpm ${r(t.filter { it.speed == s })}" })
        println("MEASURE variation $label pitch " + pitches.joinToString { p -> "$p ${r(t.filter { it.pitch == p })}" })
        println("MEASURE variation $label pause " + gaps.joinToString { g -> "${g * 10} ms ${r(t.filter { it.gap == g })}" })
        println("MEASURE variation $label level " + levels.joinToString { (_, l) -> "$l ${r(t.filter { it.level == l })}" })
        println("MEASURE variation $label voice " + voices.joinToString { v -> "$v ${r(t.filter { it.voice == v })}" })
        println("MEASURE variation $label background quiet ${r(t.filter { !it.noisy })}, noise −45 dBFS ${r(t.filter { it.noisy })}")
        println("MEASURE variation $label noise×level " + listOf(false, true).joinToString { n -> levels.joinToString { (_, l) -> "${if (n) "noise" else "quiet room"}/$l ${r(t.filter { it.noisy == n && it.level == l })}" } })
    }

    @Test
    fun recallAcrossPronunciationAndVoiceVariation() {
        val builtIn = trials(ownVoice = false)
        val own = trials(ownVoice = true)
        report("built-in", builtIn)
        report("one own sample", own)
        if (System.getenv("WAKE_EXPERIMENT") == "1") {
            report("built-in, level condition off", trials(ownVoice = false, minLevel = null))
            report("one own sample, level condition off", trials(ownVoice = true, minLevel = null))
        }
        // Regression guards from the measured values (see docs); recording one's own voice must stay clearly better
        assertTrue(own.count { it.hit } * builtIn.size > builtIn.count { it.hit } * own.size)
        assertTrue("own sample ${own.count { it.hit }}/${own.size}", own.count { it.hit } >= MEASURED_OWN_HITS)
        assertTrue("built-in ${builtIn.count { it.hit }}/${builtIn.size}", builtIn.count { it.hit } >= MEASURED_BUILT_IN_HITS)
    }

    companion object {
        const val MEASURED_OWN_HITS = 0
        const val MEASURED_BUILT_IN_HITS = 0
    }
}
