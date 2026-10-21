package io.github.elgrande73.voicememo.wakephrase

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wake phrases the app can detect without enrolled samples. Enrolled samples (Settings) always take precedence.
 *
 * [id] is persisted (PreferenceKeys.BUILT_IN_WAKE_PHRASE): never change it. New installations use [DEFAULT];
 * installations that existed before settings v3 keep [CIAO_LINDA], the default of earlier versions (SettingsMigrator).
 */
enum class BuiltInWakePhrase(val id: String, val displayName: String) {
    /** Default since settings v3: two distinct, easy words that are rare in ordinary speech (see docs). */
    MANGO_TANGO("mango_tango", "Mango Tango"),
    /** Default of earlier versions, kept for existing installations. */
    CIAO_LINDA("ciao_linda", "Ciao Linda");

    /**
     * Reference feature sequences used when no sample is enrolled ([WakePhraseFrontEnd] features, FrontEndConfig.DEFAULT).
     * Mango Tango: synthetic speech of several voices, see [MANGO_TANGO_RESOURCE]. Ciao Linda: the legacy synthesized
     * formant sequence ([TemplateKeywordDetector.generateCanonicalCiaoLindaAudio]).
     */
    fun referenceFeatures(): List<List<FloatArray>> = when (this) {
        MANGO_TANGO -> mangoTango
        CIAO_LINDA -> ciaoLinda
    }

    companion object {
        val DEFAULT = MANGO_TANGO

        /**
         * Bundled reference of "Mango Tango": features (no audio) of five eSpeak NG renditions, rebuilt from the test
         * fixtures `mango_ref_*.pcm` and checked by BuiltInWakePhraseTest (provenance: app/src/test/resources/audio).
         */
        const val MANGO_TANGO_RESOURCE = "/wakephrase/mango_tango.bin"

        /** 'VMBP' little endian: count, then (length, template bytes) per reference. */
        const val MAGIC_BUILT_IN_PHRASE = 0x50424D56

        fun fromId(id: String?): BuiltInWakePhrase? = entries.firstOrNull { it.id == id }

        private val mangoTango: List<List<FloatArray>> by lazy {
            val bytes = BuiltInWakePhrase::class.java.getResourceAsStream(MANGO_TANGO_RESOURCE)?.use { it.readBytes() }
            checkNotNull(bytes?.let(::parseReferences)) { "Built-in wake phrase reference missing or damaged" }
        }

        private val ciaoLinda: List<List<FloatArray>> by lazy {
            val frontEnd = WakePhraseFrontEnd(FrontEndConfig.DEFAULT)
            listOf(TemplateKeywordDetector.generateCanonicalCiaoLindaAudio(sampleRate = 16000, amplitude = 12000.0).map { frontEnd.process(it) })
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
}
