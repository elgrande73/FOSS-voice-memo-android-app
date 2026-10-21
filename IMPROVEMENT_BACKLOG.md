# Improvement backlog

Prioritised. Each item: evidence → proposed change → how to verify. Move finished items to `IMPROVEMENT_LOG.md`.

## Now

1. **Short-memo transcription cost.** Evidence: a 4 s memo takes almost as long as a 12 s memo (1.47 s vs 1.63 s
   on the host) because whisper encodes a full 30 s window. Idea: whisper.cpp `audio_ctx` (shorter encoder context)
   for short memos. It can change accuracy, so measure WER on the fixture sentences first and adopt only with no
   quality loss.

## Next

2. **Wake phrase recall at 50 % with mean normalisation** (some held-out conditions lost recall). Ideas, all decided on
   dev data: candidate window lengths (0.70–1.35 × template), the 0.040 spectral-flux gate, the minimum level. Keep each
   only if test AUC and FP do not get worse.
3. **Near-miss rejection for "Tango Mango" / "Mango tea"**: anti-references (score against near-miss templates and veto
   when they match better). Needs bundled near-miss features; measure on the corpus first.
4. **Wake phrase latency**: median 863 ms from phrase start (test). Check whether the trigger can be reported earlier
   without more false triggers.

## Later / needs a device

5. Real-voice recordings (with consent) to replace the synthetic corpus; microphone and room effects.
6. Transcription speed and memory on a real phone (base model, q5_0 vs f16).
7. Battery use of continuous monitoring.
