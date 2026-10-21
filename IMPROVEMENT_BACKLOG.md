# Improvement backlog

Prioritised. Each item: evidence → proposed change → how to verify. Move finished items to `IMPROVEMENT_LOG.md`.

## Now

1. **Wake phrase recall at 50 % with mean normalisation** (some held-out conditions lost recall). Ideas, all decided on
   dev data: candidate window lengths (0.70–1.35 × template), the 0.040 spectral-flux gate, the minimum level. Keep each
   only if test AUC and FP do not get worse.

## Next

2. **Near-miss rejection for "Tango Mango" / "Mango tea"**: anti-references (score against near-miss templates and veto
   when they match better). Needs bundled near-miss features; measure on the corpus first.
3. **Wake phrase latency**: median 863 ms from phrase start (test). Check whether the trigger can be reported earlier
   without more false triggers.

## Later / needs a device

4. Real-voice recordings (with consent) to replace the synthetic corpus; microphone and room effects.
5. Transcription speed and memory on a real phone (base model, q5_0 vs f16).
6. Battery use of continuous monitoring.
