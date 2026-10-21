# Wake-phrase detection: pipeline, noise suppression and validation

This document describes how Voice Memo recognises its wake phrase (default **"Mango Tango"**, section 15; users can
record any other phrase), why automatic monitoring used to report much lower match confidence than the Settings phrase
test, what was changed, and how the changes were measured. The only detector is lightweight template matching
(`TemplateKeywordDetector`, DTW); section 16 explains why no heavier engine is used.

**About the numbers.** All measurements use synthetic speech (eSpeak NG fixtures, `app/src/test/resources/audio`).
The evaluation set was regenerated with "Mango Tango" when it became the default; the tables in sections 10, 13 and 15
are current. Numbers marked *(earlier set)* in sections 1–4, 8, 11 and 14 were measured on the evaluation set used when
those changes were made (a different phrase, same procedure) and document why the decisions were taken.

## 1. Root cause of the monitoring / test discrepancy

Both workflows capture 16 kHz, mono, 16-bit PCM from `MediaRecorder.AudioSource.MIC` in 50 ms frames (800 samples)
and used the same `TemplateKeywordDetector` features, DTW matcher and score. Sample rate, channel count, PCM format,
scaling and model configuration were identical; the difference was in **which frames reached the matcher** and in
**what the enrolled template contained**:

1. **Monitoring fed the matcher only "loud" frames.** `MemoTriggerDetector` called the matcher only for frames above
   (or near) the activation threshold and skipped it after three quieter frames. The matcher's sliding window therefore
   held a few non-contiguous loud fragments of the phrase (quiet syllables, the "l"/"n"/"d" sounds and the pause were
   missing) mixed with stale fragments of earlier sounds. The Settings test fed every frame in order.
2. **The enrolled template was the whole 2.5 s recording, ~70 % silence.** Enrollment was meant to drop silent frames
   with `featureVector.sum() > 0.05`, but the vectors are L2-normalised, so that sum is always ≥ 1 and nothing was ever
   dropped. A 50-frame template requires candidate windows of at least 35 contiguous frames; the gated monitoring window
   rarely contained that many, so its score stayed at **0 %** (in real rooms ~20 % from stale fragments). The Settings
   test, which recorded the same kind of silence-padded 3 s clip, matched the silence as well and looked fine.
3. **Secondary:** features depended on absolute level (fixed log offset with a floor), so a phrase spoken further
   away than during enrollment scored lower, and background noise above the activation threshold made every frame
   "loud", so in noisy rooms monitoring fed noise to a template that was mostly background — and triggered on any speech.

Measured on identical fixture audio with the code before the change (96 cases: 2 noise types × 4 noise levels × 3
speech levels × 4 utterances, enrollment in the same environment, activation threshold 48 dB, match threshold 50 %):

| | Wake phrase: test / monitoring score | Wake phrase triggers | Other speech triggers | Mean \|test − monitoring\| |
|---|---|---|---|---|
| Quiet (−65 dBFS background) | 0.54 / **0.00** | **0 / 12** | 0 / 12 | 0.459 |
| Noise (−50…−40 dBFS) | 0.81 / 0.74 | 36 / 36 | **36 / 36** | 0.044 |

## 2. Unified pipeline (after)

```
Live microphone ─ AudioRecord ─┐                       ┌─ MemoTriggerDetector (monitoring)
Settings recording ─ AudioRecord┼─ PcmFrameAssembler ──┤      every frame → TemplateKeywordDetector
Saved test WAV (validated)  ───┘   (exact 50 ms frames) └─ WakePhraseEvaluator (Settings test, same detector code)
                                                               │
                                     WakePhraseFrontEnd (features) → DTW matcher → score → threshold
```

* **One front end** (`WakePhraseFrontEnd`) produces the features for enrollment, monitoring and the test.
* **One matcher and score** (`TemplateKeywordDetector`), fed **every frame in order** in both workflows.
* **One decision** (`MemoTriggerDetector`): trigger when `score ≥ Match confidence threshold` and the recent audio
  (last 2.5 s) reached the activation (loudness) threshold. The Settings test (`WakePhraseEvaluator`) runs the same
  state machine to decide "would trigger", and scores in a separate pass that never triggers so the reported score does
  not depend on the threshold.
