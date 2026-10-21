# Wake-phrase detection: pipeline, noise suppression and validation

This document describes how Voice Memo recognises its wake phrase (default **"Mango Tango"** for new installations,
see section 15; earlier versions used **"Ciao Linda"**, which existing installations keep), why automatic
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
`WakePhraseEvaluationTest` (metrics, sweep, false activations, processing time), `StopBehaviorTest` (stop reasons),
`WakePhraseStartStopTest` (start vs. stop on the same clips, level condition, latency), `LevelMeterTest` (live level).
Fixtures: `app/src/test/resources/audio`
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

**Measured, threshold 50 % (default)** — current front end (suppression-gain release 0.5, section 13) and the
default minimum audio level (−50 dBFS); values before that change in parentheses:

| Set | Samples | TP | FN | FP | TN | Precision | Recall | Specificity | FPR | FNR | F1 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Tuning | 1 | 25 (24) | 11 | 20 (21) | 64 | 0.556 | 0.694 (0.667) | 0.762 | 0.238 (0.250) | 0.306 | 0.617 (0.593) |
| Tuning | 5 | 29 (28) | 7 | 20 (21) | 64 | 0.592 | 0.806 (0.778) | 0.762 | 0.238 (0.250) | 0.194 | 0.682 (0.659) |
| Held-out | 1 | 25 (23) | 11 | 17 (17) | 67 | 0.595 | 0.694 (0.639) | 0.798 | 0.202 (0.202) | 0.306 | 0.641 (0.605) |
| Held-out | 5 | 29 (29) | 7 | 17 (18) | 67 | 0.630 | 0.806 (0.806) | 0.798 | 0.202 (0.214) | 0.194 | 0.707 (0.699) |

All false positives are near misses. Ordinary conversation produced no activation at 50 % or higher: FPR 0, and 0
activations in 10 minutes at 50 % and 70 %.

**Threshold sweep** (tuning set; recall on target clips, FPR on near misses and on conversation):

| Threshold | Recall (1) | Near-miss FPR (1) | Conversation FPR (1) | Recall (5) | Near-miss FPR (5) | Conversation FPR (5) |
|---|---|---|---|---|---|---|
| 20 % | 0.972 | 0.929 | 0.786 | 0.972 | 0.952 | 0.810 |
| 25 % | 0.972 | 0.881 | 0.714 | 0.972 | 0.905 | 0.738 |
| 30 % | 0.833 | 0.786 | 0.381 | 0.944 | 0.857 | 0.452 |
| 35 % | 0.806 | 0.690 | 0.214 | 0.833 | 0.810 | 0.262 |
| 40 % | 0.750 | 0.619 | 0.119 | 0.833 | 0.619 | 0.119 |
| 45 % | 0.722 | 0.619 | 0.024 | 0.833 | 0.619 | 0.024 |
| **50 %** | 0.694 | 0.524 | 0 | 0.806 | 0.524 | 0 |
| 55 % | 0.639 | 0.262 | 0 | 0.750 | 0.286 | 0 |
| 60 % | 0.444 | 0.167 | 0 | 0.528 | 0.190 | 0 |
| 65 % | 0.417 | 0.119 | 0 | 0.472 | 0.119 | 0 |
| 70 % | 0.333 | 0.071 | 0 | 0.472 | 0.071 | 0 |
| 75 % | 0.139 | 0 | 0 | 0.250 | 0 | 0 |
| 80 % | 0 | 0 | 0 | 0 | 0 | 0 |

Below 50 %, ordinary conversation starts activating the detector. Low values are offered for voices or microphones
where the phrase is otherwise not detected; Settings shows a warning below 50 %. Scores are not rescaled: a lower
threshold simply accepts weaker matches.

**False activations per hour of continuous monitoring** (10 min of negative-only audio; worst case, not an everyday
rate):

| Threshold | Conversation, 1 sample | Conversation, 5 samples | Near misses, 1 sample | Near misses, 5 samples |
|---|---|---|---|---|
| 30 % | 726/h | 840/h | 1518/h | 1644/h |
| 40 % | 258/h | 336/h | 1350/h | 1416/h |
| 50 % | 0 | 0 | 984/h | 1020/h |
| 70 % | 0 | 0 | 30/h (12/h before) | 54/h (48/h before) |

**Known regressions (measured):**
- Several references raise false activations on similar-sounding phrases (held-out set before the front-end change:
  18 vs 17 of 84; near misses at 70 %: 54/h with 5 samples vs 30/h with 1).
- The suppression-gain change that fixed stopping (section 13) also raised near-miss false activations at 70 % from
  12/h to 30/h (1 sample) and the near-miss FPR at 70 % from 0.048 to 0.071, while recall rose at most thresholds
  (e.g. 55 %: 0.472 → 0.639 with 1 sample). Measured on synthetic speech only.

In exchange, recall improves. For example, "Ciao, Linda" with a pause scored 0.44 with one sample and 0.79 with five.

**Recommendation:**
- The default stays 50 %, and users' configured thresholds are never changed.
- Users who get false starts from similar names should raise the threshold to 65–70 %.

