#!/usr/bin/env bash
# واج‌آرایی اختصاصی مانا: مدل کسرهٔ اضافه (abreza/persian-ezafe-albert، نسخهٔ quantized) → assets/mana/ezafe
set -euo pipefail
A=android/app/src/main/assets/mana/ezafe
J=android/app/src/main/java/com/pdfvoice/phon
C="$HOME/.offline-cache/mana-ezafe"
REPO=abreza/persian-ezafe-albert
BASES="https://huggingface.co https://hf-mirror.com"
mkdir -p "$A" "$C" "$J"

get() { # get <نام مقصد> <مسیرهای نامزد در مخزن...>
  local out="$C/$1"; shift
  [ -s "$out" ] && return 0
  local b p
  for p in "$@"; do for b in $BASES; do
    if curl -fsSL --retry 3 --max-time 300 -o "$out.part" "$b/$REPO/resolve/main/$p"; then mv "$out.part" "$out"; echo "fetched $p ← $b"; return 0; fi
  done; done
  rm -f "$out.part"; return 1
}

get model_quantized.onnx quantized/model_quantized.onnx onnx/model_quantized.onnx model_quantized.onnx || { echo "::error::model_quantized.onnx دریافت نشد"; exit 1; }
get tokenizer.json quantized/tokenizer.json tokenizer.json || { echo "::error::tokenizer.json دریافت نشد"; exit 1; }

[ "$(stat -c %s "$C/model_quantized.onnx")" -gt 5000000 ] || { echo "::error::model_quantized.onnx بسیار کوچک است"; exit 1; }
grep -q '"Unigram"' "$C/tokenizer.json" || { echo "::error::tokenizer.json از نوع Unigram نیست"; exit 1; }
grep -q '\[CLS\]' "$C/tokenizer.json" && grep -q '\[SEP\]' "$C/tokenizer.json" || { echo "::error::[CLS]/[SEP] در tokenizer.json نیست"; exit 1; }

cp -f "$C/model_quantized.onnx" "$C/tokenizer.json" "$A/"
sha256sum "$A/model_quantized.onnx" | cut -c1-12 > "$A/VERSION"

# ManaPhon.kt باید کنار Phonemizer.java باشد
if [ ! -s "$J/ManaPhon.kt" ]; then
  if [ -s native/ManaPhon.kt ]; then cp -f native/ManaPhon.kt "$J/ManaPhon.kt"; else echo "::error::ManaPhon.kt در native/ نیست"; exit 1; fi
fi

# ManaPhon از Java API ‏ORT استفاده می‌کند
if ! grep -rqs "onnxruntime" android/app/build.gradle; then
  echo "::warning::وابستگی onnxruntime-android در android/app/build.gradle نیست؛ ai.onnxruntime کامپایل نمی‌شود"
fi
echo "mana ezafe assets: $(ls "$A" | tr '\n' ' ')"
