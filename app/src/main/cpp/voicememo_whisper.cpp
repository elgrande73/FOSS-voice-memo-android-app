// JNI bridge between Voice Memo (io.github.elgrande73.voicememo.transcription.WhisperNative) and whisper.cpp.
// Audio never leaves the device: samples come from a local WAV file and the text is returned to the app.
#include <jni.h>
#ifdef __ANDROID__
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "VoiceMemoWhisper", __VA_ARGS__)
#else
#include <cstdio>
#define LOGW(...) std::fprintf(stderr, __VA_ARGS__)
#endif
#include <atomic>
#include <string>
#include <vector>
#include <cmath>
#include <algorithm>
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

#ifdef __ANDROID__
// Reads the bundled model directly from the (uncompressed) APK asset, so it is not stored a second time
size_t asset_read(void *ctx, void *output, size_t size) {
    auto *asset = static_cast<AAsset *>(ctx);
    size_t done = 0;
    while (done < size) {
        int n = AAsset_read(asset, static_cast<char *>(output) + done, size - done);
        if (n <= 0) break;
        done += static_cast<size_t>(n);
    }
    return done;
}

bool asset_eof(void *ctx) {
    return AAsset_getRemainingLength64(static_cast<AAsset *>(ctx)) <= 0;
}

void asset_close(void *ctx) {
    AAsset_close(static_cast<AAsset *>(ctx));
}
#endif

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

JNIEXPORT jlong JNICALL
Java_io_github_elgrande73_voicememo_transcription_WhisperNative_initFromAsset(
        JNIEnv *env, jobject, jobject assetManager, jstring assetPath) {
#ifdef __ANDROID__
    AAssetManager *manager = AAssetManager_fromJava(env, assetManager);
    if (manager == nullptr) return 0;
    AAsset *asset = AAssetManager_open(manager, to_string(env, assetPath).c_str(), AASSET_MODE_STREAMING);
    if (asset == nullptr) return 0;
    whisper_model_loader loader = {};
    loader.context = asset;
    loader.read = asset_read;
    loader.eof = asset_eof;
    loader.close = asset_close;
    return wrap(whisper_init_with_params(&loader, context_params()));
#else
    (void) env; (void) assetManager; (void) assetPath;
    return 0;
#endif
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

/**
 * Recognizes a short snippet (wake-phrase verification): greedy decoding, one segment, no context from earlier calls,
 * and an encoder context reduced to the snippet length (audioCtx, in 20 ms encoder frames; 0 = full 30 s window).
 * Returns the text, or null on failure.
 */
JNIEXPORT jstring JNICALL
Java_io_github_elgrande73_voicememo_transcription_WhisperNative_recognizeSnippet(
        JNIEnv *env, jobject, jlong handle, jfloatArray samples, jstring language, jint threads, jint audioCtx) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr || session->ctx == nullptr) return nullptr;
    session->abort.store(false);

    const jsize count = env->GetArrayLength(samples);
    std::vector<float> pcm(count);
    env->GetFloatArrayRegion(samples, 0, count, pcm.data());
    const std::string lang = to_string(env, language);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.language = lang.empty() ? "auto" : lang.c_str();
    params.detect_language = false;
    params.translate = false;
    params.no_context = true;
    params.single_segment = true;
    params.no_timestamps = true;
    params.max_tokens = 16;
    params.audio_ctx = audioCtx > 0 ? audioCtx : 0;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.suppress_blank = true;
    params.suppress_nst = true;
    params.temperature_inc = 0.0f; // no fallback re-decoding: bounded latency
    params.abort_callback = abort_callback;
    params.abort_callback_user_data = session;

    if (whisper_full(session->ctx, params, pcm.data(), count) != 0 || session->abort.load()) return nullptr;
    std::string text;
    const int segments = whisper_full_n_segments(session->ctx);
    for (int i = 0; i < segments; ++i) text += whisper_full_get_segment_text(session->ctx, i);
    return env->NewStringUTF(text.c_str());
}

