#!/usr/bin/env bash
# Render every presentation board to design/logo/previews/<id>_board.png with headless Chrome.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
for html in "$here"/previews/*_board.html; do
  png="${html%.html}.png"
  h=1000; case "$html" in *overview*) h=720;; esac
  google-chrome --headless=new --disable-gpu --hide-scrollbars --allow-file-access-from-files \
    --force-device-scale-factor=1 --screenshot="$png" --window-size=1600,$h "file://$html" >/dev/null 2>&1
  echo "$png"
done
