# Continuous improvement process

How work on Voice Memo is chosen, verified and recorded across sessions. Project rules: `CLAUDE.md`.

## Files

| File | Purpose |
|---|---|
| `IMPROVEMENT_BACKLOG.md` | Candidate work, prioritised; each item with evidence and a way to verify it |
| `IMPROVEMENT_LOG.md` | What was done per iteration: change, measurements (before → after), decision, commit |
| `NEXT_SESSION.md` | Where the last session stopped and what to do first next time |

Update all three after every meaningful iteration, in the same commit as the change.

## Loop

1. **Pick** the highest-priority backlog item that can be verified in this environment (no device or emulator here).
   Priority: user data safety > correctness and stuck states > wake-phrase accuracy > transcription speed/feedback >
   polish.
2. **Measure first.** Reproduce the problem or record a baseline with an existing test or a new one. No measurement,
   no claim.
3. **Change one variable at a time.** Decide on development data (wake phrase: `WakePhraseCorpus.DEV`, benchmark tuning
   conditions); confirm once on held-out data (`WakePhraseCorpus.TEST`, `HELD_OUT`). If the held-out result does not
   confirm the improvement, keep the earlier behaviour and log the experiment as not adopted.
4. **Guard.** Add or update a regression test with values derived from the measurement (not targets).
5. **Verify.** Targeted tests, then `./gradlew assembleDebug assembleRelease lintDebug testDebugUnitTest
   compileDebugAndroidTestKotlin`. Report failures as they are.
6. **Record.** `CHANGELOG.md` and docs for user-visible changes; log entry; backlog and next-session update.
7. **Commit and push** to the working branch. Never rewrite shared history, publish releases or touch signing.

## Evidence rules

- Synthetic fixtures (eSpeak NG) show relative differences only; say so wherever numbers appear.
- Wake phrase: evaluate per utterance, never per frame; report the full confusion matrix, AUC, latency and false
  activations per hour of negative speech.
- Transcription: report cold and warm runs separately, the real-time factor, and the hardware (host CPU vs device).
- Never fabricate results; when something could not be verified (device behaviour, real voices), say so.
