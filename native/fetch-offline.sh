#!/usr/bin/env bash
# One-time (needs internet). Usage: bash fetch-offline.sh <folder-next-to-index.html>
# Creates ocr/ (Tesseract engine + fas/eng data), lib/ (pdf.js + cmaps + standard fonts) and fonts/ (Vazirmatn, Noto Naskh Arabic) for fully offline use.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CACHE="${OFFLINE_CACHE:-$HOME/.offline-cache}"; mkdir -p "$CACHE"
SUMS="$HERE/checksums.txt"   # خطوط: <sha256>  <نام فایل> ؛ اگر نبود، مقدار فایل چاپ می‌شود تا اضافه کنید
# dl URL NAME → مسیر فایل را چاپ می‌کند؛ از حافظهٔ نهان استفاده می‌کند و در صورت وجود checksum صحت را بررسی می‌کند
dl() {
  local url="$1" name="$2" f="$CACHE/$2" want have
  want="$(grep -E "  ${name}\$" "$SUMS" 2>/dev/null | head -1 | cut -d' ' -f1 || true)"
  if [ -s "$f" ]; then
    have="$(sha256sum "$f" | cut -d' ' -f1)"
    if [ -z "$want" ] || [ "$have" = "$want" ]; then echo "cache: $name" >&2; echo "$f"; return 0; fi
    rm -f "$f"
  fi
  curl -fL --retry 4 --retry-delay 3 -o "$f.part" "$url" >&2 || { rm -f "$f.part"; return 1; }
  mv "$f.part" "$f"
  have="$(sha256sum "$f" | cut -d' ' -f1)"
  if [ -n "$want" ] && [ "$have" != "$want" ]; then echo "CHECKSUM MISMATCH: $name" >&2; rm -f "$f"; return 1; fi
  [ -n "$want" ] || echo "$have  $name" >> "$CACHE/sums-seen.txt"
  echo "$f"
}
D="$(cd "${1:-.}" && pwd)"
mkdir -p "$D/ocr/lang" "$D/lib/cmaps" "$D/lib/standard_fonts" "$D/fonts/files"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
cd "$T"
npm init -y >/dev/null
npm i --prefer-offline --no-audit --no-fund tesseract.js@5.1.1 tesseract.js-core@5.1.1 pdfjs-dist@3.11.174 @fontsource/vazirmatn@5 @fontsource/noto-naskh-arabic@5 >/dev/null
N="$T/node_modules"
cp "$N/tesseract.js/dist/tesseract.min.js" "$N/tesseract.js/dist/worker.min.js" "$D/ocr/"
cp "$N"/tesseract.js-core/tesseract-core*lstm.wasm.js "$D/ocr/"
cp "$N/pdfjs-dist/build/pdf.min.js" "$N/pdfjs-dist/build/pdf.worker.min.js" "$D/lib/"
[ -d "$N/pdfjs-dist/cmaps" ] && cp -r "$N/pdfjs-dist/cmaps/." "$D/lib/cmaps/" || echo "WARN: cmaps not found" >&2
[ -d "$N/pdfjs-dist/standard_fonts" ] && cp -r "$N/pdfjs-dist/standard_fonts/." "$D/lib/standard_fonts/" || echo "WARN: standard_fonts not found" >&2
for L in fas eng; do
  TD="$(dl "https://github.com/tesseract-ocr/tessdata_best/raw/main/$L.traineddata" "$L.traineddata")"
  # بدون .gz ذخیره می‌شود (اندروید/WebView با فایل‌های .gz داخل assets مشکل دارد؛ در برنامه gzip:false استفاده می‌شود)
  rm -f "$D/ocr/lang/$L.traineddata.gz"
  cp "$TD" "$D/ocr/lang/$L.traineddata"
  [ "$(stat -c %s "$D/ocr/lang/$L.traineddata")" -gt 1000000 ] || { echo "download failed: $L" >&2; exit 1; }
done
python3 - "$N" "$D/fonts" <<'PY' || echo "WARN: fonts not built; system fonts will be used" >&2
import re, shutil, sys
N, O = sys.argv[1:3]
need = {'vazirmatn': [400, 500, 600, 800], 'noto-naskh-arabic': [400, 600]}
css = []
for pkg, ws in need.items():
    for w in ws:
        t = open(f"{N}/@fontsource/{pkg}/{w}.css", encoding='utf-8').read()
        t = re.sub(r",\s*url\([^)]*\.woff\)\s*format\('woff'\)", "", t)
        files = set(re.findall(r"url\(\./files/([^)]+\.woff2)\)", t))
        assert files, (pkg, w)
        for f in files:
            shutil.copy(f"{N}/@fontsource/{pkg}/files/{f}", f"{O}/files/{f}")
        css.append(t)
open(f"{O}/fonts.css", "w", encoding="utf-8").write("\n".join(css))
PY
# بررسی نهایی: موارد ضروری
for f in ocr/tesseract.min.js ocr/worker.min.js ocr/lang/fas.traineddata ocr/lang/eng.traineddata lib/pdf.min.js lib/pdf.worker.min.js; do
  [ -s "$D/$f" ] || { echo "missing: $f" >&2; exit 1; }
done
ls "$D"/ocr/tesseract-core*lstm.wasm.js >/dev/null
[ -s "$D/fonts/fonts.css" ] || echo "WARN: fonts/fonts.css missing" >&2
echo "OK -> $D/ocr , $D/lib , $D/fonts"
