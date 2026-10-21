# Changelog

## Unreleased — Voice Memo 1.0 (versionCode 1)

First release under the new name **Voice Memo** and the new application ID `io.github.elgrande73.voicememo`
(previously **Ciao Linda**, `com.aistudio.ciaolinda`). Android installs it as a separate app; data of the old app is
not accessible to it (see README, "New application identity").

### Added (wake phrase, detection model, history)
- New default wake phrase **"Mango Tango"** for new installations, with a built-in reference made from synthetic
  speech. Existing installations keep "Ciao Linda"; both can be chosen under Settings → Wake Phrase. Recorded samples
  are unchanged and still take precedence. The previous built-in "Ciao Linda" reference never matched spoken
  "Ciao Linda" in the fixtures (score 0.00); the new one detects 16 of 60 held-out synthetic voices at 50 % with no
  false triggers on conversation (`docs/WAKE_PHRASE_DETECTION.md`, section 15).
- Settings → Wake-Word Detection Model: *Standard* (default, unchanged) or *Speech-checked*, which confirms each
  utterance with the bundled offline Whisper model (no download). It works without recorded samples and for typed
  custom phrases, falls back to Standard with a visible reason, and switches only between memos. Measured trade-off:
  more voices recognised without samples (27/60 vs 16/60) but more near misses (9/48 vs 0/48); see section 16.
- History: *Manual* filter next to *Wake Phrase* (combinable; Today/Favorites narrow further); playback mode
  *Autoplay* (default, as before) or *Single*, remembered; the list scrolls to keep the playing memo and its button
  visible in Autoplay.
- History: selection mode to delete or export several memos (select all shown or the entire library). Deletion asks
  for confirmation with the count, removes exactly the confirmed memos (audio, title, transcript) and reports partial
  failures; export writes one memo as its original WAV, several as a ZIP with the original WAVs (not recompressed)
  and a JSON/CSV manifest, to a file you choose or to another app.

### Fixed (transcription)
- Transcription could stay at "Transcribing…" or "Waiting…" forever: native errors that were not exceptions stopped
  the queue, the worker ran at a background priority that made it extremely slow, and the process could be frozen in
  the background. Transcription now runs in a foreground service with progress and Cancel, has a watchdog, shows
  explicit states (waiting, transcribing n %, failed with Retry), and resumes queued memos after a restart.

### Added (offline speech-to-text and audio level)
- On-device transcription of saved memos with whisper.cpp (built from source) and the bundled multilingual OpenAI
  Whisper "base" model (55 MB, MIT): transcribe, cancel, retry, edit and delete transcripts in History; optional
  automatic transcription of new memos (off by default); language and quality settings; optional import of other
  whisper.cpp model files (validated and test-loaded before use). No download, no network permission.
  See `docs/TRANSCRIPTION.md`.
- Optional *Minimum audio level* (dBFS) for the wake phrase, measured on the spoken phrase and applied identically to
  starting and stopping. New installations: on, −50 dBFS; existing installations keep their previous level.

### Fixed (monitoring and wake phrase)
- The dashboard's audio level only moved while a memo was being recorded; it now shows the real microphone level
  during monitoring (about 10 updates per second) and no stale value when there is no input.
- Saying the wake phrase to stop a memo shortly after speaking failed more often than starting (synthetic benchmark,
  0.2 s pause: 17/36 vs 25/36). Cause: noise-suppression gains opened by the memo speech closed too slowly. Now 22/36
  (0.2 s pause) and equal to starting (25/36) after 0.5 s and 1 s pauses. The loudness check for stopping no longer
  counts the preceding memo speech.

### Changed
- Levels are shown in dBFS (digital level) instead of an uncalibrated "dB" scale.
- The device's system speech recogniser is no longer used (it listened to the microphone instead of transcribing the
  saved memo and could be proprietary or online).
- The app now contains native code for arm64-v8a, armeabi-v7a and x86_64 only; the release APK is about 68 MB.

### Added (recording controls)
- Large round Quick Memo / Stop Memo button at the bottom centre of the dashboard (96 dp, distinct colour, symbol and
  label for start and stop; TalkBack labels).
