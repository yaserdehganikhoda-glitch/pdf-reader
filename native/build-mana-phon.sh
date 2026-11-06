#!/usr/bin/env bash
# واج‌آرایی اختصاصی مانا: ManaPhon.kt + مدل کسرهٔ اضافه (abreza/persian-ezafe-albert، نسخهٔ quantized) → assets/mana/ezafe
# نبودِ مدل بیلد را نمی‌شکند (هشدار؛ برنامه بدون کسرهٔ اضافهٔ مدلی کار می‌کند)، ولی ManaPhon.kt همیشه باید در پروژه باشد (PiperNativePlugin آن را import می‌کند).
set -uo pipefail
A=android/app/src/main/assets/mana/ezafe
J=android/app/src/main/java/com/pdfvoice/phon
C="$HOME/.offline-cache/mana-ezafe"
REPO=abreza/persian-ezafe-albert
BASES="https://huggingface.co https://hf-mirror.com"
mkdir -p "$A" "$C" "$J"

if [ ! -s "$J/ManaPhon.kt" ]; then
  if [ -s native/ManaPhon.kt ]; then cp -f native/ManaPhon.kt "$J/ManaPhon.kt"; else echo "::error::native/ManaPhon.kt در مخزن نیست"; exit 1; fi
fi
grep -q "onnxruntime-android" android/app/build.gradle || { echo "::error::وابستگی onnxruntime-android در android/app/build.gradle نیست؛ ManaPhon کامپایل نمی‌شود"; exit 1; }

get() { # get <نام مقصد> <مسیرهای نامزد در مخزن...>
  local out="$C/$1"; shift
  [ -s "$out" ] && return 0
  local b p
  for p in "$@"; do for b in $BASES; do
    if curl -fsSL --retry 3 --max-time 300 -o "$out.part" "$b/$REPO/resolve/main/$p"; then mv "$out.part" "$out"; echo "fetched $p ← $b"; return 0; fi
  done; done
  rm -f "$out.part"; return 1
}
skip() { echo "::warning::مدل کسرهٔ اضافهٔ مانا گذاشته نشد ($1)؛ مانا بدون کسرهٔ اضافهٔ مدلی کار می‌کند"; rm -rf "$A"; exit 0; }

get model_quantized.onnx quantized/model_quantized.onnx onnx/model_quantized.onnx model_quantized.onnx || skip "model_quantized.onnx دریافت نشد"
get tokenizer.json quantized/tokenizer.json tokenizer.json || skip "tokenizer.json دریافت نشد"
[ "$(stat -c %s "$C/model_quantized.onnx")" -gt 5000000 ] || { rm -f "$C/model_quantized.onnx"; skip "model_quantized.onnx بسیار کوچک است"; }
grep -q '"Unigram"' "$C/tokenizer.json" || { rm -f "$C/tokenizer.json"; skip "tokenizer.json از نوع Unigram نیست"; }
grep -q '\[CLS\]' "$C/tokenizer.json" && grep -q '\[SEP\]' "$C/tokenizer.json" || { rm -f "$C/tokenizer.json"; skip "[CLS]/[SEP] در tokenizer.json نیست"; }

cp -f "$C/model_quantized.onnx" "$C/tokenizer.json" "$A/"
sha256sum "$A/model_quantized.onnx" | cut -c1-12 > "$A/VERSION"
echo "mana ezafe assets: $(ls "$A" | tr '\n' ' ')"
