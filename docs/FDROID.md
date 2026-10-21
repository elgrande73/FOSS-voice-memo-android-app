# F-Droid readiness

This document records the FOSS/F-Droid review of Voice Memo and what the maintainer still has to do before
submitting to [fdroiddata](https://gitlab.com/fdroid/fdroiddata). It is a preparation checklist; it does not mean
the app has been accepted by F-Droid. Inclusion is decided by the F-Droid review and its build server.

## Summary of the review

| Area | Finding | Status |
|---|---|---|
| License | Apache-2.0 (`LICENSE`, full text). All source files are covered. | ✅ |
| Runtime dependencies | AndroidX (Compose, Room, Lifecycle, Activity, Core), Kotlin stdlib/coroutines, JetBrains annotations, JSpecify, Guava `listenablefuture` — all Apache-2.0, all from Maven Central / Google Maven. No Google Play Services, Firebase, analytics, crash reporting or ad SDKs. | ✅ |
| Removed dependencies | Retrofit, OkHttp, Moshi (+ codegen) and the Secrets Gradle plugin were declared but never used (no `INTERNET` permission, no references in the code) and have been removed. `metadata.json` (Google AI Studio project file claiming a server-side Gemini capability that the app never used) was removed. | ✅ |
| Build plugins | Android Gradle Plugin, Kotlin, Compose compiler, KSP (Room), Roborazzi (tests only), Foojay toolchain resolver (settings plugin; only downloads a JDK if a toolchain is requested, none is). | ✅ (Foojay: see risks) |
| Build | `./gradlew assembleRelease` from source with the Android SDK, **NDK r28c (28.2.13676358)** and CMake 3.31.6; the git submodule `third_party/whisper.cpp` (MIT, pinned to v1.9.5) must be checked out (`submodules: true`). No credentials, API keys, `.env` files or prebuilt native libraries. Dependency metadata is excluded from the APK/AAB (`dependenciesInfo`). | ✅ (verify on the F-Droid build server) |
| Native code | whisper.cpp/ggml compiled from source for arm64-v8a, armeabi-v7a and x86_64 (`app/src/main/cpp`). 16 KB page alignment. | ✅ |
| Speech model | Not in the APK or the repository. The user downloads OpenAI Whisper base (MIT) as whisper.cpp's `ggml-base.bin` (142 MiB) from the whisper.cpp project on Hugging Face with *Settings → Download Whisper Base model*; it is verified against the SHA-1 published in the pinned whisper.cpp submodule before use. | ⚠️ review |
| Network / tracking | `INTERNET` permission **only** for that model download, which runs only after an explicit user action (HTTPS, no cleartext; no other networking code, no analytics, no ads, no downloads of code). Recording, wake phrase, playback and transcription with an installed model work offline. Extra models can also be imported from a file. | ⚠️ reviewers decide (possible *NonFreeNet* anti-feature because the file is hosted on Hugging Face) |
| Assets | Launcher icon: original artwork (`artwork/icon`), Apache-2.0. Chimes: synthesized at runtime, no audio files. The previous icon bitmap (`img_app_icon.jpg`, unknown provenance) was removed. | ✅ |
| Test fixtures | `app/src/test/resources/audio`: synthetic speech generated with eSpeak NG (GPL-3.0-or-later tool, used offline once; not a build dependency and not packaged into the APK). Regeneration script included. | ✅ (reviewers may check) |
| Audio processing | Noise suppression, noise-floor estimation and wake-phrase detection are implemented in Kotlin on platform APIs. Speech-to-text runs locally with whisper.cpp (see `docs/TRANSCRIPTION.md`). | ✅ |
| Permissions | Only what the features need; see the table in `README.md`. The shortcut features added no permission. | ✅ |
| Accessibility service | Optional `RecordingShortcutAccessibilityService` (off until the user enables it): key-event filtering only, no accessibility events, no window content. Used for shortcuts outside the app. Reviewers may check its description string. | ✅ (reviewers may check) |
| Anti-features | Likely none. The former dependency on the device's (often proprietary/online) system speech-recognition service was removed; transcription is local. | ✅ (reviewers decide) |
| Store metadata | `fastlane/metadata/android/en-US/` (title, descriptions, changelog, 512 px icon). | ✅ |
| Build metadata | Draft in `fdroid/metadata/io.github.elgrande73.voicememo.yml`. | ⚠️ maintainer input needed |

## Maintainer checklist (cannot be completed from the repository alone)

1. **Confirm the application ID** `io.github.elgrande73.voicememo`. It is derived from the GitHub account that hosts
   the source and is provisional until confirmed. It must not change after the first release.
2. **Tag a release** (e.g. `v1.0` matching `versionName "1.0"` / `versionCode 1` in `app/build.gradle.kts`) and set
   `commit:` in the F-Droid metadata. No tag or release commit exists yet.
3. **Signing / reproducible builds**: `AllowedAPKSigningKeys` was carried over from the previous metadata and must
   match the SHA-256 of the maintainer's release certificate. Remove `Binaries` and `AllowedAPKSigningKeys` if F-Droid
   should sign the APK itself instead of verifying a reproducible build.
4. **Screenshots**: add phone screenshots to `fastlane/metadata/android/en-US/images/phoneScreenshots/`.
5. **Submit** the metadata as a merge request to fdroiddata and run `fdroid lint` / `fdroid build` there (or with
   `fdroidserver` locally). These checks were not run in this repository.
6. **Old app**: decide what happens to the listing of the previously published app (other name and ID), e.g. mark it as superseded.
   Users must install Voice Memo separately; their old data does not migrate automatically (see README).

## Remaining risks

- **Foojay resolver plugin** in `settings.gradle.kts`: harmless while no Java toolchain is configured, but some F-Droid
  reviewers prefer it removed because it can download JDKs. It was kept to avoid changing the build setup without
  the maintainer's agreement.
- **Gradle wrapper**: `gradle/wrapper/gradle-wrapper.jar` is a binary; F-Droid's `gradle: yes` uses its own Gradle and
  ignores it. The wrapper download is pinned with `distributionSha256Sum`.
- **Model download host**: Hugging Face (whisper.cpp's official files). F-Droid may prefer a different host or flag the
  download; the source URL and checksum are constants in `ModelDownloader.kt` (`ModelSource.WHISPER_BASE`). The git
  history still contains the formerly bundled 55 MB model file.
- **APK size**: 12.8 MB (release, unsigned, all three ABIs), measured; the model download adds 142 MiB of app storage.
- **Default wake phrase quality**: the built-in "Mango Tango" reference is made from synthetic speech (eSpeak NG
  features, no audio) and recognises only some voices. Recording the phrase in one's own voice (Settings → Wake Phrase)
  is recommended. Detection accuracy
  and its limitations are documented in `docs/WAKE_PHRASE_DETECTION.md`.
