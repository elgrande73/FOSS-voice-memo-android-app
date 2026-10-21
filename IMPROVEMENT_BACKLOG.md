# Improvement backlog

Prioritised. Each item: evidence → proposed change → how to verify. Move finished items to `IMPROVEMENT_LOG.md`.

## Now

1. **Near-miss rejection for "Tango Mango" / "Mango tea"**: anti-references (score against near-miss templates and veto
   when they match better). Needs bundled near-miss features; measure on the corpus first (test near-miss FP at 50 %:
   5/192, at 40 %: 29/192).

## Next

2. **Wake phrase latency**: median 863 ms from phrase start (test). Check whether the trigger can be reported earlier
   without more false triggers.

## Later / needs a device

3. Real-voice recordings (with consent) to replace the synthetic corpus; microphone and room effects.
4. Transcription speed and memory on a real phone (base model, q5_0 vs f16).
5. Battery use of continuous monitoring.
