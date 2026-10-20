# Changelog

## Unreleased — Voice Memo 1.0 (versionCode 1)

First release under the new name **Voice Memo** and the new application ID `io.github.elgrande73.voicememo`
(previously **Ciao Linda**, `com.aistudio.ciaolinda`). Android installs it as a separate app; data of the old app is
not accessible to it (see README, "New application identity").

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
