# Voice Memo

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![API](https://img.shields.io/badge/API-24%2B-brightgreen.svg)](https://developer.android.com/about/versions/nougat)

A privacy-first, open-source Android app for automatic, hands-free voice memos. Detection (voice activity, loudness, wake phrase) runs entirely on the device: no accounts, no cloud services, no analytics, no ads.

The default wake phrase is **"Mango Tango"**; you can also record your own phrase.

---

## ✨ Features

- **Automatic recording**: starts when speech or sound exceeds the activation threshold and stops after a configurable silence timeout.
- **Wake phrase**: say **"Mango Tango"** to start (and optionally stop) a memo. An offline keyword spotter (spectral filterbanks + Dynamic Time Warping) is built in; for reliable detection record one to five samples of the phrase — or any other phrase — in your own voice (natural variations help); they are matched on the device and never leave it. When the phrase is repeated to stop a memo, the spoken stop phrase is removed from the recording; a memo ended by the silence timeout keeps its complete ending. Monitoring and the Settings phrase test use the same detection pipeline, and monitoring pauses automatically while a phrase is recorded in Settings.
- **Adaptive noise handling**: the background noise floor is estimated automatically and conservative spectral noise suppression keeps background sounds from being mistaken for the wake phrase (see [`docs/WAKE_PHRASE_DETECTION.md`](docs/WAKE_PHRASE_DETECTION.md)).
- **Match confidence threshold**: one setting (20 %–80 %, default 50 %) controls how closely speech must match the wake phrase; the dashboard shows the same value as the *Trigger threshold* next to a live match gauge.
- **Rolling pre-trigger buffer**: keeps the start of your sentence.
- **Playback**: *Autoplay* (default) continues with the next memo of the list you are viewing (filters, search and order respected; stops at the end), *Single* stops after each memo; chimes frame every memo, and the list scrolls to keep the playing memo visible in Autoplay.
- **Recording controls**: a large Quick Memo / Stop Memo button at the bottom of the dashboard; Start/Stop from the lock screen through the monitoring notification and a Quick Settings tile; a home-screen widget and a "Record memo" launcher shortcut; an optional Bluetooth/USB keyboard shortcut (see [`docs/SHORTCUTS_AND_LOCK_SCREEN.md`](docs/SHORTCUTS_AND_LOCK_SCREEN.md) for what Android allows).
- **On-device speech-to-text**: transcribe memos with the multilingual Whisper base model, which you download once from Settings (about 149 MB; the app itself is about 13 MB); after that it works offline, with no account. Edit, retry or delete transcripts; optional automatic transcription.
- **Live audio level**: the dashboard shows the real microphone level (dBFS, about 10 updates per second) while monitoring, and an optional *Minimum audio level* can be required for the wake phrase (start and stop alike).
- **Recording chimes**: optional sounds when a recording starts and stops.
- **Audio routing**: built-in microphone, USB audio and Bluetooth headsets.
- **History**: search; filters Today, Favorites, Wake Phrase and Manual (combinable); select several memos (all shown or the entire library) to delete them or export them — one memo as its original WAV, several as a ZIP with the original WAVs and a JSON/CSV manifest — to a file you choose or to another app.
- **Local storage**: 16-bit PCM WAV files indexed in a local Room database; favorites, playback speed.
- **Material 3 UI** with light/dark theming.

---

## 🆔 New application identity

This app was previously published under a different name and application ID. It is now **Voice Memo** with the application ID **`io.github.elgrande73.voicememo`**.

> ⚠️ The ID `io.github.elgrande73.voicememo` is derived from the GitHub account hosting the source code (`github.com/elgrande73`). It is **provisional until the maintainer confirms it** before the first release; changing it after a release would again create a separate app.

Changing the application ID is a deliberate breaking change. Android treats Voice Memo as a **different app**, not as an update of the previous app:

| Scenario | What happens |
|---|---|
| **Fresh installation of Voice Memo** | Starts with no memos, the built-in "Mango Tango" wake phrase and a 50 % match confidence threshold. |
| **Update from one Voice Memo version to a later one** | Memos, audio files, settings, the threshold, your recorded wake-phrase samples (a single phrase recorded with an earlier version becomes sample 1), the test recording and its result are kept (see [Persistent user data](ARCHITECTURE.md#5-persistent-user-data--stable-identifiers)). |
| **Voice Memo installed alongside the previous app** | Both apps are installed independently. Voice Memo **cannot read** the previous app's private database, settings, recordings or wake-phrase template (Android app sandbox), and does not claim to have imported them. |
| **Moving data from the previous app** | There is **no automatic import**. Recordings can be moved manually: in the previous app use *History → Export All (ZIP)* and keep the archive; the WAV files remain playable with any audio player. Voice Memo has no importer, so settings and the wake-phrase template must be configured again (record the phrase once more under *Settings → Wake Phrase*). |

---

## 🔒 Privacy & Permissions

- **Network only for the model download you start**: the `INTERNET` permission is used for one thing — downloading the speech model when you tap *Download Whisper Base model* in Settings (HTTPS, from the whisper.cpp project on Hugging Face, verified by checksum). No analytics, trackers, ads or other network access; everything else works offline.
- **Speech-to-text on the device**: memos are transcribed with whisper.cpp and OpenAI's Whisper "base" model (MIT), on the phone, from the saved recording; nothing is sent anywhere. Transcription runs only when you tap *Transcribe* (or for new memos if you turn on automatic transcription). Further models can be imported from a file. Details and measurements: [`docs/TRANSCRIPTION.md`](docs/TRANSCRIPTION.md).
- **Lock screen**: while monitoring runs, anyone holding the locked phone can start or stop a memo from the notification or the Quick Settings tile (turn this off under *Settings → Shortcuts & Lock Screen*; the notification then has no button at all). Memos can only be listened to after unlocking.
- **Backups**: Android's standard app backup is enabled (`allowBackup`), so memos and settings can be included in device backups configured by the user.

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | Microphone capture for monitoring, recording and wake-phrase training/testing |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` | Continuous background monitoring with a visible notification |
| `POST_NOTIFICATIONS` | Monitoring status and "memo saved" notifications |
| `WAKE_LOCK` | Keeps monitoring running with the screen off |
| `MODIFY_AUDIO_SETTINGS` | Audio routing for external/Bluetooth microphones |
| `BLUETOOTH` (≤ Android 11), `BLUETOOTH_CONNECT` | Detecting and using Bluetooth headset microphones |
| `INTERNET` | Only the speech model download you start in Settings |
| `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_MEDIA_PROCESSING` | Keeping the model download and transcription running, with a notification, while the app is in the background |

---

## 🛠️ Tech Stack

- **Kotlin** & **Jetpack Compose** (Material 3)
- **Room** (local database), **Kotlin Coroutines & Flow**
- **Android AudioRecord / AudioTrack / MediaPlayer** (platform APIs)
- **whisper.cpp** (MIT, git submodule `third_party/whisper.cpp`, compiled with the NDK) for on-device speech-to-text

### Building
```bash
git clone --recurse-submodules https://github.com/elgrande73/FOSS-voice-memo-android-app.git
# or, in an existing clone: git submodule update --init
./gradlew assembleRelease   # needs Android SDK 35, NDK 28.2.13676358 and CMake 3.31.6 (installed by Gradle/sdkmanager)
```

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for the architecture, data persistence rules, the match-confidence mapping and playback sequencing.

---

## 🚀 Building

Requirements: JDK 17+ and the Android SDK (platform 35, build tools 35). No API keys or credentials are needed.

```bash
./gradlew assembleDebug          # debug APK
./gradlew assembleRelease        # unsigned release APK (signing: create keystore.properties, see app/build.gradle.kts)
./gradlew testDebugUnitTest      # unit + Robolectric tests
./gradlew lintDebug              # Android lint
```

F-Droid preparation notes and the maintainer checklist are in [`docs/FDROID.md`](docs/FDROID.md).

---

## 🎨 Artwork & sounds

- The launcher icon is original artwork made for this project; the editable source is [`artwork/icon/voice_memo_icon.svg`](artwork/icon/voice_memo_icon.svg) (see [`artwork/icon/README.md`](artwork/icon/README.md)).
- All chimes are synthesized in code at runtime (`FeedbackSoundPlayer`); no audio files are bundled or downloaded.

Both are covered by the project license.

---

## 📄 License

Licensed under the [Apache License, Version 2.0](LICENSE).

---

## 📬 Contact

Made with love in Estonia 🇪🇪
