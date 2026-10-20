# Changelog

## Unreleased — Voice Memo 1.0 (versionCode 1)

First release under the new name **Voice Memo** and the new application ID `io.github.elgrande73.voicememo`
(previously **Ciao Linda**, `com.aistudio.ciaolinda`). Android installs it as a separate app; data of the old app is
not accessible to it (see README, "New application identity").

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
