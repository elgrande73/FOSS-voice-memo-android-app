# On-device transcription and model choices

Voice Memo transcribes saved memos on the phone with **whisper.cpp** (built from source) and a bundled
**OpenAI Whisper "base"** model. Nothing is downloaded at run time, the app still has no `INTERNET` permission, and
no audio or text leaves the device. Wake-phrase detection stays a separate, lightweight detector
(see `WAKE_PHRASE_DETECTION.md`); the transcription model is loaded only when a memo is transcribed.

All numbers below are **measured on the build machine** (x86-64 Intel Xeon, 4 cores, AVX-512) with **synthetic
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
TranscriptionManager (queue, one background thread at THREAD_PRIORITY_BACKGROUND)
   ─► WavReader (16 kHz mono floats) ─► silence check ─► WhisperTranscriber ─► JNI (libvmwhisper)
   ─► whisper.cpp / ggml (CPU backend chosen at run time) ─► text ─► Room (status COMPLETED / FAILED)
```

- The model is loaded on the first transcription and released 60 s after the queue is empty.
- 4 threads (2 while monitoring runs, so wake-phrase detection keeps CPU time); ggml's worker threads inherit the
  background priority.
- The bundled model is read directly from the APK (stored uncompressed), so it is not stored twice.
- arm64: ggml's CPU backend is built in seven variants (ARMv8.0 … ARMv9.2 with dot-product, fp16, int8-matmul, SVE/SME)
  and the best one the CPU supports is loaded at run time, so old Cortex-A53 phones work and newer phones use faster
  instructions. armeabi-v7a and x86_64 get one generic variant.
- Cancellation uses whisper.cpp's abort callback; it takes effect at the next processing step
  (measured: 2.3–2.5 s on the host for a 40 s recording).

### Data handling
- Transcripts are stored in the memo's existing database row (`transcription_text`, `transcription_status`); no schema
  change. Status values: `SKIPPED` (not transcribed), `PENDING` (automatic transcription was on when saved),
  `COMPLETED`, `FAILED`. Queue and running state are kept in memory only, so a memo can never be stuck as
  "transcribing" after the app is closed.
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
- Keywords "CIAO LINDA" and "CHOW LINDA", thresholds 0.25 / 0.10 / 0.03: **0 detections of 72 target clips** (and 0 false
  positives). Control: the model detected its own test keywords on its test recordings and "HELLO WORLD" spoken by
  eSpeak's English voice, so the harness works; the English model does not recognise the Italian phrase.
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
build environment's network policy). The official ggml files are on Hugging Face; the bundled file was therefore
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
| **base q5_0 (bundled)** | **55 MB** | **0.371** | **0.603** | 0.103 | 0.059 | 0.55 |
| base q5_1 | 60 MB | 0.395 | 0.632 | 0.128 | 0.059 | 0.50 |
| base q5_1, greedy | 60 MB | 0.476 | 0.721 | 0.179 | 0.176 | 0.37 |
| base q5_1, automatic language | 60 MB | 0.476 | 0.779 | 0.128 | 0.059 | 0.82 |

- eSpeak's Italian voice is recognised poorly by every model; real Italian speech is expected to do much better, but
  this was **not measured** (no real recordings were available). English synthetic speech is close to correct.
- Reducing whisper's 30 s audio context for short memos saved little time and increased errors (base: 0.395 → 0.484 with
  +3 s context) and is not used.
- Choosing the language beats automatic detection, hence the language setting (default: automatic).
- Quality setting: *Accurate* = beam search 5 (default), *Fast* = greedy (~30 % faster, more errors).

### Memory (host, base q5_0)
whisper.cpp reports 54.7 MB model, 28 MB KV caches and ~140 MB compute buffers; measured peak resident memory of the
process: **193 MB with beam search, 148 MB greedy** (3.6 s memo). On a phone the model is mapped from the APK; similar
totals are expected but **not measured**. If loading fails (e.g. not enough memory) the memo is marked failed with a
message and the audio is kept.

### JNI integration test (host)
`WhisperNativeHostTest` runs the real JNI bridge with the bundled model on the JVM (after
`scripts/host-whisper-jni/build.sh`; generic x86-64 CPU backend, so slower than the numbers above):
load 92–114 ms; 3 s clips in 1.4–1.7 s with 4 threads and beam search; WER 0.571 on two English + two Italian
synthetic sentences (English 2 of 15 words wrong); language detection returns "en" for English; abort ends a
40 s transcription within 2.5 s; an invalid model file is rejected. Loading the bundled model **from the APK asset**
uses a separate code path (AAsset reader) that only runs on Android and was **not tested** (no device/emulator).

## 3. Distribution strategy

| Strategy | Offline after install | Size | F-Droid / policy | Assessment |
|---|---|---|---|---|
| A. Fully bundled | Yes | APK 67.7 MB (release, unsigned) | No network needed; model reproducible from public sources | **Chosen for the default model** |
| B. Bundled default + optional downloads | Yes for the default | + downloaded models | Downloading requires the `INTERNET` permission (forbidden by this project) and F-Droid's *NonFreeNet* anti-feature when the host is a non-free service (e.g. Hugging Face); see other F-Droid speech apps | Downloads **not implemented**; instead users may **import** a model file (no network) |
| C. Download-selected models only | No (until downloaded) | Small APK | As B, and core function not usable after install | Rejected |

Optional models: *Settings → Speech-to-Text → Import Model File…* accepts any whisper.cpp ggml model. Import:
copied to a temporary file in app-private, non-backed-up storage → header validated (magic and Whisper
hyper-parameters) → SHA-256 recorded and duplicates refused → test-loaded with whisper.cpp → only then made available.
A failed import deletes the temporary file and never changes the selected model; removing an imported model falls back
to the bundled one, which can never be removed. No curated checksum catalogue is built in, because the official
model hashes could not be verified from this environment (Hugging Face blocked); this is an open item.

## 4. Licences and reproducibility

| Item | Licence | Bundling |
|---|---|---|
| whisper.cpp + ggml v1.9.5 (`third_party/whisper.cpp`, commit `d1be6fde…`) | MIT | Compiled from source by Gradle/CMake (NDK r28c) |
| OpenAI Whisper base weights | MIT (OpenAI states the code and weights are MIT) | `app/src/main/assets/models/ggml-base-q5_0.bin`, SHA-256 `1dfdc5f732fa9be0107f46803c6b25c02b625c80b72a89541f23568bec00bea5` |
| Conversion inputs | sherpa-onnx export (Apache-2.0 tooling) of the MIT weights; `openai-whisper` 20250625 assets (MIT) | Build-time only |

`scripts/whisper-model/build-bundled-model.sh` rebuilds the bundled model from the GitHub release asset and checks
every intermediate SHA-256; it was run in a clean environment and reproduced the file byte for byte.
Licence texts are shipped in the app (Settings → Open-source licenses) and listed in `THIRD_PARTY_NOTICES.md`.

## 5. Not verified (device work required)
- Transcription speed, memory, battery and heat on low-end, mid-range and recent phones (targets: faster than real
  time for short memos where practical; no UI stutter).
- Asset-based model loading, CPU-variant selection and 16 KB page devices on real hardware.
- Real Italian/English/German speech accuracy (only synthetic speech was available).
- Transcription while monitoring and recording run (CPU contention, wake-phrase latency).
