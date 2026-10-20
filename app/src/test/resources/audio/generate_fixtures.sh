#!/usr/bin/env bash
# Regenerates the speech fixtures used by the wake-phrase tests (16 kHz, mono, signed 16-bit little-endian raw PCM).
# Requires eSpeak NG and FFmpeg. Output is deterministic for a given eSpeak NG version (fixtures made with 1.51).
set -euo pipefail
cd "$(dirname "$0")"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
gen() { # voice speed pitch name text [word-gap in 10 ms units]
  espeak-ng -v "$1" -s "$2" -p "$3" -g "${6:-0}" -w "$TMP/$4.wav" "$5"
  ffmpeg -loglevel error -y -i "$TMP/$4.wav" -ar 16000 -ac 1 \
    -af "silenceremove=start_periods=1:start_threshold=-50dB,areverse,silenceremove=start_periods=1:start_threshold=-50dB,areverse,loudnorm=I=-20:TP=-3,volume=0dB" \
    -f s16le -acodec pcm_s16le "$4.pcm"
}
gen it 140 50 ciao_linda_a "Ciao Linda"
gen it 160 40 ciao_linda_b "Ciao, Linda"
gen it+f3 150 60 ciao_linda_c "Ciao Linda"
gen it 150 50 other_speech "Compra il latte domani"
gen en 160 50 other_speech_en "Remember the meeting"

# --- Evaluation dataset (docs/WAKE_PHRASE_DETECTION.md, WakePhraseEvaluationTest) ---
# Target speaker (voice "it"): enrollment samples (natural variation of pace, pitch and word gap) ...
gen it 135 50 target_enroll_1 "Ciao Linda"
gen it 150 45 target_enroll_2 "Ciao Linda" 2
gen it 165 55 target_enroll_3 "Ciao Linda"
gen it 145 60 target_enroll_4 "Ciao, Linda" 1
gen it 155 40 target_enroll_5 "Ciao Linda" 3
# ... and separate evaluation renditions never used for enrollment
gen it 125 48 target_eval_1 "Ciao Linda"
gen it 175 52 target_eval_2 "Ciao Linda"
gen it 140 35 target_eval_3 "Ciao Linda" 2
gen it 160 65 target_eval_4 "Ciao Linda"
gen it 130 55 target_eval_5 "Ciao, Linda" 4
gen it 185 45 target_eval_6 "Ciao Linda" 1
# Other speakers saying the wake phrase (secondary use case)
gen it+f2 150 50 other_speaker_1 "Ciao Linda"
gen it+m3 145 50 other_speaker_2 "Ciao Linda"
gen en 150 50 other_speaker_3 "Ciao Linda"
# Similar-sounding phrases and partial phrases (must not trigger)
gen it 150 50 neg_similar_1 "Ciao Lina"
gen it 150 50 neg_similar_2 "Ciao Lucia"
gen it 150 50 neg_similar_3 "Cara Linda"
gen it 150 50 neg_similar_4 "Mia Linda"
gen it 150 50 neg_similar_5 "Ciao bella"
gen it 150 50 neg_partial_1 "Ciao"
gen it 150 50 neg_partial_2 "Linda"
# Ordinary conversation without the wake phrase
gen it 150 50 neg_speech_1 "Domani devo chiamare il dottore"
gen it 160 45 neg_speech_2 "Che tempo fa oggi a Milano"
gen it 140 55 neg_speech_3 "Ricordati di comprare il pane e le uova"
gen en 155 50 neg_speech_4 "Let us meet at the station at nine"
gen en 165 45 neg_speech_5 "Can you send me the file later"
