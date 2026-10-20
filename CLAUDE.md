# CLAUDE.md — Voice Memo (Android, FOSS / F-Droid)

Kotlin + Jetpack Compose (Material 3), Room, coroutines/Flow, platform audio APIs. minSdk 24, target/compile 35.
Architecture, persistence rules and pipelines: `ARCHITECTURE.md`, `docs/WAKE_PHRASE_DETECTION.md`, `docs/SHORTCUTS_AND_LOCK_SCREEN.md`, `docs/FDROID.md`.

## Identity & branding (mandatory)
- App name / launcher label: **Voice Memo** (`R.string.app_name`). Dashboard title: **Voice memo** (`R.string.dashboard_title`). These are separate strings; change neither as a side effect.
- Application ID / namespace / package: `io.github.elgrande73.voicememo` (provisional until the maintainer confirms; never change it after a release). The old `com.aistudio.ciaolinda` identity must not return.
- **"Ciao Linda" is the default wake phrase, not branding.** Keep it in recognition logic, defaults, tests and docs; remove it elsewhere. Persisted values `KEYWORD_CIAO_LINDA` (trigger type) and the `'CIAO'` template magic must never be renamed.
- Never claim Voice Memo inherits Ciao Linda's private data; there is no importer.

## Settings & detection (mandatory)
- Exactly one wake-phrase setting: **Match confidence threshold**, percent, **20–80 % inclusive**, default 50 % (below 50 % conversation starts triggering; measured, see docs), stored as `matchConfidenceThresholdPercent`. Dashboard label: **Trigger threshold**, showing the exact configured value. No competing "sensitivity" settings or terms.
- Detector semantics: `score = clamp(1 − DTW distance / 0.40, 0, 1)` (similarity score, not a probability); match when `score ≥ percent / 100`. Do not rescale scores to make numbers look better.
- Monitoring and the Settings phrase test must share one pipeline: `WakePhraseFrontEnd` → `TemplateKeywordDetector` → `MemoTriggerDetector` (test via `WakePhraseEvaluator`). Feed every 50 ms frame in order (`PcmFrameAssembler`); never gate frames before the matcher.
- Noise handling stays conservative and validated by fixture tests (`WakePhraseNoiseRobustnessTest`); justify parameter changes with measurements on held-out conditions.
- Wake-phrase samples are optional, max 5, matched with `ReferenceAggregation.MAX`; store features only, never raw enrollment audio, and keep them on the device.
- Memo endings: only a `KEYWORD` stop removes audio (the stop phrase); timeouts, user/service stops and errors keep every captured frame. Finalize exactly once in `MemoTriggerDetector.finalizeRecording`.
- Accuracy claims need measurements from `WakePhraseEvaluationTest` (synthetic fixtures; say so).
- Speech-to-text: whisper.cpp from the pinned submodule `third_party/whisper.cpp`, built with NDK r28c; bundled model `assets/models/ggml-base-q5_0.bin` (MIT, reproducible with `scripts/whisper-model/build-bundled-model.sh`, SHA-256 pinned in `SpeechModelStore`). Transcribe only on request or with automatic transcription enabled, on the background worker; never modify the WAV; keep previous text on failure. No model downloads (no `INTERNET`); extra models only by user file import with validation. Keep the wake-phrase detector lightweight and separate from transcription.
- Levels are digital (dBFS); never label them dB SPL. The optional wake-phrase minimum level applies to the matched phrase, identically for start and stop, and never removes memo audio.
- Recording controls (dashboard button, notification, tile, shortcuts) go through `AudioMonitoringService` start/stop/toggle actions; background controls only act while monitoring runs. Shortcuts are off by default; the accessibility service only filters key events (no window content). Never show app content over the lock screen. See `docs/SHORTCUTS_AND_LOCK_SCREEN.md`.
- Microphone: only one owner at a time via `MicrophoneArbiter`. Settings phrase recordings go through `PhraseCaptureCoordinator` (stop monitoring → confirm release → record → restore unless the user changed monitoring). Dashboard status must reflect `AudioMonitoringService.engineState`, not just whether the service exists.

## Playback (mandatory)
- After natural completion, play the next memo of the currently displayed (filtered/searched) list: END chime → next BEGIN chime → memo. Stop at the end of the list (no wrap). Manual stop/pause/cancel/focus loss: no END chime, no advance. Logic lives in the unit-tested `MemoPlaybackSequencer`.
- Chimes are synthesized at runtime (`FeedbackSoundPlayer`); no bundled or downloaded audio.

## Data integrity (mandatory)
- Never delete or overwrite user data: memos, WAV files, settings, enrolled wake phrase, test recording/result.
- Persisted identifiers are frozen: `voice_memo_preferences`, `voice_memo_database`, `filesDir/memos`, `filesDir/wake_phrase/*` (samples: `wake_phrase/samples/sample_<id>.bin`, magic `VMWS`; legacy `enrolled_template.bin` migrated once).
- Room: schema exported to `app/schemas`; no destructive migration fallback. Every schema change needs a version bump, an explicit migration in `AppDatabase.MIGRATIONS` and a test.
- Settings changes go through `SettingsMigrator` (versioned, idempotent, never replace values the user saved).

## FOSS / F-Droid & privacy (mandatory)
- License: Apache-2.0; do not change it. No proprietary SDKs, Google Play Services, analytics, ads, tracking, or runtime downloads of code/models/assets. No `INTERNET` permission; request only permissions the features need.
- Prefer platform APIs; add a dependency only if necessary and after reviewing its license.
- New assets need documented original provenance (see `artwork/icon/README.md`, `app/src/test/resources/audio/README.md`).
- Never log or persist audio for diagnostics; debug-only summaries (`WakePhraseDiag`) contain levels and scores only.
- Do not invent release tags, commits, signing keys or URLs in `fdroid/metadata/`; leave `MAINTAINER` notes instead. Never claim F-Droid acceptance.

## Build & verify
```bash
./gradlew assembleDebug assembleRelease   # release is unsigned without keystore.properties
./gradlew testDebugUnitTest               # unit + Robolectric tests
./gradlew lintDebug
```
- Needs JDK 17+ and Android SDK 35 (`local.properties` → `sdk.dir`). Maven Central may answer HTTP 429: retry with backoff.
- Gradle build cache is on: use `--no-build-cache --rerun-tasks` when re-running tests with changed environment variables.
- Instrumented tests need an emulator/device; if none is available, say so.
- Clone with submodules (`git submodule update --init`). `WhisperNativeHostTest` runs the real engine on the JVM after `scripts/host-whisper-jni/build.sh` (skipped otherwise).
- Report results truthfully: never claim a build, test, migration or device behaviour that was not actually verified.

## Working conventions
- Inspect the existing code first; keep changes minimal and match surrounding style; avoid unrelated refactoring.
- Add or update tests for behaviour changes (playback, threshold, persistence, detection, mic coordination).
- Update `CHANGELOG.md` and the relevant docs when user-visible behaviour changes.
