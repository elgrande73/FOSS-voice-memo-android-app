# On-device transcription and model choices

Voice Memo transcribes saved memos on the phone with **whisper.cpp** (built from source) and the **OpenAI Whisper
"base"** model. The model is **not part of the app**: the user downloads it once with *Settings → On-device
transcription → Download Whisper Base model* (section 3). That download is the only network use of the app (the
`INTERNET` permission exists for it); transcription itself runs offline, and no audio or text leaves the device. Wake-phrase detection stays a separate, lightweight detector
(see `WAKE_PHRASE_DETECTION.md`); the transcription model is loaded only when a memo is transcribed.

All numbers below are **measured on the build machine** (x86-64 Intel Xeon, 4 cores) with **synthetic
speech** (eSpeak NG), unless stated otherwise. They show relative differences, not phone performance or real-world
accuracy. No phone or emulator was available for this work.

## 1. Architecture

```
AudioRecord ─► AudioMonitoringService capture loop (one microphone session)
   ├─ LevelMeter ─► live level on the dashboard (10/s)
   ├─ MemoTriggerDetector + DTW wake-phrase matcher (continuous, ~0.06 ms per 50 ms frame)
   └─ memo frames ─► WAV file (atomic write) ─► Room row
                                    │ (user taps Transcribe, or automatic transcription is on)
                                    ▼
TranscriptionManager (queue, one worker thread, foreground service while it works)
   ─► WavReader (16 kHz mono floats) ─► silence check ─► WhisperTranscriber ─► JNI (libvmwhisper)
   ─► whisper.cpp / ggml (CPU backend chosen at run time) ─► text ─► Room (status COMPLETED / FAILED)
```

- The model is loaded on the first transcription and released 60 s after the queue is empty.
- Up to 4 decoding threads: all cores but one, and all but two while monitoring runs (capture and wake-phrase detection
  need about 0.1 ms of CPU per 50 ms frame on the build host). Earlier versions used only 2 threads during monitoring,
  which made decoding about 1.8× slower (measured below). ggml's worker threads inherit the background priority.
- The model is the downloaded file (or an imported one) in app-private storage; without one, a transcription request
  fails at once with "No speech model is installed…" and a *Download speech model* action (never an endless "waiting").
- arm64: ggml's CPU backend is built in seven variants (ARMv8.0 … ARMv9.2 with dot-product, fp16, int8-matmul, SVE/SME)
  and the best one the CPU supports is loaded at run time, so old Cortex-A53 phones work and newer phones use faster
  instructions. armeabi-v7a and x86_64 get one generic variant.
- Cancellation uses whisper.cpp's abort callback; it takes effect at the next processing step
  (measured: 2.3–2.5 s on the host for a 40 s recording).

### Data handling
- Transcripts are stored in the memo's existing database row (`transcription_text`, `transcription_status`); no schema
  change. Status values: `SKIPPED` (not transcribed), `PENDING` (automatic transcription was on when saved),
  `COMPLETED`, `FAILED`. A queued memo is stored as `PENDING` and re-queued when the app starts again
  (`TranscriptionManager.resumePending`); "running" is kept in memory only, so a memo can never stay "transcribing"
  after the app was closed.
- Explicit states in History, each reported when it happens (never estimated):
  *Waiting to be transcribed…* → *Preparing audio…* → *Loading speech model…* (only when the model is not loaded yet)
  → *Transcribing on this device…* → *Saving transcript…*, each with the elapsed time and the memo's length, then
  *Transcribed m:ss of audio in m:ss.*, *Transcription cancelled.* or *Transcription failed: reason* with Retry.
  A percentage is shown only for memos longer than 30 s: whisper.cpp reports the share of audio already decoded per
  30 s window, so for shorter memos it jumps from 0 to 100 and would be meaningless. The notification shows the same
  stage, with a progress bar only when there is a real percentage. Cancel is available while waiting or running; a
  retry right after a cancel is not lost, and a memo is never queued twice.
- Concurrency: one memo at a time, in request order (one worker); the watchdog covers model loading and decoding.

