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

# --- Utterance-level corpus with a development / test split (WakePhraseCorpus, WakePhraseCorpusTest) ---
# Split by speaker (voice): no voice of the development set appears in the test set, the built-in reference or the
# enrollment samples. Development clips may be used to choose parameters; test clips only to confirm a decision.
# Development positives: the wake phrase in 12 further voices
gen en-us+m2 150 50 dev_pos_1 "Mango Tango"
gen en+f1 140 55 dev_pos_2 "Mango Tango"
gen en-gb-x-gbclan 155 45 dev_pos_3 "Mango Tango"
gen en-us-nyc 165 50 dev_pos_4 "Mango, Tango" 2
gen fr 145 50 dev_pos_5 "Mango Tango"
gen pt 150 50 dev_pos_6 "Mango Tango"
gen en+Andy 135 50 dev_pos_7 "Mango Tango"
gen en-us+Annie 160 60 dev_pos_8 "Mango Tango"
gen en+klatt 150 45 dev_pos_9 "Mango Tango"
gen de+m2 140 50 dev_pos_10 "Mango Tango" 1
gen en+Mike 175 40 dev_pos_11 "Mango Tango"
gen sv 150 55 dev_pos_12 "Mango Tango"
# Test positives: 10 more voices (the 10 mango_eval_* clips are test positives too)
gen en-gb-x-gbcwmd 150 50 test_pos_1 "Mango Tango"
gen pl 145 50 test_pos_2 "Mango Tango"
gen en+m5 160 45 test_pos_3 "Mango Tango"
gen en-us+f5 140 60 test_pos_4 "Mango Tango"
gen en+Paul 155 50 test_pos_5 "Mango, Tango" 2
gen en-us+Steph 150 55 test_pos_6 "Mango Tango"
gen ro 150 50 test_pos_7 "Mango Tango"
gen en+klatt3 135 50 test_pos_8 "Mango Tango"
gen en-us+m6 180 45 test_pos_9 "Mango Tango"
gen en+Max 145 40 test_pos_10 "Mango Tango" 1
# Near misses in the same voices (8 phrases × 4 voices per split)
NEAR=("Tango Mango" "Manga Tanga" "Bingo Tango" "Mango tea" "Banjo Django" "Mango" "Tango" "Fandango")
i=0; for v in en-us+m2 en+f1 fr en-us-nyc; do for p in "${NEAR[@]}"; do i=$((i+1)); gen "$v" 150 50 dev_near_$i "$p"; done; done
i=0; for v in en-gb-x-gbcwmd pl en+m5 en-us+f5; do for p in "${NEAR[@]}"; do i=$((i+1)); gen "$v" 150 50 test_near_$i "$p"; done; done
# Conversation: longer sentences, used for false activations per hour of speech
gen en-us+m2 160 50 dev_conv_1 "I will call you back after the meeting, it should not take long"
gen en+f1 150 55 dev_conv_2 "The man went to the tank to get some more water for the garden"
gen fr 150 50 dev_conv_3 "Nous allons manger au restaurant ce soir avec mes amis"
gen en-us-nyc 170 50 dev_conv_4 "Can you hand me the orange and the mango juice from the fridge"
gen it 150 45 dev_conv_5 "Andiamo a ballare il tango sabato sera in centro"
gen en-gb-x-gbclan 150 50 dev_conv_6 "Thank you so much, I really appreciate all the help you gave me today"
gen en-gb-x-gbcwmd 160 50 test_conv_1 "Let me know when you are ready to go and I will start the car"
gen pl 150 50 test_conv_2 "The tangled cables behind the desk need to be sorted out"
gen en+m5 155 45 test_conv_3 "My mother bought mangoes and bananas at the market on Sunday"
gen en-us+f5 150 60 test_conv_4 "Many tanks were parked along the road near the old station"
gen es 150 50 test_conv_5 "Mañana vamos a comprar mangos y naranjas en el mercado"
gen de 150 50 test_conv_6 "Wir treffen uns morgen um acht Uhr vor dem Bahnhof"
# Candidate renditions for the built-in reference (voices used in neither split): the reference is chosen from
# mango_ref_1..5 and these by greedy selection on the development split only (BuiltInReferenceSelection)
gen en+m4 145 50 mango_pool_1 "Mango Tango"
gen en-us+f1 155 55 mango_pool_2 "Mango Tango"
gen en+Alex 140 50 mango_pool_3 "Mango Tango"
gen en-us+Belinda 150 60 mango_pool_4 "Mango Tango"
gen en+Boris 160 45 mango_pool_5 "Mango Tango"
gen en-us+david 135 50 mango_pool_6 "Mango Tango" 1
gen en+grandma 150 50 mango_pool_7 "Mango Tango"
gen en-us+travis 165 50 mango_pool_8 "Mango Tango"
gen en+shelby 145 55 mango_pool_9 "Mango Tango"
gen en-us+victor 150 45 mango_pool_10 "Mango, Tango" 2
gen da 150 50 mango_pool_11 "Mango Tango"
gen fi 150 50 mango_pool_12 "Mango Tango"
gen hu 145 50 mango_pool_13 "Mango Tango"
gen cs 155 50 mango_pool_14 "Mango Tango"
gen en-gb-x-rp+f3 140 60 mango_pool_15 "Mango Tango"
gen en-us+m4 175 45 mango_pool_16 "Mango Tango"
gen en+Zac 130 50 mango_pool_17 "Mango Tango"
gen en-us+norbert 150 40 mango_pool_18 "Mango Tango"
gen ca 150 50 mango_pool_19 "Mango Tango"
gen en-gb-scotland+f3 150 55 mango_pool_20 "Mango Tango"
