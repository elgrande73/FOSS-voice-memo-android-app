# Wake-phrase test fixtures

Raw PCM, 16 kHz, mono, signed 16-bit little-endian, trimmed of leading/trailing silence and loudness-normalised.
Used only by unit tests; the audio is not packaged into the app. One exception is derived data: the spectral features
(no audio) of `mango_ref_1..5.pcm` form the built-in "Mango Tango" reference, see below.

Provenance: synthetic speech generated with [eSpeak NG](https://github.com/espeak-ng/espeak-ng) 1.51 and converted
with FFmpeg by `generate_fixtures.sh` (no recordings of real people). eSpeak NG itself is GPL-3.0-or-later; it is a
tool used to create these test inputs and is not part of the app or its build. The arguments in the script are voice,
speed (words per minute), pitch and an optional word gap (×10 ms).

Synthetic speech is far cleaner and more uniform than real speech, so results on these fixtures show relative
differences between pipelines and settings, not real-world accuracy; manual device tests are still required.

## Wake phrase of the target speaker (voice en-us+m1)

| File | Content | Role |
|---|---|---|
| `wake_phrase_a.pcm` | "Mango Tango" (speed 140) | Enrollment phrase and same-speaker test phrase |
| `wake_phrase_b.pcm` | "Mango, Tango" (faster, lower pitch, short pause) | Same speaker, different rendition |
| `wake_phrase_c.pcm` | "Mango Tango" (voice en+f2) | Different speaker |
| `other_speech.pcm` | "Compra il latte domani" | Speech without the wake phrase |
| `other_speech_en.pcm` | "Remember the meeting" | Speech without the wake phrase |

## Evaluation set (multi-sample enrollment and accuracy)

| Files | Content | Role |
|---|---|---|
| `target_enroll_1..5.pcm` | "Mango Tango" / "Mango, Tango": speed 135–165, pitch 40–60, word gaps 0–30 ms | Enrollment samples only (never evaluated) |
| `target_eval_1..6.pcm` | Same voice: speed 125–185, pitch 35–65, pauses up to 40 ms | Positives |
| `other_speaker_1..3.pcm` | "Mango Tango" in two other voice variants and an Italian voice | Other speakers |
| `neg_similar_1..5.pcm` | "Tango Mango", "Manga Tanga", "Bingo Tango", "Mango tea", "Banjo Django" | Near misses |
| `neg_partial_1..2.pcm` | "Mango", "Tango" | Partial phrase |
| `neg_context_1..2.pcm` | "I bought a mango at the market", "We danced the tango last night" (British voice) | The words inside sentences |
| `neg_speech_1..5.pcm` | Italian and English sentences without the wake phrase | Conversation |
| `custom_enroll_1..3.pcm`, `custom_eval_1..4.pcm` | "Open sesame" by the target speaker | A custom phrase recorded instead of the built-in one |

The benchmark (`WakePhraseBenchmark`) mixes these clips with generated noise at fixed seeds, so results are reproducible.

## Utterance-level corpus with a development / test split

Split by speaker: no voice of one split occurs in the other, in the built-in reference or in the enrollment samples
(`WakePhraseCorpus`, docs/WAKE_PHRASE_DETECTION.md section 17).

| Files | Content | Role |
|---|---|---|
| `dev_pos_1..12.pcm` | "Mango Tango" in 12 voices (en-us+m2, en+f1, Lancaster, New York, French, Portuguese, …) | Development positives |
| `dev_near_1..32.pcm` | 8 near-miss phrases × 4 development voices | Development near misses |
| `dev_conv_1..6.pcm` | Sentences, some with look-alike words ("mango juice", "tango") | Development conversation |
| `test_pos_1..10.pcm` | "Mango Tango" in 10 further voices (West Midlands, Polish, Romanian, …); with `mango_eval_1..10` | Test positives |
| `test_near_1..32.pcm` | The same 8 phrases × 4 test voices | Test near misses |
| `test_conv_1..6.pcm` | Sentences ("tangled cables", "mangoes", "many tanks", Spanish, German) | Test conversation |
| `mango_pool_1..20.pcm` | "Mango Tango" in 20 voices used in neither split | Candidates for the built-in reference (selection experiment, not adopted) |

## Built-in "Mango Tango" reference

| Files | Content | Role |
|---|---|---|
| `mango_ref_1..5.pcm` | "Mango Tango": en-us, en (British), en-us female variant, en male variant with word gap, de (German) | Source of the built-in reference only (never evaluated) |
| `mango_eval_1..10.pcm` | "Mango Tango" / "Mango, Tango" in 10 other voices and accents (RP, Scottish, Caribbean, Italian, Spanish, Dutch, …), speed 125–185 | Held-out positives for the built-in reference |

`app/src/main/resources/wakephrase/mango_tango.bin` contains the `WakePhraseFrontEnd` features of `mango_ref_1..5.pcm`,
extracted exactly like a Settings enrollment (quiet room, −12 dBFS peak). `BuiltInWakePhraseTest` rebuilds it from these
fixtures and fails if it differs; regenerate it with
`UPDATE_BUILT_IN_PHRASE=1 ./gradlew testDebugUnitTest --tests '*BuiltInWakePhraseTest*'` after a front-end change.
The file holds only feature vectors (16 band shapes and a speech-presence value per 50 ms frame), from which the audio
cannot be reconstructed. eSpeak NG was only used as a tool to synthesize the source audio; no eSpeak NG code or data
is part of the app.
