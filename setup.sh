#!/usr/bin/env bash
set -e
echo "== North Proxy =="
mkdir -p .github/workflows app/src/main/res/values app/src/main/res/drawable
mkdir -p app/src/main/res/mipmap-anydpi-v26
mkdir -p app/src/main/java/com/northproxy/app/data
mkdir -p app/src/main/java/com/northproxy/app/ui
mkdir -p app/src/main/java/com/northproxy/app/vpn
if [ -f north_logo.png ]; then
  cp north_logo.png app/src/main/res/drawable/north_logo.png
  echo "logo importado"
else
  echo "generando placeholder"
  if command -v magick >/dev/null 2>&1; then M=magick; else M=convert; fi
  $M -size 512x512 xc:'#05060A' -fill '#FF2233' -gravity center \
    -font DejaVu-Sans-Bold -pointsize 300 -annotate 0 'N' \
    app/src/main/res/drawable/north_logo.png
fi
echo "listo"