### Reliability (fix for memos stuck at "Transcribing…" / "Waiting…")
Traced causes, all fixed (no device was available to reproduce; fixes verified with unit tests and real inference on
the host, `TranscriptionEndToEndHostTest`):
1. Errors that are not exceptions (e.g. `OutOfMemoryError`, `UnsatisfiedLinkError`) escaped the queue loop, which then
   stopped for good, leaving every later memo "waiting". Every job now ends in a state; the loop survives any error.
2. The worker thread used `THREAD_PRIORITY_BACKGROUND`, which on Android also moves it into the background CPU group
   (a small share of a little core): minutes per memo. It now uses a normal-low priority.
3. With the app in the background the process could be frozen or killed mid-run. Transcription now runs in a foreground
   service (`TranscriptionService`, type mediaProcessing on Android 15+, dataSync before) with progress and Cancel in
   its notification; it stops itself when the queue is empty.
4. A watchdog stops a run that exceeds max(5 min, 40 × audio length) with an explanation (try "Fast" or a smaller model).
5. Several copies of the static C++ runtime in the native libraries (`libc++_static`) → `c++_shared`.
6. Ordering of status writes (queued/cancelled/restored) is serialized, so a late write can no longer overwrite a newer
   state. Deleting or exporting a memo during a job is safe: deletion cancels the job, and a job whose memo or file
   disappeared ends without writing anything (export only reads the file).
- A failed run sets `FAILED` and keeps any previous text; a cancelled run changes nothing. The WAV file is only read.
- Users can transcribe, retry, cancel, edit and delete transcripts (deleting the text keeps the recording).
- Silent memos (no 50 ms frame ≥ −55 dBFS) are not sent to the model, because Whisper invents text for silence
  (measured: 3 s of digital silence produced "[Musica]" before non-speech tokens were suppressed). Bracketed
  annotations such as "[Music]" are removed from transcripts.
- The previous transcription path (Android's system `SpeechRecognizer`, which listened to the microphone instead of
  transcribing the saved file and could be proprietary/online) was removed, along with its `<queries>` manifest entry.

## 2. Engine and model evaluation

### Keyword spotting (wake phrase): sherpa-onnx KWS — rejected
- Model: `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01` (int8, ~5 MB; English GigaSpeech BPE). Run on the
  host with the sherpa-onnx Python package on the **identical** 240 benchmark recordings exported from
  `WakePhraseBenchmark` (target, near-miss and conversation clips in 6 noise/level conditions).
- The default wake phrase of that time (Italian, two spellings as keywords), thresholds 0.25 / 0.10 / 0.03: **0
  detections of 72 target clips** (and 0 false positives). Control: the model detected its own test keywords on its test
  recordings and "HELLO WORLD" spoken by eSpeak's English voice, so the harness works; the English model did not
  recognise the Italian phrase.
- It would also add the onnxruntime native library (size not measured here; typically >10 MB per ABI), which sherpa-onnx's
  Android build scripts download as a prebuilt binary.
- Decision: keep the existing template matcher, which is personalised by the 1–5 enrolled samples and was improved in
  this change (start/stop consistency, see `WAKE_PHRASE_DETECTION.md`).

### Transcription: whisper.cpp vs. sherpa-onnx
| | whisper.cpp (chosen) | sherpa-onnx + onnxruntime |
|---|---|---|
| Build | C/C++ from source with the NDK (git submodule) | sherpa-onnx from source, onnxruntime as prebuilt library (F-Droid precedent: Anti-Vocale builds sherpa-onnx in its recipe) |
| Native size (arm64, release, all CPU variants) | 8.0 MB (measured, uncompressed in the APK) | not measured; onnxruntime alone is typically >10 MB per ABI (estimate) |
| Whisper tiny model | 32 MB (q5_1) | 103 MB (int8 encoder + decoder) |
| Whisper base model | 55 MB (q5_0) | 160 MB (int8) |
| Italian-capable small models | Whisper (all sizes) | Whisper; NeMo multilingual models are larger |

