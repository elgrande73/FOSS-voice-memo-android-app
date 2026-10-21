# Third-party components

Voice Memo itself is licensed under Apache-2.0 (`LICENSE`). It includes the components below; their license texts
are shipped inside the app (`app/src/main/assets/licenses/THIRD_PARTY_NOTICES.txt`, shown under *Settings → About →
Open-source licenses*).

| Component | Version | Origin | License | Used for |
|---|---|---|---|---|
| whisper.cpp + ggml | v1.9.5 (`d1be6fde11ac6e0407606b4e42fe72d34add8037`), git submodule `third_party/whisper.cpp` | https://github.com/ggml-org/whisper.cpp | MIT | On-device transcription engine, compiled from source with the NDK |
| OpenAI Whisper "base" weights | 2022 release; not part of the app: downloaded on the user's request as whisper.cpp's `ggml-base.bin` (SHA-1 `46570746…`) | https://github.com/openai/whisper (weights), https://huggingface.co/ggerganov/whisper.cpp (ggml file) | MIT (code and weights) | Speech model, downloaded from Settings; a q5_0 version for tests is reproduced by `scripts/whisper-model/build-bundled-model.sh` |
| AndroidX (Activity, Core, Lifecycle, Room), Jetpack Compose (UI, Material 3, icons), Kotlin standard library, kotlinx.coroutines, JetBrains annotations, JSpecify, Guava `listenablefuture` | see `gradle/libs.versions.toml` | Google Maven, Maven Central | Apache-2.0 | App framework, UI, database |

Build-time only (not shipped): `openai-whisper` Python package 20250625 (MIT; mel filter bank and tokenizer files used
by the model conversion), `onnx` (Apache-2.0), `numpy` (BSD-3-Clause), the sherpa-onnx ONNX export tooling
(Apache-2.0). Test fixtures are generated with eSpeak NG (GPL-3.0-or-later, used as a tool only).
