# Next session

**Branch:** `claude/gifted-heisenberg-ek73ee`. Read `CLAUDE.md`, `CONTINUOUS_IMPROVEMENT.md`, then the top of
`IMPROVEMENT_BACKLOG.md`.

## State at the end of the last session

- Release 1.0.0 prepared (versionCode 10000); remaining maintainer steps are in `docs/FDROID.md` (screenshots,
  device test, tag `v1.0.0`, signing route, fdroiddata merge request).

- The requested fixes are done (see `IMPROVEMENT_LOG.md`, top entry):
  - shortcuts removed;
  - recording-controls setting fixed;
  - tile, widget and launcher shortcut added;
  - late stop after an automatic end;
  - 20 dB matching for recorded samples;
  - transcription -O3 and the single-encode patch.
- Nothing was verified on a device. Start with backlog item 1.

## Environment notes

- `export ANDROID_HOME=/opt/android-sdk`. The full check takes about 6–10 min:
  `./gradlew assembleDebug assembleRelease lintDebug testDebugUnitTest compileDebugAndroidTestKotlin --no-daemon`.
- Corpus tests take a few minutes: `./gradlew testDebugUnitTest --tests '*WakePhraseCorpusTest*'`.
  - `SELECT_BUILT_IN=1` runs the reference selection experiment.
- `TranscriptionModelBenchmarkHostTest`: set `WHISPER_BENCH_DIR` to a folder of ggml files (built from the sherpa-onnx
  exports with `scripts/whisper-model/onnx_whisper_to_ggml.py` and whisper-quantize; see docs/TRANSCRIPTION.md).
- Host transcription tests need two things:
  - `build/whisper-model/ggml-base-q5_0.bin` (`scripts/whisper-model/build-bundled-model.sh`; Hugging Face may be blocked).
  - `build/host-whisper-jni/lib/libvmwhisper.so` (`scripts/host-whisper-jni/build.sh`).
- No emulator or device here, so instrumented tests and device behaviour remain unverified.
