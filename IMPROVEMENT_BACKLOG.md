# Improvement backlog

Prioritised. Each item: evidence → proposed change → how to verify. Move finished items to `IMPROVEMENT_LOG.md`.

## Now

1. **Device verification** of this round: transcription time for a 4 s memo (debug and release, cold/warm, logcat
   "TranscriptionManager" stage line), tile/widget/launcher shortcut, notification with the setting off, wake-phrase
   start/stop and the late-stop window on a phone.
2. **q8_0 Whisper base on the device** (host: 14 % faster, same WER, half the memory): on-device quantisation of the
   verified download, or an officially checksummed source; measure on a phone first.
3. **Stopping right after speech** with recorded samples (0.2 s pause: stop 65 vs start 77 of 108).

## Next

2. **Wake phrase latency**: median 863 ms from phrase start (test). Check whether the trigger can be reported earlier
   without more false triggers.

## Later / needs a device

3. Real-voice recordings (with consent) to replace the synthetic corpus; microphone and room effects.
4. Transcription speed and memory on a real phone (base model, q5_0 vs f16).
5. Battery use of continuous monitoring.
