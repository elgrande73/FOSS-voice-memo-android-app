# Wake-phrase detection: pipeline, noise suppression and validation

This document describes how Voice Memo recognises its wake phrase (default **"Ciao Linda"**), why automatic
monitoring used to report much lower match confidence than the Settings phrase test, what was changed, and how the
changes were measured.

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
* **Templates from earlier versions** (`'CIAO'` format, legacy features) keep working and are matched with the legacy
  feature extractor; Settings recommends re-recording. New templates use the `'CIA2'` format.

### Score semantics

For each candidate window the DTW path cost between template and recent frames is divided by the path length (mean
Euclidean distance between L2-normalised feature vectors, 0…√2) and mapped to
`score = clamp(1 − distance / 0.40, 0, 1)`. This is a **heuristic similarity score, not a calibrated probability**; the
UI shows it as "match" percentage. The user's *Match confidence threshold* (50–80 %) is compared with it directly
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

## 4. Results after the change (same 96 cases as above)

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
boundaries, sub-frame offsets, thresholds 50/65/80 %, loudness criterion, restarts, legacy templates, endpointing,
performance), `WakePhraseNoiseRobustnessTest`, `WakePhraseFrontEndTest`, `NoiseFloorEstimatorTest`,
`PcmFrameAssemblerTest`, `WakePhraseStoreTest` (format validation), `WakePhraseSamplesTest` (multi-sample enrollment),
`WakePhraseEvaluationTest` (metrics, sweep, false activations, processing time), `StopBehaviorTest` (stop reasons). Fixtures: `app/src/test/resources/audio`
(synthetic speech, see its README).

## 7. Limitations and required device tests

* Fixtures are synthetic speech; numbers show relative effects, not real-world accuracy. The matcher is a
  speaker-dependent template matcher: a different voice ("ciao_linda_c") does not match reliably, by design.
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

Measured on the fixtures (`StopBehaviorTest`):
- Combinations: 3 pause lengths, 2 phrases and 3 sub-frame offsets.
- Every memo word was kept.
- At most 50 ms of the stop phrase remained.
- The cut fell 10–286 ms before the stop phrase started.

**Saving:**
- Completed memos are written atomically by `WavWriter.writeWavFileAtomic`: a temporary file, length and header check, then rename. A failed write is retried once.
- A failed write never replaces an existing file.
- Saving runs on `Dispatchers.IO`, never on the main thread.

## 9. Multi-sample enrollment (optional, 1–5 samples)

Enrolled samples are optional. Without any, the built-in "Ciao Linda" reference is used. Settings shows "n of 5 samples" and lets the user add, replace or delete individual samples. A sixth sample is refused with a message.

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
- Templates in older feature formats are still loaded and are flagged as outdated.

**Choice of aggregation** (measured, 5 samples, tuning set). Mean of the best two references: AUC 0.807. Max: AUC 0.817. Max was chosen.

## 10. Evaluation procedure and results

`WakePhraseBenchmark` and `WakePhraseEvaluationTest`. Run with:

```bash
./gradlew testDebugUnitTest --tests '*WakePhraseEvaluationTest*' -i | grep MEASURE
```

**Data:** synthetic eSpeak NG voices; see `app/src/test/resources/audio/README.md`.
- Enrollment clips (`target_enroll_1..5`) are recorded in a quiet room and are never evaluated.
- Positives: `target_eval_1..6`, the same voice with variations in pace, pitch, pauses and emphasis.
- Negatives: 7 near misses ("Ciao Lina", "Cara Linda", "Ciao", "Linda", …) and 7 conversation clips.
- Each clip is placed in a 3 s recording at 2 speech levels × 3 backgrounds (36 positive and 84 negative cases).
- Tuning backgrounds: quiet, pink −50 dBFS, white −45 dBFS; speech peaks −12 and −22 dBFS.
- Held-out backgrounds: white −60, pink −55, pink −42 dBFS; speech peaks −16 and −26 dBFS. Used only to confirm decisions.
- **False activations per hour:** 10 minutes of continuous monitoring with the negative clips in random order, random gaps and levels, and pink background at −55 dBFS. Starts and stop events are counted.

**Measured, threshold 50 % (default):**

