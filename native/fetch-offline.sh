#!/usr/bin/env bash
# One-time (needs internet). Usage: bash fetch-offline.sh <folder-next-to-index.html>
# Creates ocr/ (onnxruntime-web + PaddleOCR PP-OCRv5 Arabic/Persian models), lib/ (pdf.js + cmaps + standard fonts) and fonts/ (Vazirmatn, Noto Naskh Arabic) for fully offline use.
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
mkdir -p "$D/ocr/ort" "$D/ocr/paddle" "$D/lib/cmaps" "$D/lib/standard_fonts" "$D/fonts/files"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
cd "$T"
npm init -y >/dev/null
npm i --prefer-offline --no-audit --no-fund onnxruntime-web@1.17.3 pdfjs-dist@3.11.174 @fontsource/vazirmatn@5 @fontsource/noto-naskh-arabic@5 >/dev/null
N="$T/node_modules"
# پاک‌سازی فایل‌های قدیمی Tesseract (دیگر استفاده نمی‌شوند)
rm -rf "$D/ocr/lang" "$D/ocr/tesseract.min.js" "$D/ocr/worker.min.js" "$D"/ocr/tesseract-core*
cp "$N/onnxruntime-web/dist/ort.min.js" "$N/onnxruntime-web/dist/ort-wasm-simd.wasm" "$D/ocr/ort/"
# فایل‌های اختیاری شتاب WebGPU (اگر نباشند، OCR با WASM SIMD کار می‌کند)
for f in ort.webgpu.min.js ort-wasm-simd.jsep.wasm; do
  [ -s "$N/onnxruntime-web/dist/$f" ] && cp "$N/onnxruntime-web/dist/$f" "$D/ocr/ort/" || echo "WARN: $f not found; WebGPU disabled" >&2
done
# فایل‌های WASM چندنخی (فقط وقتی crossOriginIsolated باشد استفاده می‌شوند؛ نبودشان OCR را خراب نمی‌کند)
for f in ort-wasm-simd-threaded.wasm ort-wasm-simd-threaded.jsep.wasm; do
  [ -s "$N/onnxruntime-web/dist/$f" ] && cp "$N/onnxruntime-web/dist/$f" "$D/ocr/ort/" || echo "WARN: $f not found; multithreaded OCR disabled" >&2
done
cp "$N/pdfjs-dist/build/pdf.min.js" "$N/pdfjs-dist/build/pdf.worker.min.js" "$D/lib/"
[ -d "$N/pdfjs-dist/cmaps" ] && cp -r "$N/pdfjs-dist/cmaps/." "$D/lib/cmaps/" || echo "WARN: cmaps not found" >&2
[ -d "$N/pdfjs-dist/standard_fonts" ] && cp -r "$N/pdfjs-dist/standard_fonts/." "$D/lib/standard_fonts/" || echo "WARN: standard_fonts not found" >&2
# مدل‌های PaddleOCR (PP-OCRv5 عربی/فارسی، ONNX) با بررسی sha256
HF="https://huggingface.co/vladadu/pp-ocrv5-arabic-mobile-onnx/resolve/main"
pm() {  # pm نام‌فایل sha256
  local f; f="$(dl "$HF/$1" "$1")"
  [ "$(sha256sum "$f" | cut -d' ' -f1)" = "$2" ] || { echo "CHECKSUM MISMATCH: $1" >&2; rm -f "$f"; exit 1; }
  cp "$f" "$D/ocr/paddle/$1"
}
pm PP-OCRv5_mobile_det.onnx c8d9b07063420ce5365c74e42532de48238feeeedcdb7a330b195708bc38a93f
pm arabic_PP-OCRv5_mobile_rec.onnx 4e2f4ae42104e1b272463966c56ddafa3c6ad98ce9d8c7ed765ce66666ea13e1
pm arabic_PP-OCRv5_mobile_rec_dict.txt 7f92f7dbb9b75a4787a83bfb4f6d14a8ab515525130c9d40a9036f61cf6999e9
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
for f in ocr/ort/ort.min.js ocr/ort/ort-wasm-simd.wasm ocr/paddle/PP-OCRv5_mobile_det.onnx ocr/paddle/arabic_PP-OCRv5_mobile_rec.onnx ocr/paddle/arabic_PP-OCRv5_mobile_rec_dict.txt lib/pdf.min.js lib/pdf.worker.min.js; do
  [ -s "$D/$f" ] || { echo "missing: $f" >&2; exit 1; }
done
[ -s "$D/fonts/fonts.css" ] || echo "WARN: fonts/fonts.css missing" >&2
echo "OK -> $D/ocr , $D/lib , $D/fonts"
