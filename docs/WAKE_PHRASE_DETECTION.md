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
`PcmFrameAssemblerTest`, `WakePhraseStoreTest` (format validation). Fixtures: `app/src/test/resources/audio`
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
