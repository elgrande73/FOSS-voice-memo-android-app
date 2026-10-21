# Improvement log

Newest first. Numbers come from synthetic fixtures unless stated otherwise.

## 2026-10-21 — Release preparation 1.0.0

- Version 1.0.0 / versionCode 10000 (scheme major×10000 + minor×100 + patch; pre-releases used 1); *Settings → About*
  shows the installed version (`AppVersionTest` also checks the F-Droid draft and the fastlane changelog).
- Text audit: fixed descriptions that contradicted the code (media-playback setting described as a frequency filter,
  wake phrase described as needing the loudness threshold, configurable maximum length, stop-only mode, Guide chips).
- README rewritten for users; `docs/FDROID.md` turned into the 1.0.0 readiness checklist; release notes in CHANGELOG.
- Not done: screenshots, device testing, F-Droid tools (`fdroid lint`/`build`), tag and signing decisions (maintainer).

## 2026-10-21 — Requested fixes: controls, one-tap recording, parity, robustness, transcription speed

- **Transcription root cause:** debug builds compiled ggml/whisper.cpp at -O0 (AGP Debug). Host, same 4 s memo: -O0
  34.0 s, -O2 2.2 s, -O3 1.5 s. Now -O3 in every build type. Patch: one encoder pass with automatic language detection
  (3.40 → 1.93 s mean, identical transcripts on 42 memos).
- **Not adopted:**
  - flash attention: 15 % faster, but WER 0.303 → 0.323;
  - tiny: about 2× faster, WER 0.33–0.36 vs 0.28–0.30;
  - q8_0 (14 % faster, same WER): no pinned checksum to download it; follow-up.
- **Shortcuts:** the volume-button shortcut and the accessibility service were removed (migration v5).
- **Recording-controls setting:** the channel-level lock-screen visibility overrode the setting. Fixed: new channel, and
  no button at all when the setting is off.
- **New:** home-screen widget, launcher shortcut, and the tile now works with monitoring off (it opens the app).
- **Parity:**
  - Start and stop are identical in code. Measured with random frame phase: 57 vs 52–53 of 192.
  - Real asymmetry: a stop phrase said after a silence-timeout end started a new memo (31/36). Late-stop window of 5 s
    added.
- **Robustness:**
  - Recorded samples are now matched in a 20 dB range.
  - Held-out: 1 sample F1 0.519 → 0.780, 5 samples F1 0.677 → 0.862.
  - Cost: stopping 0.2 s after speech is harder (65 vs 77/108); the variation set lost 4 of 184.
  - The half-frame stream was rejected.
- **Restart:** a sticky restart without an intent now stops the service.

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
