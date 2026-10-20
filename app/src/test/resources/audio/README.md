# Wake-phrase test fixtures

Raw PCM, 16 kHz, mono, signed 16-bit little-endian, trimmed of leading/trailing silence and loudness-normalised.
Used only by unit tests; they are not packaged into the app.

| File | Content | Role |
|---|---|---|
| `ciao_linda_a.pcm` | "Ciao Linda" (Italian voice, speed 140) | Enrollment phrase and same-speaker test phrase |
| `ciao_linda_b.pcm` | "Ciao, Linda" (same voice, faster, lower pitch, short pause) | Same speaker, different rendition |
| `ciao_linda_c.pcm` | "Ciao Linda" (different voice variant) | Different speaker |
| `other_speech.pcm` | "Compra il latte domani" | Speech without the wake phrase |
| `other_speech_en.pcm` | "Remember the meeting" | Speech without the wake phrase |

Provenance: synthetic speech generated with [eSpeak NG](https://github.com/espeak-ng/espeak-ng) 1.51 and converted
with FFmpeg by `generate_fixtures.sh` (no recordings of real people). eSpeak NG itself is GPL-3.0-or-later; it is a
tool used to create these test inputs and is not part of the app or its build.

Synthetic speech is far cleaner and more uniform than real speech, so results on these fixtures show relative
differences between pipelines and settings, not real-world accuracy; manual device tests are still required.
