package io.github.elgrande73.voicememo.transcription

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Pluggable interface for Speech-To-Text / Transcription engines.
 */
interface SpeechToTextEngine {
    suspend fun transcribeAudioFile(audioFile: File): String?
    fun isAvailable(): Boolean
}

/**
 * Optional transcription through the device's system speech recognition service (disabled by default).
 * The service is provided by the device, not by this app; offline recognition is requested but whether it is honored
 * depends on that service.
 */
class AndroidOnDeviceSpeechEngine(private val context: Context) : SpeechToTextEngine {

    override fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    override suspend fun transcribeAudioFile(audioFile: File): String? = withContext(Dispatchers.Main) {
        if (!audioFile.exists() || audioFile.length() < 100) return@withContext null
        if (!isAvailable()) {
            Log.w("SpeechEngine", "Speech recognition service is not available on this device")
            return@withContext null
        }

        try {
            suspendCancellableCoroutine { continuation ->
                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    // Ask the system recognition service not to send audio over the network
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        Log.d("SpeechEngine", "Recognition error code: $error")
                        try {
                            recognizer.destroy()
                        } catch (_: Exception) {}
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()
                        try {
                            recognizer.destroy()
                        } catch (_: Exception) {}
                        if (continuation.isActive) {
                            continuation.resume(text)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                continuation.invokeOnCancellation {
                    try {
                        recognizer.destroy()
                    } catch (_: Exception) {}
                }

                // If intent can be dispatched to recognizer
                recognizer.startListening(intent)
            }
        } catch (e: Exception) {
            Log.e("SpeechEngine", "Failed to start speech recognition", e)
            null
        }
    }
}