| Set | Samples | TP | FN | FP | TN | Precision | Recall | Specificity | FPR | FNR | F1 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Tuning (baseline) | 1 | 24 | 12 | 21 | 63 | 0.533 | 0.667 | 0.750 | 0.250 | 0.333 | 0.593 |
| Tuning | 5 | 28 | 8 | 21 | 63 | 0.571 | 0.778 | 0.750 | 0.250 | 0.222 | 0.659 |
| Held-out | 1 | 23 | 13 | 17 | 67 | 0.575 | 0.639 | 0.798 | 0.202 | 0.361 | 0.605 |
| Held-out | 5 | 29 | 7 | 18 | 66 | 0.617 | 0.806 | 0.786 | 0.214 | 0.194 | 0.699 |

All false positives are near misses. Ordinary conversation produced no activation in any configuration: FPR 0, and 0 activations in 10 minutes at 50 % and 70 %.

**Threshold sweep** (tuning set). Recall is on target clips; FPR is on near misses; conversation FPR is 0 throughout:

| Threshold | Recall (1 sample) | Near-miss FPR (1 sample) | Recall (5 samples) | Near-miss FPR (5 samples) |
|---|---|---|---|---|
| 50 % | 0.639 | 0.500 | 0.778 | 0.500 |
| 55 % | 0.472 | 0.262 | 0.583 | 0.310 |
| 60 % | 0.417 | 0.143 | 0.500 | 0.190 |
| 65 % | 0.417 | 0.119 | 0.500 | 0.119 |
| 70 % | 0.306 | 0.048 | 0.472 | 0.048 |
| 75 % | 0.167 | 0 | 0.306 | 0 |
| 80 % | 0 | 0 | 0 | 0 |

**Near-miss false activations per hour of continuous monitoring:**

| Threshold | 1 sample | 5 samples |
|---|---|---|
| 50 % | 942 | 1002 |
| 70 % | 12 | 48 |

These come from a stream made only of near misses, so they are a worst case, not an everyday rate.

**Known regression.** Matching against several references raises false activations on similar-sounding phrases:
- One more false positive on the held-out set (18 vs 17 of 84).
- A higher near-miss rate in continuous monitoring (48 vs 12 per hour at 70 %).

In exchange, recall improves at every threshold. For example, "Ciao, Linda" with a pause scored 0.42 with one sample and 0.80 with five.

**Recommendation:**
- The default stays 50 %, and users' configured thresholds are never changed.
- Users who get false starts from similar names should raise the threshold to 65–70 %.

**Processing time** (JVM on the build host, not a phone): 0.032 ms per 50 ms frame with 1 sample, 0.063 ms with 5. Cost grows roughly linearly with the number of samples. Phone CPUs are slower, but this is about 1000× below real time on the host. Not measured on a device.

## 11. Experiments that were not adopted

Each experiment changed one variable at a time and was decided on the tuning set, then checked on the held-out set.

| Hypothesis | Change | Result | Decision |
|---|---|---|---|
| Speech-presence dimension separates speech from noise | 17th feature = SNR / 30 dB, weights 0.25–1.0 | Recall in noise fell (24 → 9 true positives at weight 1.0) | Rejected; parameter kept at 0 |
| Penalizing the worst local DTW segment rejects near misses | `localMismatchWeight` 0.25 / 0.5 | AUC with 5 samples: tuning 0.817 → 0.889, held-out 0.807 → 0.841. Held-out recall at FPR ≤ 2 % rose only from 0.306 to 0.333. | Not adopted: the gain is small, and it shifts every score downward, which would change what users' thresholds mean. Kept off (0). |
| Requiring two consecutive matching frames | `consecutiveMatchFrames = 2` | Marginal change | Not adopted (1) |
| Average of the two best references | `MEAN_OF_TOP_TWO` | AUC 0.807 vs 0.817 for max | Max kept |

## 12. Limitations of the evaluation

- All numbers come from synthetic speech of one TTS engine. They show relative effects between settings, not real-world accuracy.
- Other voices are recognised poorly regardless of sample count (recall 0.333 with 1 and with 5 samples), because the matcher is speaker-dependent.
- The near-miss set is small (7 phrases), so FPR values change in steps of about 0.024.
- Device microphones, audio-HAL processing, reverberation and real background noise were not tested.
