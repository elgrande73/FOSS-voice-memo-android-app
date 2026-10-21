package io.github.elgrande73.voicememo

import io.github.elgrande73.voicememo.AudioFixtures.Noise
import io.github.elgrande73.voicememo.transcription.WhisperNative
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Speed and accuracy of Whisper model files on identical audio (build host, real whisper.cpp through the app's JNI
 * bridge). Opt-in: set WHISPER_BENCH_DIR to a directory of ggml model files (see docs/TRANSCRIPTION.md for how they were
 * produced). Audio: the 21 synthetic sentences with a known text (eSpeak NG fixtures, 5 languages, 2–5 s), each as a
 * memo with 0.5 s of silence around it, clean and with pink noise at −45 dBFS. Greedy decoding, 4 threads; language
 * detected automatically (the app's default) and given. WER = word edit distance / reference words.
 */
class TranscriptionModelBenchmarkHostTest {

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

    private data class Case(val clip: String, val language: String, val text: String)

    private fun cases(): List<Case> = File(root, "app/src/test/resources/audio/generate_fixtures.sh").readLines().mapNotNull {
        Regex("""^gen (\S+) \d+ \d+ ((?:neg_speech|other_speech|neg_context|dev_conv|test_conv)\S*) "([^"]+)"""").find(it)
    }.map { Case(it.groupValues[2], it.groupValues[1].substringBefore('+').substringBefore('-'), it.groupValues[3]) }

    private fun words(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}' ]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }

    private fun editDistance(a: List<String>, b: List<String>): Int {
        val d = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in 0..a.size) d[i][0] = i
        for (j in 0..b.size) d[0][j] = j
        for (i in 1..a.size) for (j in 1..b.size) d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return d[a.size][b.size]
    }

    private fun memo(clip: String, noisy: Boolean): FloatArray {
        val speech = AudioFixtures.atPeak(AudioFixtures.load(clip), -6.0)
        val seconds = speech.size / 16000.0 + 1.0
        val pcm = AudioFixtures.scene(speech, seconds, 0.5, if (noisy) -45.0 else -90.0, if (noisy) Noise.PINK else Noise.NONE, clip.hashCode())
        return FloatArray(pcm.size) { pcm[it] / 32768f }
    }

    @Test
    fun modelsOnIdenticalAudio() {
        val dir = System.getenv("WHISPER_BENCH_DIR")?.let(::File)
        assumeTrue("set WHISPER_BENCH_DIR", dir?.isDirectory == true)
        val lib = File(root, "build/host-whisper-jni/lib/libvmwhisper.so")
        assumeTrue("host library not built", lib.exists())
        WhisperNative.loadFrom(lib.absolutePath, File(root, "build/host-whisper-jni/bin").absolutePath)
        val cases = cases()
        val threads = System.getenv("WHISPER_BENCH_THREADS")?.toInt() ?: 4
        for (model in dir!!.listFiles { f -> f.name.endsWith(".bin") }!!.sortedBy { it.name }) {
            val t0 = System.nanoTime()
            val handle = WhisperNative.init(model.absolutePath)
            val loadMs = (System.nanoTime() - t0) / 1e6
            check(handle != 0L) { "could not load $model" }
            val conditions = System.getenv("WHISPER_BENCH_CONDITIONS")?.split(",") ?: listOf("clean-auto", "clean-given", "noise-auto", "noise-given")
            for (noisy in listOf(false, true)) for (auto in listOf(true, false)) {
                if ("${if (noisy) "noise" else "clean"}-${if (auto) "auto" else "given"}" !in conditions) continue
                var errors = 0; var total = 0; var decodeMs = 0.0; var audioS = 0.0; var langOk = 0
                val perMemo = mutableListOf<Double>()
                for (case in cases) {
                    val audio = memo(case.clip, noisy)
                    val t = System.nanoTime()
                    val result = WhisperNative.transcribe(handle, audio, if (auto) "auto" else case.language, threads, 1)!!
                    val ms = (System.nanoTime() - t) / 1e6
                    decodeMs += ms; perMemo += ms; audioS += audio.size / 16000.0
                    if (result[0] == case.language) langOk++
                    if (System.getenv("WHISPER_BENCH_VERBOSE") == "1") {
                        println("MEASURE text model=${model.name} ${if (noisy) "noise" else "clean"} ${if (auto) "auto" else "given"} ${case.clip} [${result[0]}] ${result[1].trim()}")
                    }
                    errors += editDistance(words(case.text), words(result[1])); total += words(case.text).size
                }
                perMemo.sort()
                println(
                    "MEASURE bench model=${model.name} (${model.length() / 1_000_000} MB) load %.0f ms | %s %s | WER %.3f (%d/%d) | decode mean %.0f ms, median %.0f ms, max %.0f ms per memo (mean audio %.1f s), RTF %.3f | language %d/%d"
                        .format(loadMs, if (noisy) "noise-45" else "clean", if (auto) "auto-lang" else "given-lang", errors.toDouble() / total, errors, total,
                            decodeMs / cases.size, perMemo[perMemo.size / 2], perMemo.last(), audioS / cases.size, decodeMs / 1000 / audioS, langOk, cases.size)
                )
            }
            WhisperNative.free(handle)
        }
    }
}
