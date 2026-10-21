#!/bin/sh
# Builds the JNI bridge for the host so WhisperNativeHostTest can run the real engine on the JVM.
set -e
cd "$(dirname "$0")/../.."
cmake -S scripts/host-whisper-jni -B build/host-whisper-jni -DCMAKE_BUILD_TYPE=Release
cmake --build build/host-whisper-jni -j4 --target vmwhisper
ls build/host-whisper-jni/lib