* **Enrollment endpointing**: only frames ≥ 10 dB above the recording's noise floor (and within 35 dB of its peak) plus
  one frame of padding form the template; a recording without any quiet part uses the frames within 35 dB of the peak.
* **Intentional difference**: a 3 s test recording is too short for the adaptive noise floor to converge, so the
  evaluator primes it with the same recording first; during monitoring it has converged long before.
* **Frame assembly**: `PcmFrameAssembler` turns partial `AudioRecord` reads into exact 800-sample frames (previously a
  short read was processed together with stale samples left in the buffer).
* **Template format**: templates store `WakePhraseFrontEnd` features (`MAGIC_FRONT_END_TEMPLATE`). Templates in any
  other format (the very first app version used a different feature extractor) are no longer loaded: they stay on disk,
  are not used, and Settings asks the user to record the phrase again (`WakePhraseStore.unusableSampleCount`).

### Score semantics

For each candidate window the DTW path cost between template and recent frames is divided by the path length (mean
Euclidean distance between L2-normalised feature vectors, 0…√2) and mapped to
`score = clamp(1 − distance / 0.40, 0, 1)`. This is a **heuristic similarity score, not a calibrated probability**; the
UI shows it as "match" percentage. The user's *Match confidence threshold* (20–80 %) is compared with it directly
(`percent / 100`), and the dashboard shows the same value as *Trigger threshold*. The mapping constant 0.40 was not
changed. The dashboard gauge shows the latest score with a short peak-hold (×0.92 per frame) so brief matches stay
visible; the trigger decision always uses the raw, undecayed score of the current frame.

## 3. Noise floor estimation and noise suppression

**Noise floor** (`NoiseFloorEstimator`, per band and broadband, updated every 50 ms frame, no user setting):
minimum during the first 10 frames; then, in the dB domain, falls towards lower power with rate 0.2/frame
(~0.2 s), follows noise-like frames (< 6 dB above) with rate 0.05/frame (~1 s), and rises at most 2 dB/s on louder
frames, so a 2 s utterance moves it by ≤ 4 dB and transients have no lasting effect while a louder environment is
followed within seconds. Digital silence (muted frames, AudioRecord start-up) is ignored. The estimator is reset when
monitoring starts and whenever the capture stream restarts (e.g. another input device). The dashboard's "Noise Floor"
uses the same estimator.

**Suppression** (`WakePhraseFrontEnd`): spectral subtraction on the 16 band powers the matcher uses —
`gain = max(1 − 2·noise/power, −18 dB)`, smoothed per band (attack 0.7, release 0.3 per frame) so onsets and word
endings are not cut. It works where the matcher looks, needs no waveform resynthesis and adds no latency. Then:
causal temporal smoothing of band powers (0.3 × previous frame) and level-invariant log features (dB relative to the
loudest band, 40 dB range), L2-normalised. Suppression is bypassed during the first 10 frames and can be disabled
(`FrontEndConfig.BYPASS`).

Parameters were chosen on fixture grids and checked on held-out noise levels/seeds. Rejected after measurement: a
feature-domain noise gate and per-band SNR masking (both removed genuine matches), 30–35 dB range (lower detections),
60 dB range (matched everything), −6 dB gain floor (more false matches).

## 4. Results after the change (same 96 cases as above, earlier set)

| | Wake phrase: test / monitoring score | Wake phrase triggers | Other speech triggers | Mean \|test − monitoring\| |
|---|---|---|---|---|
| Quiet | 0.74 / 0.74 | **12 / 12** | 0 / 12 | 0.006 (max 0.025) |
| Noise (−50…−40 dBFS) | 0.49 / 0.50 | 17 / 36 | **0 / 36** | 0.014 (max 0.063) |

Suppression vs. bypass with the new pipeline (`WakePhraseNoiseRobustnessTest`): in noise 17/36 detections and
0/36 false matches with suppression, 20/36 and 11/36 without; in quiet 12/12 vs. 10/12, no false matches. Background is
attenuated by ~17.6 dB on average while quiet speech (−30 dBFS peak) loses ~1.5 dB (`WakePhraseFrontEndTest`).

