// JNI bridge between Voice Memo (io.github.elgrande73.voicememo.transcription.WhisperNative) and whisper.cpp.
// Audio never leaves the device: samples come from a local WAV file and the text is returned to the app.
#include <jni.h>
#ifdef __ANDROID__
#include <android/log.h>
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "VoiceMemoWhisper", __VA_ARGS__)
#else
#include <cstdio>
#define LOGW(...) std::fprintf(stderr, __VA_ARGS__)
#endif
#include <atomic>
#include <string>
#include <vector>
#include "whisper.h"
#include "ggml-backend.h"


namespace {

struct Session {
    whisper_context *ctx = nullptr;
    std::atomic<bool> abort{false};
    std::atomic<int> progress{0};
};

void progress_callback(whisper_context *, whisper_state *, int progress, void *user_data) {
    static_cast<Session *>(user_data)->progress.store(progress);
}

bool abort_callback(void *user_data) {
    return static_cast<Session *>(user_data)->abort.load();
}

jlong wrap(whisper_context *ctx) {
    if (ctx == nullptr) return 0;
    auto *session = new Session();
    session->ctx = ctx;
    return reinterpret_cast<jlong>(session);
}

whisper_context_params context_params() {
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    params.flash_attn = false;
    return params;
}

std::string to_string(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_io_github_elgrande73_voicememo_transcription_WhisperNative_loadBackends(JNIEnv *env, jobject, jstring libDir) {
    // Registers the best CPU backend variant found in the app's native library directory
    ggml_backend_load_all_from_path(to_string(env, libDir).c_str());
}

JNIEXPORT jstring JNICALL
Java_io_github_elgrande73_voicememo_transcription_WhisperNative_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

JNIEXPORT jlong JNICALL
Java_io_github_elgrande73_voicememo_transcription_WhisperNative_init(JNIEnv *env, jobject, jstring modelPath) {
    return wrap(whisper_init_from_file_with_params(to_string(env, modelPath).c_str(), context_params()));
}

JNIEXPORT void JNICALL
Java_io_github_elgrande73_voicememo_transcription_WhisperNative_free(JNIEnv *, jobject, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr) return;
    whisper_free(session->ctx);
    delete session;
}

JNIEXPORT jint JNICALL
Java_io_github_elgrande73_voicememo_transcription_WhisperNative_progress(JNIEnv *, jobject, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);
    return session != nullptr ? session->progress.load() : 0;
}

JNIEXPORT void JNICALL
Java_io_github_elgrande73_voicememo_transcription_WhisperNative_requestAbort(JNIEnv *, jobject, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session != nullptr) session->abort.store(true);
}

/**
 * Transcribes 16 kHz mono float samples. Returns {languageCode, text}, or null if whisper failed or was aborted.
 * language: ISO code, or "auto" for detection. beamSize <= 1 selects greedy decoding.
 */
JNIEXPORT jobjectArray JNICALL
Java_io_github_elgrande73_voicememo_transcription_WhisperNative_transcribe(
        JNIEnv *env, jobject, jlong handle, jfloatArray samples, jstring language, jint threads, jint beamSize) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr || session->ctx == nullptr) return nullptr;
    session->abort.store(false);
    session->progress.store(0);

    const jsize count = env->GetArrayLength(samples);
    std::vector<float> pcm(count);
    env->GetFloatArrayRegion(samples, 0, count, pcm.data());
    const std::string lang = to_string(env, language);

    whisper_full_params params = whisper_full_default_params(
            beamSize > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    if (beamSize > 1) params.beam_search.beam_size = beamSize;
    params.n_threads = threads;
    params.language = lang.empty() ? "auto" : lang.c_str();
    params.detect_language = false;
    params.translate = false;
    params.no_timestamps = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.suppress_blank = true;
    // Avoid invented annotations ("[Music]") for silence or noise
    params.suppress_nst = true;
    params.progress_callback = progress_callback;
    params.progress_callback_user_data = session;
    params.abort_callback = abort_callback;
    params.abort_callback_user_data = session;

    if (whisper_full(session->ctx, params, pcm.data(), count) != 0 || session->abort.load()) {
        if (!session->abort.load()) LOGW("whisper_full failed");
        return nullptr;
    }

    std::string text;
    const int segments = whisper_full_n_segments(session->ctx);
    for (int i = 0; i < segments; ++i) text += whisper_full_get_segment_text(session->ctx, i);
    const int lang_id = whisper_full_lang_id(session->ctx);
    const char *code = lang_id >= 0 ? whisper_lang_str(lang_id) : "";

    jobjectArray result = env->NewObjectArray(2, env->FindClass("java/lang/String"), nullptr);
    env->SetObjectArrayElement(result, 0, env->NewStringUTF(code != nullptr ? code : ""));
    env->SetObjectArrayElement(result, 1, env->NewStringUTF(text.c_str()));
    return result;
}

}  // extern "C"
