# Native speech-to-text for Voice Memo: whisper.cpp (MIT, git submodule third_party/whisper.cpp, pinned) built from
# source with the app. ggml's CPU backend is built as loadable libraries; on arm64 one per instruction-set level
# (ARMv8.0 … ARMv9.2), and the best one the phone supports is chosen at run time, so older phones work and newer ones
# use dot-product / fp16 / int8-matmul instructions.
cmake_minimum_required(VERSION 3.22)
project(voicememo_whisper C CXX)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)
if (NOT EXISTS ${WHISPER_DIR}/CMakeLists.txt)
    message(FATAL_ERROR "whisper.cpp sources missing: run 'git submodule update --init' (third_party/whisper.cpp)")
endif ()

# Always optimise the inference code. The Android Gradle plugin builds debug variants with CMAKE_BUILD_TYPE=Debug
# (no -O flag, i.e. -O0) and release variants with RelWithDebInfo (-O2). Measured with the same engine on the build
# host (Whisper base, 4 s memo, 4 threads): -O0 34.0 s, -O2 2.2 s, -O3 1.5 s. Debug builds were therefore unusably
# slow on phones (over a minute per short memo). Debug info (-g) is kept; the packaged libraries are stripped.
foreach (lang C CXX)
    set(CMAKE_${lang}_FLAGS_DEBUG "-O3 -DNDEBUG -g")
    set(CMAKE_${lang}_FLAGS_RELWITHDEBINFO "-O3 -DNDEBUG -g")
endforeach ()

set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
else ()
    set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
endif ()
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
add_subdirectory(${WHISPER_DIR} whisper EXCLUDE_FROM_ALL)
include(${CMAKE_CURRENT_SOURCE_DIR}/whisper_patches.cmake)

add_library(vmwhisper SHARED voicememo_whisper.cpp)
target_link_libraries(vmwhisper PRIVATE whisper ggml log)
target_compile_options(vmwhisper PRIVATE -O3 -fvisibility=hidden)
# 16 KB page alignment (Android 15+ devices)
target_link_options(vmwhisper PRIVATE "-Wl,-z,max-page-size=16384")
