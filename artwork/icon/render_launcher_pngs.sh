#!/usr/bin/env bash
# Renders the legacy (API 24-25) launcher PNGs and the 512 px store icon from voice_memo_icon.svg.
# Requires a Chromium/Chrome binary and Python 3 with Pillow (for cropping):
#   CHROME=/path/to/chrome ./artwork/icon/render_launcher_pngs.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
CHROME="${CHROME:-chromium}"
SVG="$PWD/artwork/icon/voice_memo_icon.svg"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

render() { # size, radius(css), output
  local size=$1 radius=$2 out=$3
  # Legacy icons show the 72/108 visible area of the adaptive icon with a 1/24 margin
  local inner=$((size * 22 / 24)) margin=$((size / 24))
  cat > "$WORK/icon.html" <<HTML
<html><body style="margin:0;background:transparent">
<div style="position:absolute;left:${margin}px;top:${margin}px;width:${inner}px;height:${inner}px;border-radius:${radius};overflow:hidden">
<img src="file://$SVG" style="display:block;width:$((inner * 3 / 2))px;height:$((inner * 3 / 2))px;margin:-$((inner / 4))px">
</div></body></html>
HTML
  # Headless Chrome enforces a minimum window size, so render in a large window and crop
  "$CHROME" --headless --no-sandbox --disable-gpu --hide-scrollbars --allow-file-access-from-files \
    --default-background-color=00000000 --force-device-scale-factor=1 \
    --window-size=800,800 --screenshot="$WORK/shot.png" "file://$WORK/icon.html" >/dev/null 2>&1
  python3 -c "import sys; from PIL import Image; Image.open(sys.argv[1]).crop((0, 0, int(sys.argv[2]), int(sys.argv[2]))).save(sys.argv[3], optimize=True)" \
    "$WORK/shot.png" "$size" "$out"
}

for entry in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
  density=${entry%%:*}; size=${entry##*:}
  dir="app/src/main/res/mipmap-$density"
  mkdir -p "$dir"
  render "$size" "22%" "$dir/ic_launcher.png"
  render "$size" "50%" "$dir/ic_launcher_round.png"
done

# Full-bleed square store icon (F-Droid / fastlane)
mkdir -p fastlane/metadata/android/en-US/images
cat > "$WORK/icon.html" <<HTML
<html><body style="margin:0"><img src="file://$SVG" style="display:block;width:768px;height:768px;margin:-128px"></body></html>
HTML
"$CHROME" --headless --no-sandbox --disable-gpu --hide-scrollbars --allow-file-access-from-files \
  --force-device-scale-factor=1 --window-size=800,800 \
  --screenshot="$WORK/shot.png" "file://$WORK/icon.html" >/dev/null 2>&1
python3 -c "import sys; from PIL import Image; Image.open(sys.argv[1]).convert('RGB').crop((0, 0, 512, 512)).save(sys.argv[2], optimize=True)" \
  "$WORK/shot.png" fastlane/metadata/android/en-US/images/icon.png
echo "Rendered launcher PNGs and store icon"
