# Changelog

## Unreleased — Voice Memo 1.0 (versionCode 1)

First release under the new name **Voice Memo** and the new application ID `io.github.elgrande73.voicememo`
(the app was previously published under another name and ID). Android installs it as a separate app; data of the old app is not accessible
to it (see README, "New application identity").

### Changed (since pre-release v0.0.2)
- Transcription language: **fixed English by default** (no per-recording language detection), with *Fixed language
  (faster, recommended)* or *Detect language automatically* in Settings, a selector with every language the model
  supports (99 for Whisper base, e.g. English, German, Spanish, French, Finnish, Estonian, Armenian, Russian) and an
  *Effective configuration* summary. A saved language choice is kept (settings migration v6). Unsupported language
  codes are refused with a message instead of being passed to the engine. Build host, same audio: fixed language
  1.7–1.9 s vs 1.9–2.2 s per short memo, WER 0.28–0.30 vs 0.33–0.34.
- Quick Memo no longer leaves monitoring on: if monitoring was off, it runs only for the quick memo and stops once the
  memo is saved (also when started from the tile, widget or launcher shortcut). If monitoring was already on, it stays
  on. During such a memo the dashboard offers *Keep Monitoring On*. Settings changed during a memo no longer restart
  capture (which could discard the memo); they apply when the memo ends.
- **Much faster transcription.** Debug builds compiled the speech engine without optimisation (over a minute for a
  4 s memo on a phone); the native code is now always built with `-O3` (build host: 34.0 s → 1.5 s for the same 4 s
  memo; release builds −O2 → −O3: 2.2 s → 1.5 s). With automatic language detection the first audio window is no
  longer encoded twice (identical transcripts; build host: 3.4 s → 1.9 s per short memo). Phone timings still need a
  device.
- **Removed:** the volume-button shortcut and the accessibility service for shortcuts outside the app ("Use shortcuts
  outside the app"), which did not work reliably. Their settings are removed (settings migration v5); the keyboard
  shortcut is unchanged. The app no longer declares an accessibility service.
- **Fixed:** *Recording controls on the lock screen* had no effect (the notification channel overrode the setting, and
  *Start memo* stayed available). The setting is now *Recording controls in the notification and on the lock screen*:
  off removes the Start/Stop button from the notification entirely; changes apply at once.
- **New:** one-tap recording from a home-screen widget and a "Record memo" launcher shortcut; the Quick Settings tile
  now also works when monitoring is off (it opens the app and records). All show the real recording state.
- **Wake phrase:** saying the stop phrase after the memo had already ended by the silence timeout started a new memo;
  within 5 s it is now recognised as the stop command (stop chime, no new memo). Starting and stopping use identical
  detection (measured parity). Recorded samples are matched on the strongest 20 dB of the spectrum, which makes them
  more robust to background noise (synthetic held-out set, 1 sample: recall 0.39 → 0.64 with no false triggers; near
  misses 180 → 36 per hour of stream); the built-in reference is unchanged.
- If Android restarts the monitoring service after the app was killed, it now stops instead of showing a monitoring
  notification without listening; start monitoring again from the app.
- Transcription shows what it is doing: preparing audio, loading the speech model (only when needed), transcribing,
  saving, with elapsed time and memo length; then how long it took, or that it was cancelled or failed. A percentage
  appears only for memos over 30 s, where whisper.cpp's progress is meaningful.
- Transcription uses up to 4 threads while monitoring runs (previously 2), leaving two cores free; about 1.8× faster
  decoding on the build host.
- Wake phrase: matching compares spectral shape relative to each sequence's own average (removes a voice's or
  microphone's constant colouring). On the synthetic test corpus false triggers on similar phrases fall by half or more
  and positives and negatives separate better (AUC 0.70 → 0.84), but at the unchanged 50 % default some voices are
  detected less often; lower the threshold to 40–45 % if the phrase is missed. Stored samples and settings are
  unchanged. New speaker-split evaluation corpus with utterance-level metrics, latency and false activations per hour.
- **Breaking:** wake-phrase samples recorded with the very first app version (an older feature format) are no longer
  used; Settings shows how many and asks to record the phrase again (the files stay on disk). Memos started by the wake
  phrase get a new stored trigger type (database migration 1 → 2, only that column changes); the Wake Phrase filter
  works for old and new memos.
- **Smaller app, model downloaded on request:** the speech model is no longer inside the app. *Settings → On-device
  transcription → Download Whisper Base model* downloads OpenAI Whisper base (MIT, 142 MiB, whisper.cpp's official
  file) once, with progress, Cancel, resume after interruptions, checksum and test load before use, and a Remove
  action. Release APK: 12.8 MB instead of 68.1 MB (measured, unsigned, all ABIs). The `INTERNET` permission is used
  only for this download; everything else works offline. Without a model, transcription fails at once with a
  *Download speech model* action instead of waiting; memos that failed for that reason are transcribed after the
  download. Imported models keep working. Existing transcripts and recordings are unchanged.
- **One wake phrase, one detector:** "Mango Tango" is the only built-in wake phrase (the earlier built-in phrase and
  its selector were removed; its reference never matched real speech in the fixtures). Recorded samples of any phrase
  keep working and take precedence. The experimental speech-checked detection model was removed; template matching is
  the only detector again. Settings migration v4 drops the removed settings and shows upgraded installations a
  one-time notice. Wake-phrase measurements were redone on a new "Mango Tango" fixture set
  (`docs/WAKE_PHRASE_DETECTION.md`).

### Added (wake phrase, history)
- Default wake phrase **"Mango Tango"** with a built-in reference made from synthetic speech of five voices: detects
  16 of 60 held-out synthetic voices at 50 % with no false triggers on conversation; recording the phrase in one's own
  voice is recommended (22–25 of 36) (`docs/WAKE_PHRASE_DETECTION.md`, section 15).
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
- On-device transcription of saved memos with whisper.cpp (built from source) and the multilingual OpenAI Whisper
  "base" model (MIT; downloaded from Settings, see above): transcribe, cancel, retry, edit and delete transcripts in
  History; optional automatic transcription of new memos (off by default); language and quality settings; optional
  import of other whisper.cpp model files (validated and test-loaded before use). See `docs/TRANSCRIPTION.md`.
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
- The app contains native code for arm64-v8a, armeabi-v7a and x86_64 only; the release APK is 12.8 MB (measured; without the speech model).

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
