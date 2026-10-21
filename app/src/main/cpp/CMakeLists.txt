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

add_library(vmwhisper SHARED voicememo_whisper.cpp)
target_link_libraries(vmwhisper PRIVATE whisper ggml log android)
target_compile_options(vmwhisper PRIVATE -O2 -fvisibility=hidden)
# 16 KB page alignment (Android 15+ devices)
target_link_options(vmwhisper PRIVATE "-Wl,-z,max-page-size=16384")
