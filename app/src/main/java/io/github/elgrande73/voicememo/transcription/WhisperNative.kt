package io.github.elgrande73.voicememo.transcription

import android.content.Context
import android.util.Log

/**
 * JNI bindings of app/src/main/cpp/voicememo_whisper.cpp (whisper.cpp, built from source with the app).
 * All calls are blocking and must run off the main thread.
 */
object WhisperNative {
    private const val TAG = "WhisperNative"

    @Volatile
    private var state: Boolean? = null

    /** Loads the native libraries and registers the best CPU backend; false if they are missing (e.g. unsupported ABI). */
    @Synchronized
    fun load(context: Context): Boolean {
        state?.let { return it }
        val ok = try {
            System.loadLibrary("vmwhisper")
            loadBackends(context.applicationInfo.nativeLibraryDir)
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Speech-to-text library not available", e)
            false
        }
        state = ok
        return ok
    }

    /** For the JVM host test only (WhisperNativeHostTest): loads a library built by scripts/host-whisper-jni. */
    @android.annotation.SuppressLint("UnsafeDynamicallyLoadedCode")
    @Synchronized
    fun loadFrom(libraryPath: String, backendDirectory: String): Boolean {
        if (state == true) return true
        System.load(libraryPath)
        loadBackends(backendDirectory)
        state = true
        return true
    }

    external fun loadBackends(libDir: String)
    external fun systemInfo(): String
    /** Returns a handle, or 0 if the model could not be loaded (invalid file, not enough memory). */
    external fun init(modelPath: String): Long
    /** Loads a model stored uncompressed in the APK's assets (no copy on disk); 0 on failure. */
    external fun initFromAsset(assetManager: android.content.res.AssetManager, assetPath: String): Long
    external fun free(handle: Long)
    external fun requestAbort(handle: Long)
    /** Progress of the running transcription in percent (whisper.cpp's progress callback). */
    external fun progress(handle: Long): Int
    /** Returns [languageCode, text], or null on failure or abort. */
    external fun transcribe(handle: Long, samples: FloatArray, language: String, threads: Int, beamSize: Int): Array<String>?

    /** Short-snippet recognition for wake-phrase verification; [audioCtx] in 20 ms encoder frames (0 = 30 s). */
    /** Mean token log-probability of each of [phrases] for the snippet (forced decoding); null on failure. */
    external fun phraseLogProb(handle: Long, samples: FloatArray, language: String, threads: Int, audioCtx: Int, phrases: Array<String>): FloatArray?

    external fun recognizeSnippet(handle: Long, samples: FloatArray, language: String, threads: Int, audioCtx: Int): String?
}