Model files were obtainable here only from GitHub (Hugging Face and openaipublic.azureedge.net were blocked by the
build environment's network policy). The official ggml files are on Hugging Face; the previously bundled file was therefore
**rebuilt from the sherpa-onnx ONNX export** of the same OpenAI weights (`scripts/whisper-model/`). Validation: the
rebuilt tiny/base models transcribe the reference recordings shipped with the ONNX models with the same text as the
ONNX models (one-word differences typical of quantization), all 167/245 tensors of the original checkpoints are
present exactly once, and the q5_0 base file has the size of the official one.

### Model size / quality (host, eSpeak NG synthetic speech, 14 sentences: 8 Italian, 4 English, 2 German)
Word error rate (WER) with the language set, beam search 5 unless noted; RTF = processing time / audio duration
including model loading (host).

| Model | Size | WER all | Italian | English | German | RTF |
|---|---|---|---|---|---|---|
| tiny q5_1 | 32 MB | 0.661 | 0.941 | 0.256 | 0.471 | 0.30 |
| tiny q5_1, greedy | 32 MB | 0.710 | 0.868 | 0.436 | 0.706 | 0.19 |
| base q4_0 | 46 MB | 0.419 | 0.676 | 0.103 | 0.118 | 0.36 |
| **base q5_0 (previously bundled)** | **55 MB** | **0.371** | **0.603** | 0.103 | 0.059 | 0.55 |
| base q5_1 | 60 MB | 0.395 | 0.632 | 0.128 | 0.059 | 0.50 |
| base q5_1, greedy | 60 MB | 0.476 | 0.721 | 0.179 | 0.176 | 0.37 |
| base q5_1, automatic language | 60 MB | 0.476 | 0.779 | 0.128 | 0.059 | 0.82 |

- eSpeak's Italian voice is recognised poorly by every model; real Italian speech is expected to do much better, but
  this was **not measured** (no real recordings were available). English synthetic speech is close to correct.
- Reducing whisper's 30 s audio context for short memos saved little time and increased errors (base: 0.395 → 0.484 with
  +3 s context) and is not used.
- Choosing the language beats automatic detection, hence the language setting (default since settings v6: fixed
  English, section "Transcription language").
- Quality setting: *Accurate* = beam search 5 (default), *Fast* = greedy (~30 % faster, more errors).

### Memory (host, base q5_0)
whisper.cpp reports 54.7 MB model, 28 MB KV caches and ~140 MB compute buffers; measured peak resident memory of the
process: **193 MB with beam search, 148 MB greedy** (3.6 s memo). On a phone the model is mapped from the APK; similar
totals are expected but **not measured**. If loading fails (e.g. not enough memory) the memo is marked failed with a
message and the audio is kept.

### JNI integration test (host)
`WhisperNativeHostTest` runs the real JNI bridge with Whisper base (q5_0 file from
`scripts/whisper-model/build-bundled-model.sh`) on the JVM (after `scripts/host-whisper-jni/build.sh`; generic x86-64
CPU backend, so slower than the numbers above):
load 92–114 ms; 3 s clips in 1.4–1.7 s with 4 threads and beam search; WER 0.571 on two English + two Italian
synthetic sentences (English 2 of 15 words wrong); language detection returns "en" for English; abort ends a
40 s transcription within 2.5 s; an invalid model file is rejected. `TranscriptionEndToEndHostTest` runs the whole
app path on the host: the model is "downloaded" with the app's `ModelDownloader` from a local HTTP server (SHA-1 check,
header check, test load: 0.4 s for 55 MB), registered, and three memos are transcribed by `TranscriptionManager` into a
Room database (1.9–2.2 s: "Can you send me the file later?"; a damaged WAV fails with an explanation; a silent one gets
an empty transcript); the results are still there after reopening the database.

### Performance: why a 4 s memo took over a minute, and what changed