Processing cost (JVM on the build machine, not a phone): front end ~20 µs, complete monitoring step including DTW
~40 µs per 50 ms frame with a 17-frame template. No per-frame allocation besides the 16-value feature vector.

## 5. Diagnostics

Debug builds log a one-second summary under the logcat tag `WakePhraseDiag` (`WakePhraseDiagnosticsLog`): format and
frame size, input RMS/peak dBFS, clipped samples, near-silent frames, noise floor, mean suppression, feature range,
template length and format, best raw score and DTW distance, displayed score, configured threshold, and processing time.
No audio or recognised content is logged; release builds create no logger.

## 6. Tests

`WakePhrasePipelineTest` (same audio through both workflows, original discrepancy, other speech, silence, levels, buffer
boundaries, sub-frame offsets, thresholds 50/65/80 %, loudness criterion, restarts, unsupported template formats, endpointing,
performance), `WakePhraseNoiseRobustnessTest`, `WakePhraseFrontEndTest`, `NoiseFloorEstimatorTest`,
`PcmFrameAssemblerTest`, `WakePhraseStoreTest` (format validation), `WakePhraseSamplesTest` (multi-sample enrollment),
`WakePhraseEvaluationTest` (metrics, sweep, false activations, processing time), `StopBehaviorTest` (stop reasons),
`WakePhraseStartStopTest` (start vs. stop on the same clips, level condition, latency), `LevelMeterTest` (live level).
Fixtures: `app/src/test/resources/audio`
(synthetic speech, see its README).

## 7. Limitations and required device tests

* Fixtures are synthetic speech; numbers show relative effects, not real-world accuracy. The matcher is a
  speaker-dependent template matcher: a different voice ("wake_phrase_c") does not match reliably, by design.
* **Frame alignment**: with non-overlapping 50 ms frames the score of the same phrase varies by up to ~0.2 depending on
  where frame boundaries fall. Overlapping frames would reduce this but change the template format and CPU cost.
* In loud background noise (−40 dBFS) detection drops substantially; suppression trades detections for no false matches.
* Not verifiable here: real microphones, AGC/noise suppression applied by the device's audio HAL, Bluetooth/USB inputs.

Manual device test plan:
1. Record the wake phrase in Settings with monitoring running: the dashboard must show "Paused: recording a phrase in
   Settings", the recording must start only afterwards, and monitoring must be active again when it finishes.
2. Run "Test Phrase" at 0.5 m and 2 m; then, with monitoring on, say the phrase at the same distances: the dashboard
   gauge should reach about the test score and trigger when it is ≥ the Trigger threshold.
3. Repeat with a TV/radio or fan in the background; check the Noise Floor value adapts within a few seconds and that
   normal conversation does not start recordings.
4. With `adb logcat -s WakePhraseDiag` (debug build), check levels, noise floor, scores and processing time.

## 8. How a memo ends (stop reasons)

Every memo is finalized in exactly one place (`MemoTriggerDetector.finalizeRecording`), with a
`RecordingStopReason`. Later stop requests are ignored, so a memo is never finalized twice.

| Reason | Trigger | Audio kept |
|---|---|---|
| `KEYWORD` | The wake phrase is repeated ("Start memo & stop if repeated") | Everything before the spoken stop phrase. The phrase and audio after it are removed, and a 10 ms fade-out avoids a click. |
| `SILENCE_TIMEOUT` | Silence timeout elapsed | Everything, including the final words and the trailing silence. No trimming. |
| `MAX_DURATION` | Maximum memo length reached, counted in captured audio rather than wall-clock time | Everything |
| `USER` | Stop button | Everything up to the request, plus the frame already in the capture pipeline. The capture thread finalizes after its next frame; a 400 ms watchdog finalizes if capture has stalled. |
| `SERVICE_STOPPED` | Monitoring stopped or cancelled while recording | Everything captured so far |
| `ERROR` | Microphone stream failed during a memo | Everything before the failure. The memo is not continued across the gap. |

Precedence for a single frame: a pending user or service request wins, then `KEYWORD`, then `MAX_DURATION`, then `SILENCE_TIMEOUT`.

