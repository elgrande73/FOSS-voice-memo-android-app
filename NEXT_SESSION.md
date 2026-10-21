# Next session

**Branch:** `claude/gifted-heisenberg-ek73ee`. Read `CLAUDE.md`, `CONTINUOUS_IMPROVEMENT.md`, then the top of
`IMPROVEMENT_BACKLOG.md`.

## State at the end of the last session

- The earlier default phrase is gone completely (commit `7a173a8`).
- The wake phrase has a speaker-split corpus, and mean-normalised matching is adopted (see `IMPROVEMENT_LOG.md`).
- Next up is backlog item 1: stage-aware transcription progress, followed by per-stage timing.

## Environment notes

- `export ANDROID_HOME=/opt/android-sdk`. The full check takes about 6–10 min:
  `./gradlew assembleDebug assembleRelease lintDebug testDebugUnitTest compileDebugAndroidTestKotlin --no-daemon`.
- Corpus tests take a few minutes: `./gradlew testDebugUnitTest --tests '*WakePhraseCorpusTest*'`.
  - `SELECT_BUILT_IN=1` runs the reference selection experiment.
- Host transcription tests need two things:
  - `build/whisper-model/ggml-base-q5_0.bin` (`scripts/whisper-model/build-bundled-model.sh`; Hugging Face may be blocked).
  - `build/host-whisper-jni/lib/libvmwhisper.so` (`scripts/host-whisper-jni/build.sh`).
- No emulator or device here, so instrumented tests and device behaviour remain unverified.