- Lock-screen controls while monitoring runs: Start/Stop memo button in the monitoring notification and a "Voice memo"
  Quick Settings tile, usable without unlocking (can be turned off; Android then asks to unlock). The monitoring
  notification moved to a new channel "Monitoring and recording controls" (no sound), because silent notifications are
  hidden on many lock screens.
- Optional volume-button shortcut (hold both volume buttons, or hold Volume Down/Up) and external keyboard shortcut,
  both off by default. They work while Voice Memo is open, and in other apps or on the lock screen (screen on) with the
  optional accessibility service. No shortcut works while the screen is off (Android limitation). See
  `docs/SHORTCUTS_AND_LOCK_SCREEN.md`.

### Changed
- Match confidence threshold range widened to 20 %–80 % (default still 50 %; saved values are kept). On the synthetic
  benchmark, values below 50 % let ordinary conversation start memos (conversation FPR 0.024 at 45 %, 0.476 at 30 %);
  Settings warns about this.

### Fixed
- Starting a memo while one was already being recorded restarted it and discarded the audio so far; repeated start
  requests are now ignored.

### Added (stop handling and multi-sample enrollment)
- Memos ended by repeating the wake phrase no longer contain the spoken stop phrase (cut at the pause before it, with a
  short fade-out). Memos ended by the silence timeout, the maximum length, the Stop button, stopping monitoring or a
  microphone failure keep every captured frame, including the final words. Each memo is finalized exactly once, and WAV
  files are written atomically.
- Optional enrollment of 1–5 wake-phrase samples (Settings shows "n of 5"; add, replace, delete). Samples are
  validated (silence, clipping, length, duplicates) and stored as features only, never as audio. A phrase recorded with
  an earlier version is migrated to sample 1.
- Reproducible accuracy benchmark on synthetic speech (confusion matrix, threshold sweep, false activations per hour);
  results in `docs/WAKE_PHRASE_DETECTION.md`. On these fixtures 5 samples raised recall at 50 % from 0.667 to 0.778
  (held-out: 0.639 to 0.806), with slightly more false activations on similar-sounding phrases.

### Fixed (wake-phrase detection)
- Automatic monitoring reported far lower match confidence than the Settings phrase test (0 % vs. > 50 % on identical
  audio): monitoring passed only loud frames to the matcher and enrolled templates contained the whole recording,
  mostly silence. Monitoring and the test now share one front end, matcher and decision logic, every frame is matched,
  and enrollment keeps only the spoken phrase. Details and measurements: `docs/WAKE_PHRASE_DETECTION.md`.
- In background noise above the activation threshold, any speech could trigger a wake-phrase memo; it no longer does in
  the test scenarios.

### Added
- Dashboard title "Voice memo".
- Adaptive noise-floor estimation and conservative spectral noise suppression for wake-phrase detection.
- Monitoring pauses while a wake phrase or test phrase is recorded in Settings and resumes afterwards; the dashboard
  shows the real monitoring state (active, starting, waiting for the microphone, unavailable, stopping, stopped).
- Debug-build diagnostics (`WakePhraseDiag` logcat tag, no audio content).
- Automatic playback of the next memo in the currently displayed list (filters and search respected), with short
  beginning and ending chimes; stops at the end of the list.
- Recording start/stop chimes (Settings → Recording Sounds, with a Test button).
- Live wake-phrase match gauge on the dashboard.
- Saved wake-phrase test recording and result, with "Re-test Saved Test Recording".
- New launcher icon (adaptive, themed/monochrome and legacy).
- F-Droid/fastlane metadata.

### Changed
- One "Match confidence threshold" setting (50 %–80 %, default 50 %) replaces "Keyword Sensitivity"; the dashboard
  shows the same value as "Trigger threshold". Changes apply to the running detector immediately.
- The dashboard's loudness label is now "Activation threshold", matching Settings.
- Memos triggered by the wake phrase are titled "Wake Phrase Memo"; the history filter is "Wake Phrase".
- Playback requests audio focus and pauses when another app takes it.

### Fixed
- Deleting the recorded wake phrase now also removes it from a running monitoring service.
- "Test Phrase" uses the recorded wake phrase after an app restart.
- Recording chimes play on the media stream so they are audible when system sounds are muted.

### Removed
- Unused network libraries (Retrofit, OkHttp, Moshi), the Secrets Gradle plugin and the AI Studio `metadata.json`.
- The destructive database migration fallback (schema changes now require explicit migrations).