Measured on the build host with the app's JNI bridge and whisper.cpp (Whisper base, greedy "Fast" decoding, 4 threads).
The host library is built without CPU-specific variants (`GGML_NATIVE=OFF`, generic x86-64 SIMD), so absolute times
are only indicative; phones were not available. All runs in this section use identical audio.

**1. Unoptimised native code in debug builds (main cause).** The Android Gradle plugin builds the C/C++ code of debug
variants with `CMAKE_BUILD_TYPE=Debug`, i.e. with no `-O` flag (`-O0`), and release variants with `RelWithDebInfo`
(`-O2`). whisper.cpp/ggml at `-O0` is extremely slow:

| Native build of the same engine | 4 s memo, warm (decode) | Model load |
|---|---|---|
| Debug (`-O0`, what debug APKs contained) | 34.0 s | 0.84 s |
| RelWithDebInfo (`-O2`, release APKs) | 2.2 s | 0.54 s |
| Release (`-O3`) | 1.5 s | 0.63 s |

A phone several times slower than the host therefore needed over a minute per short memo with a debug APK. Fix:
`app/src/main/cpp/CMakeLists.txt` compiles the native code with `-O3 -DNDEBUG` in every build type (debug info `-g`
is kept; packaged libraries are stripped). Verified in the generated arm64 build files.

**2. The first 30 s window was encoded twice with automatic language detection** (then the default language setting).
`whisper_full()` encodes window 0 to detect the language and then encodes the identical window again to transcribe it;
for a short memo that is most of the work. `app/src/main/cpp/whisper_patches.cmake` patches a copy of the pinned
whisper.cpp at build time (the submodule stays unmodified) to reuse that encoding when audio, offset and audio context
are unchanged; configuration fails if the source no longer matches, so a submodule update cannot silently drop it.

| 21 memos, 2–5 s each (mean 3.7 s), language "Automatic" | Mean decode | Median | Transcripts |
|---|---|---|---|
| Before (two encoder passes) | 3.40 s | 3.11 s | — |
| After (one encoder pass) | 1.93 s | 1.58 s | identical, word for word (42 memos, clean and noisy) |
| For comparison: language given | 1.67 s | 1.51 s | — |

**3. More decoding threads while monitoring runs** (earlier change, see the architecture section): 4 instead of 2
threads decode about 1.8× faster.

**Expected effect on a phone** (not measured, no device): a debug build was about 22× too slow from point 1 alone;
release builds gain about 1.45× from `-O3`; memos with automatic language detection need about 1.75× less time; all
multiply. The target of 2–5 s for a 4 s memo on a mid-range phone after the model is loaded is plausible but must be
confirmed on a device (`TranscriptionManager` logs the stage durations of every run).

**Model and runtime:** unchanged — whisper.cpp v1.9.5 (MIT, pinned submodule), CPU backend chosen at run time, Whisper
base multilingual f16 (`ggml-base.bin`, MIT, downloaded on request). No new library, no GPU/NNAPI code, no new download.

**Alternatives measured and not adopted** (`TranscriptionModelBenchmarkHostTest`, set `WHISPER_BENCH_DIR` to a folder
of model files; 21 synthetic sentences in 5 languages with known text, 2–5 s, clean and with pink noise at −45 dBFS;
language given unless noted; models built with `scripts/whisper-model/` steps from the public sherpa-onnx exports):

