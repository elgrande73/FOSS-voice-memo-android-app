# Voice Memo

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![API](https://img.shields.io/badge/API-24%2B-brightgreen.svg)](https://developer.android.com/about/versions/nougat)

Voice Memo is a free, open-source Android app for hands-free voice memos. Say **"Mango Tango"** and it starts
recording; say it again and the memo is saved. Memos can be turned into text on your phone. There are no accounts, ads
or analytics.

## Features

- **Hands-free recording.** Start a memo with the wake phrase "Mango Tango", or record your own phrase. Alternatively,
  any sound above a level you choose can start a memo.
- **One-tap recording.** A large Quick Memo button, a Quick Settings tile, a home-screen widget, a launcher shortcut,
  a button in the notification (also on the lock screen, can be turned off) and an optional keyboard shortcut.
- **Nothing cut off.** A short buffer keeps the first word; a memo ends after a pause you choose, when you repeat the
  wake phrase, or when you tap Stop.
- **Speech-to-text on the phone.** Transcribe memos with whisper.cpp and the Whisper base model (English by default,
  99 languages, or automatic detection). The model is a one-time download of about 148 MB that you start yourself.
- **History.** Search, filters, favourites, playback speed, autoplay through the list, and export of one memo (WAV)
  or many (ZIP with the WAV files and a CSV/JSON list).
- **Your microphone of choice.** Phone, wired headset, USB and Bluetooth headset microphones.

## Install

- **F-Droid:** Voice Memo is not on F-Droid yet. A submission is being prepared; this README will link to the listing
  once F-Droid has accepted it.
- **From source:** see [Building from source](#building-from-source).

Requires Android 7.0 (API 24) or newer. The app is about 13 MB; the optional speech model needs about 148 MB more.

## Getting started

1. Open Voice Memo and allow microphone access (and notifications, so you can see when monitoring runs).
2. Tap **Quick Memo** to record right away, or **Start Monitoring** and say **"Mango Tango"**.
3. For reliable wake-phrase detection, record the phrase in your own voice: *Settings → Wake Phrase → Record Phrase*.
   The built-in phrase was made from synthetic voices and does not recognise every voice.
4. To get text: *Settings → Speech-to-Text → Download Whisper Base model*, then tap **Transcribe** on a memo in
   *History*.

The in-app **Guide** tab explains every feature.

## Privacy

- Recording, wake-phrase detection and transcription run on your phone. No audio or text is uploaded.
- The only internet access is the speech-model download you start in Settings. It comes from the whisper.cpp
  project's files on Hugging Face over HTTPS and is checked against a published checksum before use. Everything else
  works offline.
- No analytics, crash reporting, ads or trackers.
- Memos are WAV files in the app's private storage. Android's app backup is enabled, so if you turn on backup in
  Android's settings, memos and settings can be included in it. The speech model is not backed up.
- While monitoring runs, anyone holding your locked phone can start or stop a memo from the notification or the tile,
  unless you turn this off (*Settings → Shortcuts & Lock Screen*). Memos can only be played after unlocking.

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | Recording memos and listening for the wake phrase |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` | Monitoring in the background, with a visible notification |
| `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_MEDIA_PROCESSING` | Finishing a model download or a transcription in the background, with a notification |
| `POST_NOTIFICATIONS` | Monitoring, download and transcription notifications |
| `WAKE_LOCK` | Keeps monitoring running with the screen off |
| `MODIFY_AUDIO_SETTINGS` | Using USB and Bluetooth microphones |
| `BLUETOOTH` (Android 11 and older), `BLUETOOTH_CONNECT` | Using Bluetooth headset microphones |
| `INTERNET` | Only the speech-model download you start in Settings |

## Coming from the earlier app?

Voice Memo was previously published under a different name and application ID. Android treats it as a separate app:
it cannot read the earlier app's memos or settings, and there is no importer. Export your recordings from the earlier
app before uninstalling it; the WAV files play in any audio player. Then record the wake phrase again in Voice Memo.

## Documentation

- [CHANGELOG.md](CHANGELOG.md): release notes.
- [docs/TRANSCRIPTION.md](docs/TRANSCRIPTION.md): speech-to-text, the model, languages and measurements.
- [docs/WAKE_PHRASE_DETECTION.md](docs/WAKE_PHRASE_DETECTION.md): how the wake phrase is detected, and its accuracy
  on synthetic test speech.
- [docs/SHORTCUTS_AND_LOCK_SCREEN.md](docs/SHORTCUTS_AND_LOCK_SCREEN.md): tile, widget, shortcuts and what Android
  allows on the lock screen.
- [docs/AUDIO_INPUT.md](docs/AUDIO_INPUT.md): microphone selection, Bluetooth headsets and routing.
- [ARCHITECTURE.md](ARCHITECTURE.md): code structure and data-persistence rules.
- [docs/FDROID.md](docs/FDROID.md): F-Droid readiness and release checklist.

## Bugs and contributions

Report bugs and suggest features in the [issue tracker](https://github.com/elgrande73/FOSS-voice-memo-android-app/issues).
Pull requests are welcome; please read [ARCHITECTURE.md](ARCHITECTURE.md) and keep changes focused, with tests for
behaviour changes.

## Building from source

Requirements: JDK 17+, Android SDK 35, NDK 28.2.13676358 (r28c) and CMake 3.31.6. The SDK manager can install the
last three. No API keys or credentials are needed. The speech engine (whisper.cpp) is compiled from source from a git
submodule; no prebuilt native libraries or model files are used.

```bash
git clone --recurse-submodules https://github.com/elgrande73/FOSS-voice-memo-android-app.git
# in an existing clone: git submodule update --init
./gradlew assembleDebug          # debug APK
./gradlew assembleRelease        # release APK, unsigned unless keystore.properties exists (see app/build.gradle.kts)
./gradlew testDebugUnitTest      # unit and Robolectric tests
./gradlew lintDebug              # Android lint
```

Built with Kotlin, Jetpack Compose (Material 3), Room, coroutines and Android's audio APIs.

## License and credits

Voice Memo is licensed under the [Apache License 2.0](LICENSE).

- [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (MIT), compiled into the app.
- OpenAI [Whisper](https://github.com/openai/whisper) "base" model weights (MIT), downloaded only on request.
- The launcher icon is original artwork for this project ([artwork/icon](artwork/icon/README.md)); the chimes are
  generated in code.

Details: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The license texts are also in the app under *Settings →
About → Open-source licenses*.

Made with love in Estonia 🇪🇪
