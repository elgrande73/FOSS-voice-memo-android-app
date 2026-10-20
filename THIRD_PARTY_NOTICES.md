# Third-party components

Voice Memo itself is licensed under Apache-2.0 (`LICENSE`). It includes the components below; their license texts
are shipped inside the app (`app/src/main/assets/licenses/THIRD_PARTY_NOTICES.txt`, shown under *Settings →
Speech-to-Text → Open-source licenses*).

| Component | Version | Origin | License | Used for |
|---|---|---|---|---|
| whisper.cpp + ggml | v1.9.5 (`d1be6fde11ac6e0407606b4e42fe72d34add8037`), git submodule `third_party/whisper.cpp` | https://github.com/ggml-org/whisper.cpp | MIT | On-device transcription engine, compiled from source with the NDK |
| OpenAI Whisper "base" weights | 2022 release; ggml q5_0 file `app/src/main/assets/models/ggml-base-q5_0.bin` (SHA-256 `1dfdc5f7…`) | https://github.com/openai/whisper (weights), via the ONNX export published by k2-fsa/sherpa-onnx | MIT (code and weights) | Bundled speech model; reproduced by `scripts/whisper-model/build-bundled-model.sh` |

Build-time only (not shipped): `openai-whisper` Python package 20250625 (MIT; mel filter bank and tokenizer files used
by the model conversion), `onnx` (Apache-2.0), `numpy` (BSD-3-Clause), the sherpa-onnx ONNX export tooling
(Apache-2.0). Test fixtures are generated with eSpeak NG (GPL-3.0-or-later, used as a tool only).
