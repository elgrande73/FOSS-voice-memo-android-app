# Ciao Linda

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![F-Droid](https://img.shields.io/badge/F--Droid-FOSS-green.svg)](https://f-droid.org/)
[![API](https://img.shields.io/badge/API-26%2B-brightgreen.svg)](https://android-arsenal.com/api?level=26)

A privacy-first, 100% open-source Android app for automatic hands-free voice notes and smart audio memo recording. All digital signal processing (DSP), voice activity detection (VAD), and keyword spotter analysis run entirely on-device without cloud dependencies, telemetry, or ads.

---

## ✨ Features

- **Automatic Sound & Speech Detection**: Background audio monitoring that starts recording automatically when audible speech or sound is detected, and ends after a period of silence.
- **"Ciao Linda" Wake Phrase Recognition**: On-device template-based keyword spotter with Dynamic Time Warping (DTW) and spectral filterbanks to initiate or stop memos hands-free.
- **Rolling Pre-Trigger Buffer**: Retains a 500ms–1200ms rolling audio buffer in memory so the initial syllable or word of your memo is never clipped.
- **Offline Speech-to-Text Transcription**: Optional integration with on-device speech recognition engines for automated memo transcription.
- **Audio Hardware Routing**: Seamless support for built-in phone microphones, USB audio interfaces, and Bluetooth headsets with automatic hot-swap handling.
- **Media Playback Coexistence**: Automatically pauses detection during podcast/music playback to avoid false triggers, and immediately resumes when playback stops.
- **Local Persistence & Export**: Store 16-bit PCM WAV memos locally with Room database indexing, search, favorites, inline audio player with playback speed control, and one-click ZIP/CSV export.
- **Material 3 UI**: Clean, dynamic light/dark theming with edge-to-edge support.

---

## 🔒 Privacy & Permissions

- **100% Offline**: No network calls, no analytics, no third-party trackers.
- **Permissions**: Requires only `RECORD_AUDIO` (microphone access) and `FOREGROUND_SERVICE_MICROPHONE` (continuous background monitoring).

---

## 🛠️ Tech Stack

- **Kotlin** & **Jetpack Compose** (Material 3)
- **Room Database** (local persistence)
- **Kotlin Coroutines & Flow**
- **Android AudioRecord & AudioPlaybackCallback**

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for the complete arc42-aligned System & Software Architecture documentation.

---

## 🚀 Building

```bash
# Build Debug APK
gradle assembleDebug

# Run Unit Tests
gradle :app:testDebugUnitTest
```

---

## 📄 License

Licensed under the [Apache License, Version 2.0](LICENSE).

---

## 📬 Contact

Made with love in Estonia 🇪🇪