**Stop-phrase removal** (`StopPhraseTrimmer`):
- The matcher reports how many frames its best window spans (`windowFrames`). The phrase therefore began about `windowFrames − 1` frames before the decision frame, which accounts for recognition latency.
- The trimmer then looks for the quietest frame between 2 frames before and 4 frames after that estimate. If that frame is at least 6 dB below the phrase's median level, it is a pause, and the cut goes after it. Otherwise the cut goes at the estimate.
- It never cuts into the frames before the memo started, never modifies the captured frames, and keeps the untrimmed audio if trimming fails.

Measured on the fixtures (`StopBehaviorTest`, current set):
- Combinations: 3 pause lengths, 2 renditions of the phrase and 3 sub-frame offsets.
- Every memo word was kept.
- At most 50 ms of the stop phrase remained.
- The cut fell 3–270 ms before the stop phrase started.

**Saving:**
- Completed memos are written atomically by `WavWriter.writeWavFileAtomic`: a temporary file, length and header check, then rename. A failed write is retried once.
- A failed write never replaces an existing file.
- Saving runs on `Dispatchers.IO`, never on the main thread.

## 9. Multi-sample enrollment (optional, 1–5 samples)

Enrolled samples are optional. Without any, the built-in "Mango Tango" reference is used (section 15). Settings shows "n of 5 samples" and lets the user add, replace or delete individual samples. A sixth sample is refused with a message.

**How the samples are used:**
- Each sample is a separate DTW reference in `TemplateKeywordDetector`.
- Every frame is matched against every reference.
- The frame score is the best reference score (`ReferenceAggregation.MAX`).
- Score semantics and the threshold are unchanged: `score = clamp(1 − DTW distance / 0.40, 0, 1)`.
- Monitoring and the Settings test both load all samples (`WakePhraseStore.loadSampleTemplates()`).

**Validation** (`WakePhraseEnrollment`). Rejected, each with a message:
- malformed frames
- silence (peak < −50 dBFS)
- a phrase shorter than 300 ms or longer than 2 s
- more than 1 % clipped samples
- a near-identical copy of an enrolled sample (mean DTW distance < 0.02)

Natural variation in pace, pitch, volume or distance is deliberately accepted.

**Storage:**
- Each sample is stored as `filesDir/wake_phrase/samples/sample_<id>.bin`.
- File format: magic `VMWS`, format version 1, id, creation time, phrase duration, peak level, and the feature template.
- Only spectral-shape features are stored. No raw audio is kept for enrollment.
- The data never leaves the device.
- An unreadable file is skipped, not deleted.
- The single template of earlier versions (`enrolled_template.bin`) is migrated once to sample 1. The migration is idempotent, and the old file is deleted only after the copy has been read back.
- Templates in other feature formats are skipped (kept on disk) and reported in Settings as unusable.

**Choice of aggregation** (measured, 5 samples, tuning set, earlier set). Mean of the best two references: AUC 0.807. Max: AUC 0.817. Max was chosen.

## 10. Evaluation procedure and results

`WakePhraseBenchmark` and `WakePhraseEvaluationTest`. Run with:

```bash
./gradlew testDebugUnitTest --tests '*WakePhraseEvaluationTest*' -i | grep MEASURE
```