**Processing time** (JVM on the build host, not a phone): 0.026 ms per 50 ms frame with 1 sample, 0.044 ms with 5.
Not measured on a device.

## 11. Experiments that were not adopted

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
- Other voices are recognised poorly regardless of sample count (recall 0.333 with 1 and with 5 samples), because the matcher is speaker-dependent.
- The near-miss set is small (7 phrases), so FPR values change in steps of about 0.024.
- Device microphones, audio-HAL processing, reverberation and real background noise were not tested.

## 13. Start/stop consistency ("Start memo & stop if repeated")

**Symptom:** the phrase stopped a memo less reliably than it started one. **Measurement** (`WakePhraseStartStopTest`):
the same 36 target recordings (6 clips × 3 backgrounds × 2 levels), said after silence (start) or after memo speech
and a pause (stop), through the monitoring state machine with identical settings (1 sample, 50 %).

| Pause before the phrase | Start | Stop before | Stop after the fix |
|---|---|---|---|
| 0.2 s | 25/36 | 17/36 | 22/36 |
| 0.5 s | 25/36 | 21/36 | 25/36 |
| 1.0 s | 25/36 | 23/36 | 25/36 |

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
both paths; with a strict level (−45 dBFS) and a quiet phrase, start and stop were rejected equally (5 → 0 of 18 each).

**Latency** (processing only, detector frames): the decision comes on average 130 ms before the measured end of the
phrase when starting and 57–100 ms before when stopping (the matcher fires once enough of the phrase matches).
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
from reaching the matcher. Sweep (1 sample, 50 %, 3 backgrounds; quiet phrase −30/−36 dBFS peak, distant conversation
−36/−42 dBFS peak):

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

New installations use **"Mango Tango"**; installations set up before settings v3 keep **"Ciao Linda"**
(`SettingsMigrator` v3, key `builtInWakePhrase`), because their users learned that phrase. Both are offered in
Settings → Wake Phrase → *Built-in phrase*. Recorded samples always take precedence over the built-in phrase and are
never changed by this setting; start and stop always use the same phrase (a change made while a memo is being recorded
is applied when it ends).

**Why the change.** The previous built-in reference was a synthesized formant sequence, not speech. Measured on the
synthetic-speech fixtures it never matched a spoken "Ciao Linda": score 0.00 on all 9 renditions (6 target-speaker, 3
other voices), so without recorded samples the wake phrase effectively did not work. "Mango Tango" has two clear,
stressed syllable pairs with nasal and stop consonants and is rare in conversation.

**Built-in reference.** `app/src/main/resources/wakephrase/mango_tango.bin` holds the front-end features (no audio)
of five eSpeak NG renditions (`mango_ref_1..5`: US and British English, a female and a male variant, German), extracted
like a Settings enrollment; it is matched with `ReferenceAggregation.MAX`. `BuiltInWakePhraseTest` rebuilds it from the
fixtures and fails if it differs (provenance: `app/src/test/resources/audio/README.md`). "Ciao Linda" keeps its old
reference unchanged (`KEYWORD_CIAO_LINDA` and the `'CIAO'` template magic are untouched).

**Measured** (`MangoTangoWakePhraseTest`, synthetic speech only; 3 backgrounds × 2 levels; minimum level −50 dBFS):

| Setup @ 50 % | Wake phrase detected | Near misses ("Mango", "Tango", "Tango Mango", "Manga Tanga", "Bingo Tango", "Mango tea", 2 sentences) | Conversation |
|---|---|---|---|
| Built-in reference, 10 held-out voices/accents | 16/60 | 0/48 | 0/42 |
| Built-in reference @ 40 % | 30/60 | 15/48 | 0/42 |
| Built-in reference @ 60 % | 5/60 | 0/48 | 0/42 |
| 1 recorded sample (own voice) | 17/24 | 0/48 | 0/42 |
| 3 recorded samples (own voice) | 18/24 | 0/48 | 0/42 |
| Custom phrase (3 "Ciao Linda" samples) | 25/36 Ciao Linda; 0/84 Mango Tango | — | — |

Start and stop with the same recorded phrase (memo speech in between, 1 s pause): 9/12 memos started and were ended by
the phrase; the 3 misses never started (white noise at −45 dBFS); every stop kept the whole memo speech and removed
only the stop phrase and the pause after it.

**Interpretation.** Without samples the built-in phrase recognises some voices but far from all (16/60 on voices it was
not built from); recording one sample in one's own voice is still the recommended setup, and the UI says so. Real
voices differ from eSpeak NG; these numbers show relative behaviour, not real-world accuracy, and device tests with
real speakers are still required.

## 16. Detection models (Settings → Wake-Word Detection Model)

Two backends implement `KeywordDetector`; the selected one is persisted (`wakeWordModel`, absent = Standard, so every
existing installation keeps its behaviour) and used identically by monitoring and the Settings phrase test
(`WakeWordEngines.create`, `WakePhraseEvaluator.matcherFactory`).