| Model file | Size | Load | Mean decode | WER clean | WER noisy |
|---|---|---|---|---|---|
| base f16 (the app's model) | 147 MB | 0.17–1.0 s | 1.65 s | 0.303 | 0.282 |
| base q8_0 | 81 MB | 0.13 s | 1.41 s | 0.287 | 0.287 |
| base q5_0 | 55 MB | 0.60 s | 1.75 s | 0.297 | 0.308 |
| tiny f16 | 77 MB | 0.10 s | 0.78 s | 0.344 | 0.344 |
| tiny q8_0 | 43 MB | 0.09 s | 0.66 s | 0.333 | 0.338 |
| tiny q5_0 | 29 MB | 0.06 s | 0.77 s | 0.364 | 0.333 |

- **Whisper tiny:** about 2× faster but clearly less accurate (WER 0.33–0.36 vs 0.28–0.30); with the fixes above base
  is expected to be fast enough, so base stays the only offered model.
- **base q8_0:** 14 % faster on the host with equal accuracy and half the memory. whisper.cpp publishes no checksum for
  quantised files (the download must stay pinned to the published SHA-1), so it would need on-device quantisation;
  left as a measured follow-up (users can already import a quantised file in Settings).
- **Flash attention:** 15 % faster on the host but WER 0.303 → 0.323 with changed transcripts; not adopted.
- **Shorter encoder context (`audio_ctx`)** for short memos: WER 0.32 → 0.79–1.0 and slower; not adopted.
- Automatic language detection misidentified 4–5 of 21 synthetic sentences (WER 0.33 vs 0.30 with the language given);
  choosing the language in Settings remains slightly more accurate.

### Transcription language: fixed by default (settings v6)

**Flow.** Settings stores a mode and a fixed language (`transcriptionLanguageMode` = `FIXED`/`AUTO`,
`transcriptionFixedLanguage` = whisper.cpp code). `Transcription.settings` reads them; `WhisperTranscriber` resolves
the effective language for the loaded model with `WhisperLanguages.resolve` (the same function produces the
"Effective configuration" in Settings) and passes it to the JNI bridge, which sets `whisper_full_params.language` and
calls `whisper_full`.

**What whisper.cpp v1.9.5 does** (checked in `third_party/whisper.cpp/src/whisper.cpp`):
- `whisper_full_default_params()` sets `language = "en"` and `detect_language = false`.
- Language identification runs only if `language` is null, empty or `"auto"`, or `detect_language` is true
  (`whisper_full_with_state`); `detect_language = true` would *only* detect and return without transcribing. So a
  language code alone skips identification; no further flag is needed, and the bridge keeps `detect_language = false`.
- For a multilingual model the code is turned into the prompt token with `whisper_lang_id()`. An unknown code returns
  −1 and would silently become a wrong token, and ids beyond the model's language tokens (`n_vocab − 51765 − 1`, i.e.
  99 languages for base; Cantonese `yue`, id 99, exists only in large-v3) are invalid too. The bridge refuses both
  (`transcribe` returns null) and the app reports "The speech model does not support the language …" instead of
  substituting another language.
- English-only models (`whisper_is_multilingual() == 0`) add no language token; the app always passes `"en"` for them
  (automatic detection would be meaningless).
- The language list in Settings is whisper.cpp's own table (`whisper_lang_max_id`, `whisper_lang_str`,
  `whisper_lang_str_full`, read through JNI once the library is loaded), limited to the languages of the selected model;
  a built-in copy of the v1.9.5 table is used before the library is loaded and is checked against the submodule source
  and the native library in tests. Armenian (`hy`, id 53) is in the table and within base's 99 languages; a real
  transcription with a fixed language returns that language (tested with English and German on the host).

**Defaults and upgrades** (settings migration v6): new installations and users who never chose a language get **fixed
English**; a saved language becomes the fixed language; a saved "Automatic" keeps automatic detection. Switching to
automatic detection keeps the fixed language, so switching back restores it. Changes apply from the next memo; a
running transcription is not interrupted.

**Measured** (`TranscriptionModelBenchmarkHostTest`, host, Whisper base multilingual f16, the 21 synthetic sentences
— 12 English, 9 in Italian, French, Spanish, German, Polish — clean and with pink noise, two runs, single-encode patch
in place):

| | Automatic detection | Fixed language |
|---|---|---|
| Mean decode per memo, all (clean / noise) | 2.15 / 2.05 s; 2.02 / 1.92 s | 1.89 / 1.95 s; 1.78 / 1.74 s |
| English memos, mean (clean / noise) | 1.69 / 1.65 s | 1.70 / 1.67 s |
| Other languages, mean (clean / noise) | 2.62 / 2.43 s | 2.02 / 2.08 s |
| WER (clean / noise) | 0.333 / 0.338 | 0.303 / 0.282 |
| Language correct | 16–17 of 21 | 21 of 21 (given) |

