#!/usr/bin/env bash
# tools/voice-hashes.sh — SHA-256 فایل صدا را از انتشار رسمی sherpa-onnx می‌گیرد و خطوط آمادهٔ چسباندن در VOICE_SHA256 (index.html) چاپ می‌کند.
# اجرا (نیازمند اینترنت و curl، روی رایانه): bash voice-hashes.sh <شناسهٔ صدا> [<شناسهٔ صدا> ...]
# نکته: فقط صداهایی که برنامه از همین نشانی (VOICE_BASE در PiperNativePlugin.kt) دانلود می‌کند؛ صداهای ساخت‌خودتان را جدا بسنجید.
set -o pipefail
BASE="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"
[ $# -eq 0 ] && { echo "usage: $0 <voice-id> [<voice-id> ...]" >&2; exit 1; }
for id in "$@"; do
  if h=$(curl -fsSL "$BASE/vits-piper-$id.tar.bz2" | sha256sum | cut -d' ' -f1) && [ ${#h} -eq 64 ]; then
    printf "  '%s': '%s',\n" "$id" "$h"
  else
    echo "FAILED $id" >&2
  fi
done
