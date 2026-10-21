package io.github.elgrande73.voicememo.wakephrase

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The wake phrase the app detects without enrolled samples: **"Mango Tango"**. Enrolled samples (Settings) always take
 * precedence; with them the user can use any other phrase.
 */
object BuiltInWakePhrase {
    const val DISPLAY_NAME = "Mango Tango"

    /**
     * Bundled reference: features (no audio) of five eSpeak NG renditions, rebuilt from the test fixtures
     * `mango_ref_*.pcm` and checked by BuiltInWakePhraseTest (provenance: app/src/test/resources/audio).
     */
    const val RESOURCE = "/wakephrase/mango_tango.bin"

    /** 'VMBP' little endian: count, then (length, template bytes) per reference. */
    const val MAGIC_BUILT_IN_PHRASE = 0x50424D56

    /** Reference feature sequences ([WakePhraseFrontEnd] features, FrontEndConfig.DEFAULT), matched like samples. */
    val referenceFeatures: List<List<FloatArray>> by lazy {
        val bytes = BuiltInWakePhrase::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes() }
        checkNotNull(bytes?.let(::parseReferences)) { "Built-in wake phrase reference missing or damaged" }
    }

    fun serializeReferences(references: List<List<FloatArray>>): ByteArray {
        val templates = references.map { TemplateKeywordDetector.serialize(it) }
        val buffer = ByteBuffer.allocate(8 + templates.sumOf { 4 + it.size }).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(MAGIC_BUILT_IN_PHRASE).putInt(templates.size)
        templates.forEach { buffer.putInt(it.size).put(it) }
        return buffer.array()
    }

    fun parseReferences(bytes: ByteArray): List<List<FloatArray>>? = try {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.int != MAGIC_BUILT_IN_PHRASE) {
            null
        } else {
            val count = buffer.int
            if (count !in 1..16) {
                null
            } else {
                val references = List(count) {
                    val template = ByteArray(buffer.int).also { buffer.get(it) }
                    TemplateKeywordDetector.parseFeatures(template) ?: return null
                }
                references.takeIf { list -> list.all { ref -> ref.all { v -> v.none { it.isNaN() || it.isInfinite() } } } }
            }
        }
    } catch (_: Exception) {
        null
    }
}
