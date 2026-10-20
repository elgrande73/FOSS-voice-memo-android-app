#!/usr/bin/env bash
# Regenerates the speech fixtures used by the wake-phrase tests (16 kHz, mono, signed 16-bit little-endian raw PCM).
# Requires eSpeak NG and FFmpeg. Output is deterministic for a given eSpeak NG version (fixtures made with 1.51).
set -euo pipefail
cd "$(dirname "$0")"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
gen() { # voice speed pitch name text
  espeak-ng -v "$1" -s "$2" -p "$3" -w "$TMP/$4.wav" "$5"
  ffmpeg -loglevel error -y -i "$TMP/$4.wav" -ar 16000 -ac 1 \
    -af "silenceremove=start_periods=1:start_threshold=-50dB,areverse,silenceremove=start_periods=1:start_threshold=-50dB,areverse,loudnorm=I=-20:TP=-3,volume=0dB" \
    -f s16le -acodec pcm_s16le "$4.pcm"
}
gen it 140 50 ciao_linda_a "Ciao Linda"
gen it 160 40 ciao_linda_b "Ciao, Linda"
gen it+f3 150 60 ciao_linda_c "Ciao Linda"
gen it 150 50 other_speech "Compra il latte domani"
gen en 160 50 other_speech_en "Remember the meeting"