With the encoder pass already shared (patch above), identification itself costs about one decoder step: English memos
take the same time. The gain of a fixed language comes from avoiding misdetections (for example Italian taken as Thai),
which produce wrong and longer output. Without the patch (stock whisper.cpp), identification added a whole encoder pass
(3.40 s vs 1.67 s mean, section above).

**Whisper base.en (English-only), investigated, not offered.** Built from the same public export route (147 MB, same
architecture and size as base). On the same 12 English sentences with the language fixed to English (two runs): mean
1.55 vs 1.67 s clean but 1.61 vs 1.54 s with noise (no consistent speed difference), and less accurate on this synthetic
speech (WER 0.115 vs 0.066 clean, 0.148 vs 0.090 noisy). No memory or download advantage. Base Multilingual stays the
only offered model; a base.en file can still be imported (Settings shows "Base English" and English only).

**Other pipeline checks:** the WAV is read and resampled once (1–4 ms per memo, stage timings below); the model stays
loaded between memos (released 60 s after the queue is empty); the JNI bridge copies the samples once
(about 60,000 floats for a 4 s memo); greedy decoding is the default; no audio is processed twice.

### Stage timings, cold vs warm (host)

`TranscriptionTimingHostTest`, Whisper base q5_0, greedy ("Fast"), English synthetic speech, x86-64 build host with 4
cores (not a phone; phones are several times slower). Times per stage from `TranscriptionManager.lastTimings`:

| Run | Audio | Prepare audio | Load model | Decode | Save | Total | RTF |
|---|---|---|---|---|---|---|---|
| 4 threads, cold (first memo) | 4 s | 1 ms | 471 ms | 1565 ms | 1 ms | 2038 ms | 0.51 |
| 4 threads, warm | 4 s | 4 ms | 0 | 1468 ms | 2 ms | 1474 ms | 0.37 |
| 4 threads, warm | 12 s | 3 ms | 0 | 1624 ms | 2 ms | 1629 ms | 0.14 |
| 4 threads, warm | 35 s | 4 ms | 0 | 3262 ms | 2 ms | 3268 ms | 0.09 |
| 2 threads, warm | 4 s | 1 ms | 0 | 2610 ms | 1 ms | 2612 ms | 0.65 |
| 2 threads, warm | 12 s | 1 ms | 0 | 2754 ms | 2 ms | 2757 ms | 0.23 |
| 2 threads, warm | 35 s | 4 ms | 0 | 5762 ms | 2 ms | 5768 ms | 0.17 |

**Bottleneck:** decoding (`whisper_full`) is 96–99 % of the time; reading the WAV and saving take milliseconds. Short
memos are dominated by the fixed cost of encoding one 30 s window (a 4 s memo takes almost as long as a 12 s one).
The model load is paid once per 60 s idle period (cold run; 95 ms when the file is in the OS cache). Optimisations
applied: more threads while monitoring (above); the model stays loaded between queued memos. Not changed (would affect
quality): beam search stays opt-in; the audio is not shortened or resampled differently. The cold load of the f16 base
model on a phone (142 MB) is expected to take seconds; it is now shown as its own stage. Not measured on a device.

## 3. Distribution: model download from Settings

| Strategy | APK (release, unsigned, measured) | Offline | Assessment |
|---|---|---|---|
| Bundled Whisper base q5_0 (until this change) | 68.1 MB | Immediately | Large APK |
| **Model downloaded on request (current)** | see `README.md` / CHANGELOG for the measured size | After the one-time download | **Chosen** |

**Source.** `ggml-base.bin` from the whisper.cpp project (`https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin`):
OpenAI Whisper base, multilingual, MIT, in whisper.cpp's format (f16, 142 MiB). The file is verified against the SHA-1
`465707469ff3a37a2b9b8d8f89f2f99de7299dac` that whisper.cpp publishes in `models/README.md` of the pinned submodule;
`ModelDownloaderTest` checks that the app's constant matches that file. SHA-1 is what upstream publishes; the app also
records the file's SHA-256 after verification. This is the full-precision model, not a smaller substitute (the earlier
bundled file was a q5_0 quantization of the same weights: smaller and faster, but no upstream checksum exists for it).