| | **Standard (template matching)** — default | **Speech-checked (on-device speech model)** |
|---|---|---|
| How | DTW template matching (sections 2–14) | Utterances of phrase length are checked by Whisper base for the phrase's words |
| Needs | Built-in phrase or recorded samples | The phrase's words: built-in name, or typed words of the recorded phrase |
| Extra size | none | none: reuses the bundled `ggml-base-q5_0.bin` (55 MB, MIT) |
| Memory | negligible | +55 MB resident on the host after load and checks; whisper reserves up to ~230 MB of buffers (log) |
| Time per decision | ~0.3 ms per 50 ms frame | 274 ms wall / 524 ms CPU per check of 1.5 s audio (host x86-64, 2 threads); phones are slower (not measured) |
| Decision | on the phrase's last frame | after the utterance + 250 ms pause + check time |
| License | own code | whisper.cpp (MIT), model (MIT) — already in the app |

**How the speech check works** (`SpeechCheckedKeywordDetector`, `WhisperPhraseVerifier`, JNI `phraseLogProb`). Every
frame still goes through the template matcher (front end, noise floor, diagnostics unchanged). A speech segment of
0.35–2.5 s (≥ 10 dB above the noise floor, ended by ≥ 250 ms below it) is cut out with 150 ms padding and scored on a
worker thread, so the audio thread never waits; at most two checks are queued, further utterances are skipped while the
model is busy. Whisper encodes the snippet with an encoder context reduced to its length (about 10× faster than the full
30 s window) and the decoder is forced through the phrase's words (a few spellings: "Mango Tango", "Mango tango",
"mango tango", "Mango, Tango"); the mean token log-probability `lp` of the best spelling gives
`score = clamp(1 + lp / 8, 0, 1)` — a similarity score, not a probability, compared with the same *Match confidence
threshold*. The mapping puts the default 50 % at `lp = −4`, the value that separated phrase and ordinary speech on the
fixtures. Free transcription was tried first and rejected: Whisper wrote eSpeak NG's "Mango Tango" as "Mendoza",
"Manjo Tanjo", etc. A likelihood ratio against the free transcription did not separate better and was not adopted.
A match is reported on the first frame after the check finished, with a window covering the utterance and the check
time, so a stop phrase is removed together with the time the check took (the pre-trigger buffer grows by 3 s in this
mode so a start never loses audio).

**Measured** (`SpeechCheckHostTest`, real model via the host JNI build, same fixtures and conditions as section 15):

| Setup @ 50 % | Detected | Near misses | Conversation |
|---|---|---|---|
| "Mango Tango", no samples, 10 held-out voices | **27/60** (Standard: 16/60) | **9/48** (Standard: 0/48) | 0/42 |
| "Mango Tango", no samples, the voice of section 15's user | 15/24 | — | — |
| Custom phrase "Ciao Linda" (3 samples + words) | 14/54 (6 target + 3 other voices) | 0/42 | 0/42; Mango Tango 0/24 |

So the speech check recognises more voices without recorded samples, but also accepts more near misses ("Manga Tanga",
"Bingo Tango") and is weaker than Standard with the user's own samples. It is therefore **not the default**, and no
accuracy improvement is claimed beyond these synthetic measurements.

**Fallback and switching.** If the speech check cannot be used — no words entered for recorded samples, a phrase
shorter than 4 letters or longer than 4 words, the speech engine missing for the device's ABI, the model failing to load
(e.g. memory) — Standard is used and Settings shows why (`AudioMonitoringService.wakeWordStatus`). A check that fails at
run time counts as "no match" and never stops monitoring. Switching never interrupts a memo: the new backend is loaded in
the background and installed when no memo is being recorded (`MemoTriggerDetector.replaceKeywordDetector` refuses
during a memo); the previous backend is then closed, which frees the speech model (shared by monitoring and the Settings
test with a reference count, `SpeechCheckModel`). Recorded samples are never converted or deleted by switching.

**Limitations.** Phrases said inside continuous speech without a pause are not checked (the utterance is longer than
2.5 s); during long conversation the model runs repeatedly (battery); phone latency, CPU and memory were not measured
(no device available); the multilingual model is asked in the transcription language (English if "auto").

**Alternatives considered and not adopted**

| Engine | License | Why not |
|---|---|---|
| sherpa-onnx keyword spotting (open vocabulary) | Apache-2.0 (models Apache-2.0) | Needs ONNX Runtime native libraries for every ABI; F-Droid would have to build ONNX Runtime from source, a large addition. In an earlier evaluation with the English model it detected 0 of 72 "Ciao Linda" clips |
| Vosk / Kaldi grammar recognition | Apache-2.0 | Second large native engine and model (≥ 40 MB) next to whisper.cpp for a similar result |
| openWakeWord | code Apache-2.0, pre-trained models CC BY-NC-SA 4.0 | Non-commercial model license is not FOSS-compatible; custom phrases need training |
| Porcupine (Picovoice) | proprietary, needs an access key | Not FOSS |
| microWakeWord / Mycroft Precise | Apache-2.0 | Need a model trained per phrase (TensorFlow); no way to support typed custom phrases on the device |