**Data:** synthetic eSpeak NG voices; see `app/src/test/resources/audio/README.md`.
- Enrollment clips (`target_enroll_1..5`, "Mango Tango" in the target speaker's voice) are recorded in a quiet room and
  are never evaluated.
- Positives: `target_eval_1..6`, the same voice with variations in pace, pitch, pauses and emphasis.
- Negatives: 9 near misses ("Tango Mango", "Manga Tanga", "Bingo Tango", "Mango tea", "Banjo Django", "Mango",
  "Tango", and two sentences containing "mango" / "tango") and 7 conversation clips.
- Each clip is placed in a 3 s recording at 2 speech levels × 3 backgrounds (36 positive and 96 negative cases).
- Tuning backgrounds: quiet, pink −50 dBFS, white −45 dBFS; speech peaks −12 and −22 dBFS.
- Held-out backgrounds: white −60, pink −55, pink −42 dBFS; speech peaks −16 and −26 dBFS. Used only to confirm decisions.
- **False activations per hour:** 10 minutes of continuous monitoring with the negative clips in random order, random gaps and levels, and pink background at −55 dBFS. Starts and stop events are counted.

**Measured, threshold 50 % (default)** — current front end and the default minimum audio level (−50 dBFS):

| Set | Samples | TP | FN | FP | TN | Precision | Recall | Specificity | FPR | FNR | F1 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Tuning | 1 | 21 | 15 | 17 | 79 | 0.553 | 0.583 | 0.823 | 0.177 | 0.417 | 0.568 |
| Tuning | 5 | 24 | 12 | 19 | 77 | 0.558 | 0.667 | 0.802 | 0.198 | 0.333 | 0.608 |
| Held-out | 1 | 18 | 18 | 8 | 88 | 0.692 | 0.500 | 0.917 | 0.083 | 0.500 | 0.581 |
| Held-out | 5 | 25 | 11 | 9 | 87 | 0.735 | 0.694 | 0.906 | 0.094 | 0.306 | 0.714 |

All false positives are near misses. Ordinary conversation produced no activation at 50 % or higher: FPR 0, and 0
activations in 10 minutes at 50 % and 70 %. "Mango Tango" has close neighbours ("Tango Mango", "Manga Tanga" differ by
a swapped word or two vowels), so near misses are frequent at the default threshold; 65–70 % rejects almost all of them.

**Threshold sweep** (tuning set; recall on target clips, FPR on near misses and on conversation):

| Threshold | Recall (1) | Near-miss FPR (1) | Conversation FPR (1) | Recall (5) | Near-miss FPR (5) | Conversation FPR (5) |
|---|---|---|---|---|---|---|
| 20 % | 0.944 | 0.815 | 0.095 | 1.000 | 0.833 | 0.310 |
| 25 % | 0.889 | 0.741 | 0 | 0.944 | 0.741 | 0.024 |
| 30 % | 0.833 | 0.630 | 0 | 0.861 | 0.648 | 0 |
| 35 % | 0.833 | 0.574 | 0 | 0.833 | 0.593 | 0 |
| 40 % | 0.806 | 0.500 | 0 | 0.833 | 0.500 | 0 |
| 45 % | 0.778 | 0.389 | 0 | 0.833 | 0.426 | 0 |
| **50 %** | 0.556 | 0.278 | 0 | 0.667 | 0.370 | 0 |
| 55 % | 0.500 | 0.241 | 0 | 0.528 | 0.241 | 0 |
| 60 % | 0.444 | 0.185 | 0 | 0.528 | 0.185 | 0 |
| 65 % | 0.361 | 0.074 | 0 | 0.444 | 0.074 | 0 |
| 70 % | 0.250 | 0.019 | 0 | 0.306 | 0.019 | 0 |
| 75 % | 0.222 | 0 | 0 | 0.250 | 0 | 0 |
| 80 % | 0.111 | 0 | 0 | 0.111 | 0 | 0 |

Below 50 % similar-sounding phrases trigger much more often, and at 20–30 % ordinary conversation starts activating the
detector too. Low values are offered for voices or microphones where the phrase is otherwise not detected; Settings
shows a warning below 50 %. Scores are not rescaled: a lower threshold simply accepts weaker matches.

**False activations per hour of continuous monitoring** (10 min of negative-only audio; worst case, not an everyday
rate):

| Threshold | Conversation, 1 sample | Conversation, 5 samples | Near misses, 1 sample | Near misses, 5 samples |
|---|---|---|---|---|
| 30 % | 0 | 66/h | 1224/h | 1386/h |
| 40 % | 0 | 0 | 1026/h | 1062/h |
| 50 % | 0 | 0 | 600/h | 690/h |
| 70 % | 0 | 0 | 0 | 0 |

**Known regression (measured):** several references raise false activations on similar-sounding phrases (tuning set:
19 vs 17 of 96 with 5 vs 1 sample; near misses at 50 %: 690/h vs 600/h). In exchange, recall improves: for example,
a slow "Mango, Tango" with a pause scored 0.627 with one sample and 0.755 with five.

**Recommendation:**
- The default stays 50 %, and users' configured thresholds are never changed.
- Users who get false starts from similar words should raise the threshold to 65–70 %.

**Processing time** (JVM on the build host, not a phone): 0.036 ms per 50 ms frame with 1 sample, 0.070 ms with 5.
Not measured on a device.

## 11. Experiments that were not adopted (earlier set)

Each experiment changed one variable at a time and was decided on the tuning set, then checked on the held-out set.

| Hypothesis | Change | Result | Decision |
|---|---|---|---|
| Speech-presence dimension separates speech from noise | 17th feature = SNR / 30 dB, weights 0.25–1.0 | Recall in noise fell (24 → 9 true positives at weight 1.0) | Rejected; parameter kept at 0 |
| Penalizing the worst local DTW segment rejects near misses | `localMismatchWeight` 0.25 / 0.5 | AUC with 5 samples: tuning 0.817 → 0.889, held-out 0.807 → 0.841. Held-out recall at FPR ≤ 2 % rose only from 0.306 to 0.333. | Not adopted: the gain is small, and it shifts every score downward, which would change what users' thresholds mean. Kept off (0). |
| Requiring two consecutive matching frames | `consecutiveMatchFrames = 2` | Marginal change | Not adopted (1) |
| Average of the two best references | `MEAN_OF_TOP_TWO` | AUC 0.807 vs 0.817 for max | Max kept |
| Speech raises the noise floor and over-subtracts a following stop phrase | Noise-floor fall rate 0.5 / speech rise limit 0.5 or 0 dB/s / noise-like rise frozen | Stop after a 0.2 s pause: 17–19/36 (unchanged); freezing the floor also lowered recall (tuning 0.667 → 0.528) | Rejected (the cause was the suppression gain, section 13) |
| No temporal smoothing of band powers | `temporalSmoothing` 0.3 → 0 (on top of release 0.5) | Tuning F1 0.617 → 0.675, held-out FPR 0.202 → 0.119, but held-out recall 0.694 → 0.639 and stop/start symmetry slightly worse | Not adopted (mixed on held-out data) |
| Level relative to the noise floor instead of a fixed minimum level | phrase level ≥ noise floor + 10/15/20/25 dB | ≥ 15 dB behaves like −50 dBFS; ≥ 20 dB loses normal phrases in loud noise (25 → 20 of 36) | Rejected; fixed dBFS level (section 14) |
| Neural keyword spotter | sherpa-onnx KWS (English GigaSpeech, 3.3M) | 0 of 72 target clips detected | Rejected, see `docs/TRANSCRIPTION.md` |

## 12. Limitations of the evaluation

- All numbers come from synthetic speech of one TTS engine. They show relative effects between settings, not real-world accuracy.
- Other voices are recognised less reliably with samples of one voice, because the matcher is speaker-dependent; the built-in reference covers several voices but not all (section 15).
- The near-miss set is small (9 phrases), so FPR values change in steps of about 0.019.
- Device microphones, audio-HAL processing, reverberation and real background noise were not tested.

## 13. Start/stop consistency ("Start memo & stop if repeated")

**Symptom:** the phrase stopped a memo less reliably than it started one. **Measurement** (`WakePhraseStartStopTest`):
the same 36 target recordings (6 clips × 3 backgrounds × 2 levels), said after silence (start) or after memo speech
and a pause (stop), through the monitoring state machine with identical settings (1 sample, 50 %).

| Pause before the phrase | Start (earlier set) | Stop before the fix (earlier set) | Stop after the fix (earlier set) | Start / stop now (current set) | Stop with the old gain release (current set) |
|---|---|---|---|---|---|
| 0.2 s | 25/36 | 17/36 | 22/36 | 23 / 18 of 36 | 19/36 |
| 0.5 s | 25/36 | 21/36 | 25/36 | 23 / 21 of 36 | 20/36 |
| 1.0 s | 25/36 | 23/36 | 25/36 | 23 / 21 of 36 | 19/36 |

On the current "Mango Tango" set the fix no longer makes a difference at a 0.2 s pause (18 vs 19 of 36) but still helps
after 0.5–1 s, and stopping stays within 2 of 36 trials of starting; `WakePhraseStartStopTest` guards these values.

**Not the cause** (checked): model, threshold, confidence scale, window lengths, debouncing (1.5 s after the start
phrase) and the matcher input are the same for both paths; replacing the memo speech by silence made stop equal to
start (24/36 at all pauses), so preceding speech was the trigger; freezing the noise-floor estimate did not remove the
difference (section 11). **Cause:** the noise-suppression gain per band opens quickly for speech and closed slowly
(30 % per frame). After memo speech the gains were still open during a short pause, so the pause noise and the start of
the stop phrase were processed differently from the same phrase after silence. **Fix:** release 0.5 per frame
(`WakePhraseFrontEnd.GAIN_RELEASE`). Enrolled samples created before the change keep working (measured with old
templates and the new front end: recall equal or higher on both sets).

**Level condition:** the old check used the loudest frame of the last 2.5 s, which memo speech always satisfied when
stopping but not silence when starting. It now uses only the matched phrase (`MemoTriggerDetector.phraseLevelDb`), for
both paths; with a strict level (−45 dBFS) and a quiet phrase, start and stop were rejected equally (current set: start
5 → 0 and stop 3 → 0 of 18).

**Latency** (processing only, detector frames, current set): the decision comes on average 290 ms before the measured
end of the phrase when starting and 90–330 ms before when stopping (the matcher fires once enough of the phrase matches).
Microphone buffering on a phone adds to this; not measured.

**Not changed:** a single utterance cannot start and stop a memo (stop phrases are ignored for 1.5 s after the start);
the audio before the stop phrase is kept and only the phrase is removed (section 8).

## 14. Live level and the optional minimum audio level

**Live level (fixed):** the dashboard level was only updated by `ChunkRecorded` events, which exist only while a memo is
recorded, so it froze during monitoring. It is now computed by `LevelMeter` from every captured frame of the monitoring
stream (before chime muting): RMS of the 50 ms frame in dBFS, instant rise, 30 dB/s fall, published 10 times per second;
no extra microphone session. Without live input (monitoring stopped, microphone unavailable or handed to Settings, stream
restart) the value is cleared, so no stale level is shown.

**dBFS, not dB SPL:** levels are digital (0 dBFS = full scale). The same voice reads differently on different phones,
microphones, gain and Android's automatic gain control/noise suppression; the old "dB" scale (dBFS + 90) was relabelled.

**Minimum audio level for the wake phrase** (Settings; optional): an additional condition to the match confidence
threshold, applied to start and stop alike, measured on the matched phrase. It never removes audio and never stops frames
from reaching the matcher. Sweep (earlier set; 1 sample, 50 %, 3 backgrounds; quiet phrase −30/−36 dBFS peak, distant
conversation −36/−42 dBFS peak):

| Minimum level | Normal phrase | Quiet phrase | Normal-level negatives | Distant negatives |
|---|---|---|---|---|
| off | 25/36 | 10/36 | 22/84 | 4/84 |
| −60 / −55 dBFS | 25/36 | 10/36 | 22/84 | 4/84 |
| **−50 dBFS (default)** | 25/36 | 10/36 | 22/84 | 3/84 |
| −45 dBFS | 25/36 | 5/36 | 22/84 | 0/84 |
| −42 dBFS (previous fixed check) | 25/36 | 4/36 | 22/84 | 0/84 |
| −40 dBFS | 25/36 | 2/36 | 22/84 | 0/84 |
| −35 dBFS | 25/36 | 0/36 | 22/84 | 0/84 |

−50 dBFS loses no quiet phrases here and removes some distant speech; stricter values reject distant speech completely but
also quiet or distant users. Existing installations keep their previous level (the old activation threshold, default
−42 dBFS); new installations start at −50 dBFS. The right value depends on the phone; compare with the live level.

## 15. Default wake phrase: "Mango Tango"

**"Mango Tango"** is the only built-in wake phrase (`BuiltInWakePhrase`). It is used while no sample is enrolled; with
samples, the user's own recording is matched instead (any phrase). Start and stop always use the same phrase. A change
of samples while a memo is being recorded is applied when the memo ends.

**Why.** The reference of the earlier built-in phrase was a synthesized formant sequence, not speech: measured on the
earlier fixtures it scored 0.00 on every spoken rendition, so without samples the wake phrase effectively did not
work. "Mango Tango" has two clear, stressed syllable pairs with nasal and stop consonants and is rare in conversation.

**Upgrades** (`SettingsMigrator` v4): installations from before v4 show a one-time notice on the dashboard and in
Settings that the built-in phrase is now "Mango Tango" (with the hint that recorded samples keep working). Recorded
samples are never changed, converted or deleted; they keep taking precedence. Earlier settings of a selectable built-in
phrase and of the removed speech-checked detection are dropped by the migration.

**Built-in reference.** `app/src/main/resources/wakephrase/mango_tango.bin` holds the front-end features (no audio)
of five eSpeak NG renditions (`mango_ref_1..5`: US and British English, a female and a male variant, German), extracted
like a Settings enrollment; it is matched with `ReferenceAggregation.MAX`. `BuiltInWakePhraseTest` rebuilds it from the
fixtures and fails if it differs (provenance: `app/src/test/resources/audio/README.md`).

**Measured** (`MangoTangoWakePhraseTest`, synthetic speech; 3 backgrounds × 2 levels; minimum level −50 dBFS):

| Setup @ 50 % | Wake phrase detected | Near misses | Conversation |
|---|---|---|---|
| Built-in reference, 10 held-out voices/accents | 16/60 | 4/54 | 0/42 |
| Built-in reference, the target speaker | 13/36 | | |
| Built-in reference @ 40 % (held-out voices) | 30/60 | 11/54 | 0/42 |
| Built-in reference @ 60 % (held-out voices) | 5/60 | 0/54 | 0/42 |
| 1 recorded sample (own voice) | 22/36 | 17/54 | 0/42 |
| 3 recorded samples (own voice) | 25/36 | 18/54 | 0/42 |
| Custom phrase "Open sesame", 3 samples | 20/24 "Open sesame"; 0/96 "Mango Tango" | — | 0/42 |

Start and stop with the same phrase (memo speech in between, 1 s pause, 3 backgrounds): 6/18 memos were started and
ended by the phrase with the built-in reference, 10/18 with 3 recorded samples; every stop kept the whole memo speech and
removed only the stop phrase and the pause after it.

**Interpretation.** Without samples the built-in phrase recognises some voices but far from all; recording the phrase
(or another phrase) in one's own voice is the recommended setup, and the UI says so. Recorded samples raise recall but
also accept more near misses at 50 %; 65–70 % avoids them. Real voices differ from eSpeak NG; these numbers show relative
behaviour, not real-world accuracy, and device tests with real speakers are still required.

## 16. Detector choice

Template matching is the only wake-word detector: it is small (no model file), runs in about 0.04–0.07 ms per 50 ms
frame on the build host, works offline for any phrase the user records, and keeps the microphone pipeline independent
of transcription. An experimental second detector that confirmed utterances with the Whisper speech model was removed
again: it needed a 55 MB+ model and about 250 MB of memory while monitoring, decided only after the utterance, and on the
fixtures accepted more near misses than template matching. Installations that had selected it continue with template
matching and their samples (settings migration v4).

Engines considered and not adopted:

| Engine | License | Why not |
|---|---|---|
| sherpa-onnx keyword spotting (open vocabulary) | Apache-2.0 (models Apache-2.0) | Needs ONNX Runtime native libraries for every ABI; F-Droid would have to build ONNX Runtime from source. In an earlier evaluation with the English model it detected 0 of 72 target clips |
| Vosk / Kaldi grammar recognition | Apache-2.0 | Second large native engine and model (≥ 40 MB) |
| openWakeWord | code Apache-2.0, pre-trained models CC BY-NC-SA 4.0 | Non-commercial model license is not FOSS-compatible; custom phrases need training |
| Porcupine (Picovoice) | proprietary, needs an access key | Not FOSS |
| microWakeWord / Mycroft Precise | Apache-2.0 | Need a model trained per phrase (TensorFlow); no way to support recorded custom phrases on the device |
