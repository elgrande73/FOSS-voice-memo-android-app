# Voice Memo launcher icon

A cheerful, slightly abstract microphone with a happy face, flanked by voice-waveform bars, with a red "recording" dot, on a violet-to-magenta gradient.

## Provenance & license

Original artwork created for the Voice Memo project (no third-party clip art, fonts or generated stock assets). It is licensed under the project license, the [Apache License 2.0](../../LICENSE).

## Files

| File | Purpose |
|---|---|
| `voice_memo_icon.svg` | Editable source (108×108 adaptive-icon canvas, `background` and `foreground` groups) |
| `render_launcher_pngs.sh` | Regenerates the legacy PNGs and the store icon from the SVG |
| `app/src/main/res/drawable/ic_launcher_background.xml` | Adaptive icon background layer (vector) |
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | Adaptive icon foreground layer (vector) |
| `app/src/main/res/drawable/ic_launcher_monochrome.xml` | Single-colour layer for Android 13+ themed icons |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher*.xml` | Adaptive icon definitions (Android 8.0+) |
| `app/src/main/res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher*.png` | Legacy icons for Android 7.x (rounded square and round) |
| `fastlane/metadata/android/en-US/images/icon.png` | 512×512 store icon |

All foreground content stays inside the 66 dp safe zone, so the icon survives circular, squircle and rounded-square masks. The vector layers are kept in sync with the SVG by hand; `LauncherIconTest` renders them and checks key colours.

Regenerate the PNGs after editing the SVG:

```bash
CHROME=/path/to/chromium ./artwork/icon/render_launcher_pngs.sh   # needs Python 3 + Pillow
```
