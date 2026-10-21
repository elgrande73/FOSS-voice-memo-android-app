# Build-time patch of the pinned whisper.cpp (MIT, third_party/whisper.cpp, unmodified in git), used by the app and by
# the host test build (scripts/host-whisper-jni). Include after add_subdirectory(whisper.cpp).
#
# Single encoder pass with automatic language detection: whisper_full() encodes the first 30 s window once to detect
# the language and then encodes the very same window again to transcribe it. The encoder output (and the decoder's
# cross-attention cache computed from it) depends only on the audio, the window offset and the audio context, so the
# second pass is skipped when those are unchanged. Transcripts are identical; for a short memo with "Automatic"
# language the work is roughly halved (measured in docs/TRANSCRIPTION.md). Configuration fails if the source does not
# match, so an updated submodule cannot silently drop or misapply the patch.
set(_whisper_src "${WHISPER_DIR}/src/whisper.cpp")
file(READ "${_whisper_src}" _src)

set(_a_old "    // auto-detect language if not specified\n")
set(_a_new "    // Voice Memo patch: remember that language detection already encoded the first window\n    bool vm_first_window_encoded = false;\n    const int vm_encoded_audio_ctx = state->exp_n_audio_ctx;\n\n    // auto-detect language if not specified\n")
set(_b_old "        state->lang_id = lang_id;\n        params.language = whisper_lang_str(lang_id);\n")
set(_b_new "        state->lang_id = lang_id;\n        params.language = whisper_lang_str(lang_id);\n        vm_first_window_encoded = true;\n")
set(_c_old "        // encode audio features starting at offset seek\n        if (!whisper_encode_internal(*ctx, *state, seek, params.n_threads, params.abort_callback, params.abort_callback_user_data)) {\n")
set(_c_new "        // encode audio features starting at offset seek (Voice Memo patch: reuse the language-detection encoding of window 0)\n        const bool vm_reuse_encoding = vm_first_window_encoded && seek == 0 && state->exp_n_audio_ctx == vm_encoded_audio_ctx;\n        vm_first_window_encoded = false;\n        if (!vm_reuse_encoding && !whisper_encode_internal(*ctx, *state, seek, params.n_threads, params.abort_callback, params.abort_callback_user_data)) {\n")

foreach (_p a b c)
    string(FIND "${_src}" "${_${_p}_old}" _pos)
    if (_pos EQUAL -1)
        message(FATAL_ERROR "whisper.cpp patch '${_p}' does not apply: update app/src/main/cpp/whisper_patches.cmake for the new whisper.cpp version")
    endif ()
    string(REPLACE "${_${_p}_old}" "${_${_p}_new}" _src "${_src}")
endforeach ()

set(_patched "${CMAKE_CURRENT_BINARY_DIR}/whisper-patched/whisper.cpp")
file(WRITE "${_patched}.tmp" "${_src}")
# Rewrite only when the content changed (no needless rebuilds)
file(COPY_FILE "${_patched}.tmp" "${_patched}" ONLY_IF_DIFFERENT)

get_target_property(_whisper_sources whisper SOURCES)
set(_new_sources "")
set(_replaced FALSE)
foreach (_s IN LISTS _whisper_sources)
    get_filename_component(_name "${_s}" NAME)
    if (_name STREQUAL "whisper.cpp")
        list(APPEND _new_sources "${_patched}")
        set(_replaced TRUE)
    else ()
        list(APPEND _new_sources "${_s}")
    endif ()
endforeach ()
if (NOT _replaced)
    message(FATAL_ERROR "whisper.cpp source not found in the whisper target")
endif ()
set_target_properties(whisper PROPERTIES SOURCES "${_new_sources}")
# The copy keeps including whisper.cpp's private headers ("whisper-arch.h", ...)
target_include_directories(whisper PRIVATE "${WHISPER_DIR}/src")
