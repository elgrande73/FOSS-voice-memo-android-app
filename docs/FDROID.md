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
| Build | `./gradlew assembleRelease` from source with the Android SDK; no credentials, API keys, `.env` files or prebuilt binaries required. Dependency metadata is excluded from the APK/AAB (`dependenciesInfo`). | ✅ |
| Network / tracking | No `INTERNET` permission, no networking code, no analytics, no ads, no runtime downloads of code or assets. | ✅ |
| Assets | Launcher icon: original artwork (`artwork/icon`), Apache-2.0. Chimes: synthesized at runtime, no audio files. The previous icon bitmap (`img_app_icon.jpg`, unknown provenance) was removed. | ✅ |
| Permissions | Only what the features need; see the table in `README.md`. | ✅ |
| Anti-features | Likely none. Optional transcription (off by default) delegates to the device's system speech-recognition service, which may be proprietary or online; documented in the README. Reviewers may want to assess this. | ⚠️ review |
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
6. **Old app**: decide what happens to the Ciao Linda listing (`com.aistudio.ciaolinda`), e.g. mark it as superseded.
   Users must install Voice Memo separately; their old data does not migrate automatically (see README).

## Remaining risks

- **Foojay resolver plugin** in `settings.gradle.kts`: harmless while no Java toolchain is configured, but some F-Droid
  reviewers prefer it removed because it can download JDKs. It was kept to avoid changing the build setup without
  the maintainer's agreement.
- **Gradle wrapper**: `gradle/wrapper/gradle-wrapper.jar` is a binary; F-Droid's `gradle: yes` uses its own Gradle and
  ignores it. The wrapper download is pinned with `distributionSha256Sum`.
- **System speech recognition**: see the anti-features row above.
- **Default wake phrase quality**: the built-in "Ciao Linda" template is synthesized from tones. In unit-test probes,
  adding even faint noise to that synthetic phrase dropped its match confidence to 0 %, so real speech is unlikely to
  match the default template reliably; recording the phrase in one's own voice (Settings → Wake Phrase) is
  recommended. This is a pre-existing product limitation, not a compliance issue.
