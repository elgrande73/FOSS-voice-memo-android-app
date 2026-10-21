# Improvement log

Newest first. Numbers come from synthetic fixtures unless stated otherwise.

## 2026-10-21 — Wake phrase: speaker-split corpus and mean-normalised matching

- **Corpus:** `WakePhraseCorpus` / `WakePhraseCorpusTest` with 98 new eSpeak NG fixtures, split by speaker into a
  development and a test set. Per-utterance confusion matrix, AUC, latency from phrase start, false activations per hour
  of negative speech, ms per frame.
- **Baseline (built-in reference, 50 %):** dev recall 0.292, FP 8/228, AUC 0.746; test recall 0.358, FP 19/228, AUC
  0.698; test 266 false activations per hour of negative speech.
- **Experiment, not adopted:** greedy selection of a larger built-in reference from 25 renditions. Dev F1 rose from
  0.416 to 0.476, but test F1 fell from 0.473 to 0.452 (over-fitting). The bundled reference is unchanged.
- **Adopted:** per-band mean normalisation before DTW. Chosen on dev data (weight 1); AUC dev 0.746 → 0.900, test
  0.698 → 0.836. Test FP 19 → 5 and false activations 266/h → 114/h of speech. Own voice held-out: AUC 0.834 → 0.903 (1
  sample), 0.874 → 0.914 (5); near-miss activations 600/h → 180/h.
- **Trade-off:** at 50 % some conditions lose recall (test 43 → 35 of 120). Documented; the score is not rescaled.
- Regression guards updated; docs section 17; CHANGELOG.

## 2026-10-21 — Removed the earlier default phrase completely

- Trigger type `KEYWORD_WAKE_PHRASE` with Room migration 1 → 2. Only that column changes; recordings and transcripts are
  untouched.
- The legacy template format and extractor were removed. Old samples are skipped, kept on disk and reported in Settings.
- The previous application ID is no longer named. A repository-wide text and binary search finds no reference.
- Commit `7a173a8`.
