# Improvement backlog

Prioritised. Each item: evidence → proposed change → how to verify. Move finished items to `IMPROVEMENT_LOG.md`.

## Now

1. **Transcription progress by stage** (user request). Evidence: the UI shows only "Transcribing… n %" from whisper's
   callback, and nothing while audio is read or the model loads (cold load takes seconds on a phone). Change: job stages
   queued → preparing audio → loading model → transcribing (engine % only once it reports) → saving → done / failed /
   cancelled, with elapsed time and audio duration. Verify: `TranscriptionTest` with a fake engine (stage order, no
   percentage before the engine reports, terminal state on every path) and Robolectric UI text.
2. **Transcription timing per stage, cold vs warm** on the host (`TranscriptionEndToEndHostTest`): WAV read, model load,
   `whisper_full`, save, RTF. Then check thread count (`TranscriptionSettings.threads = 4`) against
   `Runtime.availableProcessors()` — measure before changing.

## Next

3. **Wake phrase recall at 50 % with mean normalisation** (some held-out conditions lost recall). Ideas, all decided on
   dev data: candidate window lengths (0.70–1.35 × template), the 0.040 spectral-flux gate, the minimum level. Keep each
   only if test AUC and FP do not get worse.
4. **Near-miss rejection for "Tango Mango" / "Mango tea"**: anti-references (score against near-miss templates and veto
   when they match better). Needs bundled near-miss features; measure on the corpus first.
5. **Wake phrase latency**: median 863 ms from phrase start (test). Check whether the trigger can be reported earlier
   without more false triggers.

## Later / needs a device

6. Real-voice recordings (with consent) to replace the synthetic corpus; microphone and room effects.
7. Transcription speed and memory on a real phone (base model, q5_0 vs f16).
8. Battery use of continuous monitoring.
