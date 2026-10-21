package io.github.elgrande73.voicememo

import io.github.elgrande73.voicememo.AudioFixtures.Noise
import io.github.elgrande73.voicememo.wakephrase.BuiltInWakePhrase
import io.github.elgrande73.voicememo.wakephrase.TemplateKeywordDetector
import io.github.elgrande73.voicememo.wakephrase.WakePhraseEnrollment
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bundled "Mango Tango" reference is reproducible: it is rebuilt here from the fixtures `mango_ref_*.pcm` with the
 * same validation as a Settings enrollment and must equal the bundled file byte for byte.
 * Regenerate after a front-end change with: `UPDATE_BUILT_IN_PHRASE=1 ./gradlew testDebugUnitTest --tests '*BuiltInWakePhraseTest*'`.
 */
class BuiltInWakePhraseTest {

    private fun rebuild(): ByteArray = BuiltInWakePhrase.serializeReferences(
        (1..5).map { "mango_ref_$it" }.map { name ->
            val recording = AudioFixtures.scene(AudioFixtures.atPeak(AudioFixtures.load(name), -12.0), 2.5, 0.3, -65.0, Noise.PINK, seed = name.hashCode())
            val result = WakePhraseEnrollment.validate(AudioFixtures.frames(recording))
            check(result is WakePhraseEnrollment.Result.Accepted) { "$name rejected: $result" }
            TemplateKeywordDetector.parseFeatures(result.templateBytes)!!
        }
    )

    @Test
    fun bundledMangoTangoReferenceIsReproducibleFromTheFixtures() {
        val rebuilt = rebuild()
        if (System.getenv("UPDATE_BUILT_IN_PHRASE") == "1") {
            File("src/main/resources${BuiltInWakePhrase.RESOURCE}").apply { parentFile.mkdirs() }.writeBytes(rebuilt)
        }
        val bundled = javaClass.getResourceAsStream(BuiltInWakePhrase.RESOURCE)!!.readBytes()
        assertArrayEquals(rebuilt, bundled)
        assertEquals(5, BuiltInWakePhrase.referenceFeatures.size)
    }

    @Test
    fun damagedReferenceDataIsRejected() {
        val valid = rebuild()
        assertNotNull(BuiltInWakePhrase.parseReferences(valid))
        assertNull(BuiltInWakePhrase.parseReferences(valid.copyOf(valid.size / 2)))
        assertNull(BuiltInWakePhrase.parseReferences(ByteArray(16)))
    }

    @Test
    fun withoutSamplesTheBuiltInPhraseIsMatchedAndSamplesReplaceIt() {
        fun best(detector: TemplateKeywordDetector, clip: String): Float {
            val frames = AudioFixtures.frames(AudioFixtures.scene(AudioFixtures.atPeak(AudioFixtures.load(clip), -14.0), 3.0, 0.5))
            detector.resetStream()
            return frames.maxOf { detector.processFrame(it, 0f, 0f).confidence }
        }
        val builtIn = TemplateKeywordDetector()
        assertFalse(builtIn.hasCustomTemplate())
        val reference = best(builtIn, "mango_ref_1") // a reference rendition matches itself almost perfectly
        assertTrue("reference $reference", reference >= 0.9f)
        // With a sample of another phrase, only that sample is matched
        val custom = SyntheticPhrase.detector()
        assertTrue(custom.hasCustomTemplate())
        assertTrue(best(custom, "mango_ref_1") < 0.5f)
    }
}
