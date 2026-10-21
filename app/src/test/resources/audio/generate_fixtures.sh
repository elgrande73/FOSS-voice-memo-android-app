#!/usr/bin/env bash
# Regenerates the speech fixtures used by the wake-phrase tests (16 kHz, mono, signed 16-bit little-endian raw PCM).
# Requires eSpeak NG and FFmpeg. Output is deterministic for a given eSpeak NG version (fixtures made with 1.51).
set -euo pipefail
cd "$(dirname "$0")"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
gen() { # voice speed pitch name text [word-gap in 10 ms units]
  espeak-ng -v "$1" -s "$2" -p "$3" -g "${6:-0}" -w "$TMP/$4.wav" "$5"
  ffmpeg -nostdin -loglevel error -y -i "$TMP/$4.wav" -ar 16000 -ac 1 \
    -af "silenceremove=start_periods=1:start_threshold=-50dB,areverse,silenceremove=start_periods=1:start_threshold=-50dB,areverse,loudnorm=I=-20:TP=-3,volume=0dB" \
    -f s16le -acodec pcm_s16le "$4.pcm"
}
# Target speaker (voice en-us+m1): the wake phrase as a user enrolls and says it
gen en-us+m1 140 50 wake_phrase_a "Mango Tango"
gen en-us+m1 160 40 wake_phrase_b "Mango, Tango" 1
gen en+f2 150 60 wake_phrase_c "Mango Tango"
gen it 150 50 other_speech "Compra il latte domani"
gen en 160 50 other_speech_en "Remember the meeting"

# --- Evaluation dataset (docs/WAKE_PHRASE_DETECTION.md, WakePhraseEvaluationTest) ---
# Target speaker: enrollment samples (natural variation of pace, pitch and word gap) ...
gen en-us+m1 135 50 target_enroll_1 "Mango Tango"
gen en-us+m1 150 45 target_enroll_2 "Mango Tango" 2
gen en-us+m1 165 55 target_enroll_3 "Mango Tango"
gen en-us+m1 145 60 target_enroll_4 "Mango, Tango" 1
gen en-us+m1 155 40 target_enroll_5 "Mango Tango" 3
# ... and separate evaluation renditions never used for enrollment
gen en-us+m1 125 48 target_eval_1 "Mango Tango"
gen en-us+m1 175 52 target_eval_2 "Mango Tango"
gen en-us+m1 140 35 target_eval_3 "Mango Tango" 2
gen en-us+m1 160 65 target_eval_4 "Mango Tango"
gen en-us+m1 130 55 target_eval_5 "Mango, Tango" 4
gen en-us+m1 185 45 target_eval_6 "Mango Tango" 1
# Other speakers saying the wake phrase (secondary use case)
gen en+f2 150 50 other_speaker_1 "Mango Tango"
gen en-us+m3 145 50 other_speaker_2 "Mango Tango"
gen it 150 50 other_speaker_3 "Mango Tango"
# Similar-sounding phrases and partial phrases (must not trigger)
gen en-us+m1 150 50 neg_similar_1 "Tango Mango"
gen en-us+m1 150 50 neg_similar_2 "Manga Tanga"
gen en-us+m1 150 50 neg_similar_3 "Bingo Tango"
gen en-us+m1 150 50 neg_similar_4 "Mango tea"
gen en-us+m1 150 50 neg_similar_5 "Banjo Django"
gen en-us+m1 150 50 neg_partial_1 "Mango"
gen en-us+m1 150 50 neg_partial_2 "Tango"
# The words inside ordinary sentences (must not trigger)
gen en-gb-x-rp 150 50 neg_context_1 "I bought a mango at the market"
gen en-gb-x-rp 150 50 neg_context_2 "We danced the tango last night"
# Ordinary conversation without the wake phrase
gen it 150 50 neg_speech_1 "Domani devo chiamare il dottore"
gen it 160 45 neg_speech_2 "Che tempo fa oggi a Milano"
gen it 140 55 neg_speech_3 "Ricordati di comprare il pane e le uova"
gen en 155 50 neg_speech_4 "Let us meet at the station at nine"
gen en 165 45 neg_speech_5 "Can you send me the file later"

# A custom phrase the same user records instead of the built-in one
gen en-us+m1 140 50 custom_enroll_1 "Open sesame"
gen en-us+m1 155 45 custom_enroll_2 "Open sesame" 2
gen en-us+m1 165 55 custom_enroll_3 "Open, sesame"
gen en-us+m1 130 48 custom_eval_1 "Open sesame"
gen en-us+m1 175 52 custom_eval_2 "Open sesame" 1
gen en-us+m1 150 60 custom_eval_3 "Open, sesame" 3
gen en-us+m1 145 40 custom_eval_4 "Open sesame"

# --- Built-in "Mango Tango" reference ---
# Reference renditions: their features form the reference bundled with the app
# (app/src/main/resources/wakephrase/mango_tango.bin, rebuilt and checked by BuiltInWakePhraseTest). Never evaluation clips.
gen en-us 150 50 mango_ref_1 "Mango Tango"
gen en 140 40 mango_ref_2 "Mango Tango"
gen en-us+f3 155 60 mango_ref_3 "Mango Tango"
gen en+m3 145 45 mango_ref_4 "Mango Tango" 1
gen de 150 50 mango_ref_5 "Mango Tango"
# Held-out voices, accents and speaking rates for the built-in reference (none of them used for it)
gen en-gb-x-rp 135 50 mango_eval_1 "Mango Tango"
gen en-us+f2 170 55 mango_eval_2 "Mango Tango"
gen en-029 150 45 mango_eval_3 "Mango Tango"
gen en-gb-scotland 160 50 mango_eval_4 "Mango Tango"
gen en+m7 130 40 mango_eval_5 "Mango Tango"
gen it 150 50 mango_eval_6 "Mango Tango"
gen es 150 50 mango_eval_7 "Mango Tango"
gen nl 145 55 mango_eval_8 "Mango Tango"
gen en-us 185 50 mango_eval_9 "Mango, Tango" 2
gen en+f4 125 65 mango_eval_10 "Mango Tango"
