#!/usr/bin/env bash
# Rebuilds app/src/main/assets/models/ggml-base-q5_0.bin from public sources and checks every step's checksum.
#
# Source: OpenAI Whisper "base" weights (MIT), as exported to ONNX by the k2-fsa/sherpa-onnx project and published
# as a GitHub release asset. The ONNX files contain every weight of the original checkpoint; onnx_whisper_to_ggml.py
# writes them in whisper.cpp's ggml format (same layout and tensor types as whisper.cpp's convert-pt-to-ggml.py), and
# whisper.cpp's own quantizer (third_party/whisper.cpp, pinned) produces the q5_0 file.
#
# Requirements: curl, tar (bzip2), python3 (venv), cmake, a C++ compiler. Network access only to github.com and pypi.org.
# Usage: scripts/whisper-model/build-bundled-model.sh [existing-tarball]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

TARBALL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2"
TARBALL_SHA256="911b2083efd7c0dca2ac3b358b75222660dc09fb716d64fbfc417ba6c99ff3de"
ENCODER_SHA256="5a6b87cb313993f6c9fefec9e7027556f6cb30becddf49655bee36c50ecc12d7"
DECODER_SHA256="8a12c3f6ad65bb5b86d7e6eccc302378f20f9fb2df6cb10747c62895da7ac194"
F16_SHA256="1971d0645e1342d74a8ff2fbc273f40d544dfcf5974a5f158d699602d66c09ad"
OUTPUT_SHA256="1dfdc5f732fa9be0107f46803c6b25c02b625c80b72a89541f23568bec00bea5"

check() { echo "$2  $1" | sha256sum -c --quiet - || { echo "checksum mismatch: $1" >&2; exit 1; }; }

if [ $# -ge 1 ]; then cp "$1" "$WORK/model.tar.bz2"; else curl -fsSL -o "$WORK/model.tar.bz2" "$TARBALL_URL"; fi
check "$WORK/model.tar.bz2" "$TARBALL_SHA256"
tar -xjf "$WORK/model.tar.bz2" -C "$WORK" sherpa-onnx-whisper-base/base-encoder.onnx sherpa-onnx-whisper-base/base-decoder.onnx
check "$WORK/sherpa-onnx-whisper-base/base-encoder.onnx" "$ENCODER_SHA256"
check "$WORK/sherpa-onnx-whisper-base/base-decoder.onnx" "$DECODER_SHA256"

python3 -m venv "$WORK/venv"
"$WORK/venv/bin/pip" install -q onnx==1.23.2 numpy==2.5.4 protobuf ml_dtypes typing_extensions
# Only the mel filter bank and tokenizer files (assets) of the openai-whisper package are used (MIT)
"$WORK/venv/bin/pip" install -q --no-deps openai-whisper==20250625
ASSETS="$("$WORK/venv/bin/python" -c 'import importlib.util, os; print(os.path.dirname(importlib.util.find_spec("whisper").origin))')"
"$WORK/venv/bin/python" "$ROOT/scripts/whisper-model/onnx_whisper_to_ggml.py" \
  "$WORK/sherpa-onnx-whisper-base/base-encoder.onnx" "$WORK/sherpa-onnx-whisper-base/base-decoder.onnx" "$ASSETS" "$WORK/ggml-base.bin"
check "$WORK/ggml-base.bin" "$F16_SHA256"

cmake -S "$ROOT/third_party/whisper.cpp" -B "$WORK/build" -DCMAKE_BUILD_TYPE=Release -DWHISPER_BUILD_TESTS=OFF > /dev/null
cmake --build "$WORK/build" -j4 --target whisper-quantize > /dev/null
"$WORK/build/bin/whisper-quantize" "$WORK/ggml-base.bin" "$WORK/ggml-base-q5_0.bin" q5_0 > /dev/null
check "$WORK/ggml-base-q5_0.bin" "$OUTPUT_SHA256"
cp "$WORK/ggml-base-q5_0.bin" "$ROOT/app/src/main/assets/models/ggml-base-q5_0.bin"
echo "OK: app/src/main/assets/models/ggml-base-q5_0.bin reproduced ($OUTPUT_SHA256)"