/**
 * Wake-phrase verification by forced decoding: encodes the snippet (encoder context reduced to audioCtx) and returns,
 * for each candidate spelling in phrases, the mean log-probability the decoder assigns to exactly that text
 * (teacher forcing after <|startoftranscript|><|lang|><|transcribe|><|notimestamps|>). Higher = the audio is more
 * likely that phrase. Element 0 of the result is the mean log-probability of the model's own greedy transcription of the
 * snippet (the reference for a likelihood ratio); elements 1..n belong to phrases. Returns null on failure.
 */
JNIEXPORT jfloatArray JNICALL
Java_io_github_elgrande73_voicememo_transcription_WhisperNative_phraseLogProb(
        JNIEnv *env, jobject, jlong handle, jfloatArray samples, jstring language, jint threads, jint audioCtx,
        jobjectArray phrases) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr || session->ctx == nullptr) return nullptr;
    whisper_context *ctx = session->ctx;
    session->abort.store(false);

    const jsize count = env->GetArrayLength(samples);
    std::vector<float> pcm(count);
    env->GetFloatArrayRegion(samples, 0, count, pcm.data());
    const std::string lang = to_string(env, language);

    // Encoder pass (and a one-token greedy decode) through whisper_full, which applies the reduced audio context
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.language = lang.empty() ? "en" : lang.c_str();
    params.detect_language = false;
    params.no_context = true;
    params.single_segment = true;
    params.no_timestamps = true;
    params.max_tokens = 12;
    params.audio_ctx = audioCtx > 0 ? audioCtx : 0;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.suppress_blank = true;
    params.suppress_nst = true;
    params.temperature_inc = 0.0f;
    params.abort_callback = abort_callback;
    params.abort_callback_user_data = session;
    if (whisper_full(ctx, params, pcm.data(), count) != 0 || session->abort.load()) return nullptr;

    // Free (greedy) transcription: mean log-probability of its text tokens
    double free_sum = 0.0;
    int free_n = 0;
    const whisper_token eot = whisper_token_eot(ctx);
    for (int seg = 0; seg < whisper_full_n_segments(ctx); ++seg) {
        for (int t = 0; t < whisper_full_n_tokens(ctx, seg); ++t) {
            const whisper_token_data data = whisper_full_get_token_data(ctx, seg, t);
            if (data.id >= eot) continue; // special tokens
            free_sum += data.plog;
            ++free_n;
        }
    }

    int lang_id = whisper_lang_id(lang.empty() ? "en" : lang.c_str());
    if (lang_id < 0) lang_id = whisper_lang_id("en");
    const std::vector<whisper_token> prefix = {
            whisper_token_sot(ctx), whisper_token_lang(ctx, lang_id), whisper_token_transcribe(ctx), whisper_token_not(ctx)};
    const int n_vocab = whisper_n_vocab(ctx);

    const jsize n_phrases = env->GetArrayLength(phrases);
    std::vector<float> result(n_phrases + 1, -1e9f);
    result[0] = free_n > 0 ? (float) (free_sum / free_n) : -1e9f;
    for (jsize p = 0; p < n_phrases; ++p) {
        auto *jtext = (jstring) env->GetObjectArrayElement(phrases, p);
        const std::string text = to_string(env, jtext);
        env->DeleteLocalRef(jtext);
        whisper_token tokens[64];
        const int n = whisper_tokenize(ctx, text.c_str(), tokens, 64);
        if (n <= 0) continue;
        if (whisper_decode(ctx, prefix.data(), (int) prefix.size(), 0, threads) != 0) return nullptr;
        double sum = 0.0;
        int n_past = (int) prefix.size();
        for (int k = 0; k < n; ++k) {
            const float *logits = whisper_get_logits(ctx);
            float max_logit = logits[0];
            for (int v = 1; v < n_vocab; ++v) max_logit = std::max(max_logit, logits[v]);
            double z = 0.0;
            for (int v = 0; v < n_vocab; ++v) z += std::exp((double) (logits[v] - max_logit));
            sum += (double) (logits[tokens[k]] - max_logit) - std::log(z);
            if (k + 1 < n && whisper_decode(ctx, &tokens[k], 1, n_past++, threads) != 0) return nullptr;
        }
        result[p + 1] = (float) (sum / n);
    }
    jfloatArray out = env->NewFloatArray(n_phrases + 1);
    env->SetFloatArrayRegion(out, 0, n_phrases + 1, result.data());
    return out;
}

}  // extern "C"
