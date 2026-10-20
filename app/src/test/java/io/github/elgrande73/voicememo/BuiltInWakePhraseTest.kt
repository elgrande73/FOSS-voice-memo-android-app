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
            File("src/main/resources${BuiltInWakePhrase.MANGO_TANGO_RESOURCE}").apply { parentFile.mkdirs() }.writeBytes(rebuilt)
        }
        val bundled = javaClass.getResourceAsStream(BuiltInWakePhrase.MANGO_TANGO_RESOURCE)!!.readBytes()
        assertArrayEquals(rebuilt, bundled)
        assertEquals(5, BuiltInWakePhrase.MANGO_TANGO.referenceFeatures().size)
    }

    @Test
    fun damagedReferenceDataIsRejected() {
        val valid = rebuild()
        assertNotNull(BuiltInWakePhrase.parseReferences(valid))
        assertNull(BuiltInWakePhrase.parseReferences(valid.copyOf(valid.size / 2)))
        assertNull(BuiltInWakePhrase.parseReferences(ByteArray(16)))
    }

    @Test
    fun persistedIdsAreStable() {
        assertEquals(BuiltInWakePhrase.MANGO_TANGO, BuiltInWakePhrase.DEFAULT)
        assertEquals(BuiltInWakePhrase.MANGO_TANGO, BuiltInWakePhrase.fromId("mango_tango"))
        assertEquals(BuiltInWakePhrase.CIAO_LINDA, BuiltInWakePhrase.fromId("ciao_linda"))
        assertNull(BuiltInWakePhrase.fromId("something else"))
    }

    @Test
    fun switchingTheBuiltInPhraseChangesWhatIsDetected() {
        val detector = TemplateKeywordDetector()
        assertEquals(BuiltInWakePhrase.MANGO_TANGO, detector.builtInPhrase)
        val canonicalCiao = TemplateKeywordDetector.generateCanonicalCiaoLindaAudio(amplitude = 15000.0)
        fun best(frames: List<ShortArray>) = (frames + List(4) { ShortArray(800) }).maxOf { detector.processFrame(it, 0f, 0f).confidence }
        val asMango = best(canonicalCiao)
        detector.builtInPhrase = BuiltInWakePhrase.CIAO_LINDA
        detector.resetStream()
        val asCiao = best(canonicalCiao)
        assertTrue("ciao $asCiao vs mango $asMango", asCiao >= 0.9f && asMango < 0.5f)
        assertFalse(detector.hasCustomTemplate())
    }
}
