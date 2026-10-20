# Voice Memo

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![API](https://img.shields.io/badge/API-24%2B-brightgreen.svg)](https://developer.android.com/about/versions/nougat)

A privacy-first, open-source Android app for automatic, hands-free voice memos. Detection (voice activity, loudness, wake phrase) runs entirely on the device: no accounts, no cloud services, no analytics, no ads.

The default wake phrase is **"Ciao Linda"**.

---

## ✨ Features

- **Automatic recording**: starts when speech or sound exceeds the activation threshold and stops after a configurable silence timeout.
- **Wake phrase**: say **"Ciao Linda"** to start (and optionally stop) a memo. An offline keyword spotter (spectral filterbanks + Dynamic Time Warping) is built in; you can optionally record one to five samples of the phrase in your own voice (natural variations help); they are matched on the device and never leave it. When the phrase is repeated to stop a memo, the spoken stop phrase is removed from the recording; a memo ended by the silence timeout keeps its complete ending. Monitoring and the Settings phrase test use the same detection pipeline, and monitoring pauses automatically while a phrase is recorded in Settings.
- **Adaptive noise handling**: the background noise floor is estimated automatically and conservative spectral noise suppression keeps background sounds from being mistaken for the wake phrase (see [`docs/WAKE_PHRASE_DETECTION.md`](docs/WAKE_PHRASE_DETECTION.md)).
- **Match confidence threshold**: one setting (50 %–80 %) controls how closely speech must match the wake phrase; the dashboard shows the same value as the *Trigger threshold* next to a live match gauge.
- **Rolling pre-trigger buffer**: keeps the start of your sentence.
- **Playback queue**: playing a memo from the list automatically continues with the next memo of the list you are viewing (filters and search respected), framed by short beginning and ending chimes.
- **Recording chimes**: optional sounds when a recording starts and stops.
- **Audio routing**: built-in microphone, USB audio and Bluetooth headsets.
- **Local storage & export**: 16-bit PCM WAV files indexed in a local Room database; search, favorites, playback speed, ZIP export.
- **Material 3 UI** with light/dark theming.

---

## 🆔 New application identity

This app was previously published as **Ciao Linda** (`com.aistudio.ciaolinda`). It is now **Voice Memo** with the application ID **`io.github.elgrande73.voicememo`**.

> ⚠️ The ID `io.github.elgrande73.voicememo` is derived from the GitHub account hosting the source code (`github.com/elgrande73`). It is **provisional until the maintainer confirms it** before the first release; changing it after a release would again create a separate app.

Changing the application ID is a deliberate breaking change. Android treats Voice Memo as a **different app**, not as an update of Ciao Linda:

| Scenario | What happens |
|---|---|
| **Fresh installation of Voice Memo** | Starts with no memos, the built-in "Ciao Linda" wake phrase and a 50 % match confidence threshold. |
| **Update from one Voice Memo version to a later one** | Memos, audio files, settings, the threshold, your recorded wake-phrase samples (a single phrase recorded with an earlier version becomes sample 1), the test recording and its result are kept (see [Persistent user data](ARCHITECTURE.md#5-persistent-user-data--stable-identifiers)). |
| **Voice Memo installed alongside the old Ciao Linda app** | Both apps are installed independently. Voice Memo **cannot read** Ciao Linda's private database, settings, recordings or wake-phrase template (Android app sandbox), and does not claim to have imported them. |
| **Moving data from Ciao Linda** | There is **no automatic import**. Recordings can be moved manually: in Ciao Linda use *History → Export All (ZIP)* and keep the archive; the WAV files remain playable with any audio player. Voice Memo has no importer, so settings and the wake-phrase template must be configured again (record the phrase once more under *Settings → Wake Phrase*). |

---

## 🔒 Privacy & Permissions

- **No network access**: the app does not request the `INTERNET` permission and contains no networking libraries, analytics, trackers or ads.
- **Optional transcription** (off by default): uses the device's system speech-recognition service (`android.speech.SpeechRecognizer`). That service is provided by the device (on many phones a proprietary service) and may itself use the network; the app requests offline recognition (`EXTRA_PREFER_OFFLINE`). *Known limitation:* the current implementation listens to the microphone after a memo is saved instead of transcribing the saved file.
- **Backups**: Android's standard app backup is enabled (`allowBackup`), so memos and settings can be included in device backups configured by the user.

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | Microphone capture for monitoring, recording and wake-phrase training/testing |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` | Continuous background monitoring with a visible notification |
| `POST_NOTIFICATIONS` | Monitoring status and "memo saved" notifications |
| `WAKE_LOCK` | Keeps monitoring running with the screen off |
| `MODIFY_AUDIO_SETTINGS` | Audio routing for external/Bluetooth microphones |
| `BLUETOOTH` (≤ Android 11), `BLUETOOTH_CONNECT` | Detecting and using Bluetooth headset microphones |

---

## 🛠️ Tech Stack

- **Kotlin** & **Jetpack Compose** (Material 3)
- **Room** (local database), **Kotlin Coroutines & Flow**
- **Android AudioRecord / AudioTrack / MediaPlayer** (platform APIs)

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
