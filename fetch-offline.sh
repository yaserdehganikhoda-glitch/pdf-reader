#!/usr/bin/env bash
# One-time (needs internet). Usage: bash fetch-offline.sh <folder-next-to-index.html>
# Creates ocr/ (Tesseract engine + fas/eng data) and lib/ (pdf.js) for fully offline use.
set -euo pipefail
D="$(cd "${1:-.}" && pwd)"
mkdir -p "$D/ocr/lang" "$D/lib"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
cd "$T"
npm init -y >/dev/null
npm i --no-audit --no-fund tesseract.js@5.1.1 tesseract.js-core@5.1.1 pdfjs-dist@3.11.174 >/dev/null
N="$T/node_modules"
cp "$N/tesseract.js/dist/tesseract.min.js" "$N/tesseract.js/dist/worker.min.js" "$D/ocr/"
cp "$N"/tesseract.js-core/tesseract-core*lstm.wasm.js "$D/ocr/"
cp "$N/pdfjs-dist/build/pdf.min.js" "$N/pdfjs-dist/build/pdf.worker.min.js" "$D/lib/"
for L in fas eng; do
  curl -fsSL "https://github.com/tesseract-ocr/tessdata_best/raw/main/$L.traineddata" | gzip -9 > "$D/ocr/lang/$L.traineddata.gz"
  [ "$(stat -c %s "$D/ocr/lang/$L.traineddata.gz")" -gt 100000 ] || { echo "download failed: $L" >&2; exit 1; }
done
echo "OK -> $D/ocr , $D/lib"