**Flow** (`ModelDownloader`, `ModelDownloads`, `ModelDownloadService`): only after the user taps *Download Whisper Base
model* → foreground service (type dataSync) with progress and Cancel → storage check (size + 50 MB) → HTTPS only (no
cleartext) into `noBackupFilesDir/speech_models/downloaded/ggml-base.bin.part` → after an interruption, network loss
or cancellation the partial file is kept and *Retry* continues with an HTTP Range request (if the server ignores it, the
download starts over) → SHA-1 check (a mismatch deletes the part) → ggml header check (Whisper base, multilingual) →
test load with whisper.cpp → atomic rename to `ggml-base.bin` and metadata (`model.json`) → state *Ready*. A model that
fails any step never becomes active and never replaces an installed one. States shown in Settings: not installed,
downloading (MB and %), checking, preparing, installed, failed (with the reason and Retry when it can help).

**Missing model.** A transcription request without a model ends at once as *failed* with "No speech model is
installed…" and a *Download speech model* button on the memo; when the download completes, those memos are transcribed
automatically. **Removal** (*Remove Whisper base model*, confirmed): queued and running transcriptions are cancelled,
the engine is released, the file is deleted; transcripts, recordings and imported models stay.

**Other models.** *Import Model File…* still accepts any whisper.cpp ggml model chosen by the user (no network):
copied to a temporary file → header validated → SHA-256 recorded, duplicates refused → test-loaded → only then
available. The q5_0 file from `scripts/whisper-model/build-bundled-model.sh` (55 MB) can be imported this way.

**Policy notes (F-Droid, not decided here).** The `INTERNET` permission is now present, but used only for this download
after an explicit user action; there is no telemetry and no other network access. The file is hosted on Hugging Face, a
third-party service; F-Droid reviewers may consider a *NonFreeNet* anti-feature or ask for a different mirror. The model
is free (MIT). See `docs/FDROID.md`.

## 4. Licences and reproducibility

| Item | Licence | Bundling |
|---|---|---|
| whisper.cpp + ggml v1.9.5 (`third_party/whisper.cpp`, commit `d1be6fde…`) | MIT | Compiled from source by Gradle/CMake (NDK r28c) |
| OpenAI Whisper base weights | MIT (OpenAI states the code and weights are MIT) | Not in the app; downloaded on request as whisper.cpp's `ggml-base.bin` (SHA-1 `465707469ff3a37a2b9b8d8f89f2f99de7299dac`) |
| Conversion inputs | sherpa-onnx export (Apache-2.0 tooling) of the MIT weights; `openai-whisper` 20250625 assets (MIT) | Build-time only |

`scripts/whisper-model/build-bundled-model.sh` rebuilds a q5_0 version of the same weights from public sources (GitHub
release asset of the sherpa-onnx export, PyPI) and checks every intermediate SHA-256 (output
`1dfdc5f732fa9be0107f46803c6b25c02b625c80b72a89541f23568bec00bea5`); it is used by the host tests and can be imported.
Licence texts are shipped in the app (Settings → About → Open-source licenses) and listed in `THIRD_PARTY_NOTICES.md`.

## 5. Not verified (device work required)
- Transcription speed, memory, battery and heat on low-end, mid-range and recent phones (targets: faster than real
  time for short memos where practical; no UI stutter).
- The real download from Hugging Face (blocked in the build environment; only the same code path against a local
  server was tested), the f16 `ggml-base.bin` on phones (speed and memory: about 150 MB of weights instead of 55 MB),
  CPU-variant selection and 16 KB page devices on real hardware.
- Real Italian/English/German speech accuracy (only synthetic speech was available).
- Transcription while monitoring and recording run (CPU contention, wake-phrase latency).
