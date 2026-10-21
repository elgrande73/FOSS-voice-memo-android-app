# Improvement log

Newest first. Numbers come from synthetic fixtures unless stated otherwise.

## 2026-10-21 — Wake phrase: window length and flux gate (not adopted)

- **Hypothesis:** different candidate window lengths, or a different stationary-sound (spectral-flux) gate, recover
  recall at 50 % after mean normalisation.
- **Measured on dev only** (built-in reference, 300 utterances):

  | Variant | TP | FP | AUC |
  |---|---|---|---|
  | Base | 23 | 0 | 0.900 |
  | Minimum length 0.6× | 22 | 0 | 0.894 |
  | Minimum length 0.8× | 24 | 0 | 0.904 |
  | Maximum length 1.2× | 23 | 0 | 0.897 |
  | Maximum length 1.5× | 23 | 0 | 0.893 |
  | Flux gate 0.03 | 23 | 0 | 0.858 |
  | Flux gate 0.05 | 23 | 0 | 0.901 |

- Every difference is within one utterance, so nothing is worth a change and none was tested on the test split.
- **Conclusion:** recall for unseen voices at 50 % is limited by how far apart voices are, not by these parameters.
  Recording one's own samples remains the recommended setup.

## 2026-10-21 — Transcription: shorter encoder context for short memos (not adopted)

- **Hypothesis:** whisper.cpp `audio_ctx`, sized to the memo length plus a margin, makes short memos faster without
  losing quality.
- **Measured** (temporary experiment; host, base q5_0, greedy, 21 synthetic sentences in 5 languages, language given):

  | Encoder context | WER | Total decode time |
  |---|---|---|
  | Default (30 s) | 0.318 (62/195) | 33.5 s |
  | Memo + 64 frames | 0.785 | 41.2 s |
  | Memo only | 1.015 | 59.6 s |

- With the shorter context, accuracy collapses and decoding gets slower because of repetitions and hallucinated text.
- **Decision:** rejected; the default context is kept and the experiment code removed.
- Backlog item closed.

## 2026-10-21 — Transcription: real stage progress, timings, threads

- **Stages:** queued → preparing audio → loading model (only when it is not loaded) → transcribing → saving, then a
  completed, cancelled or failed outcome. Each stage shows elapsed time and memo length, and the notification shows
  the same stage. A percentage appears only for memos over 30 s: whisper.cpp reports per 30 s window, so it jumps
  0 → 100 on short memos.
- **Measured** (`TranscriptionTimingHostTest`, host, base q5_0):
  - Decoding is 96–99 % of the time; the cold model load is 471 ms; WAV read and save take a few ms.
  - RTF with 4 threads: 0.37 for a 4 s memo, 0.09 for a 35 s memo.
  - 2 threads are about 1.8× slower than 4.
- **Change:** up to 4 threads during monitoring (was 2), leaving two cores free. Detection costs about 0.1 ms per
  50 ms frame.
- Not changed, to protect quality: beam search stays opt-in; audio is not truncated.
- Not verified on a phone.

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
