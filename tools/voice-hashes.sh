#!/usr/bin/env bash
# tools/voice-hashes.sh — راه جایگزین دستی (روی رایانه) برای ساخت native/voice-sums.txt؛ راه اصلی workflow «Voice sums» است.
# اجرا (نیازمند اینترنت و curl): bash voice-hashes.sh fa_IR-gyro-medium fa_IR-ganji-medium fa_IR-ganji_adabi-medium >> native/voice-sums.txt
# خروجی با قالب checksums.txt: «<sha256>  <شناسهٔ صدا>». فقط صداهایی که برنامه از VOICE_BASE (انتشار tts-models در sherpa-onnx) دانلود می‌کند.
set -o pipefail
BASE="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"
[ $# -eq 0 ] && { echo "usage: $0 <voice-id> [<voice-id> ...]" >&2; exit 1; }
for id in "$@"; do
  if h=$(curl -fsSL "$BASE/vits-piper-$id.tar.bz2" | sha256sum | cut -d' ' -f1) && [ ${#h} -eq 64 ]; then
    printf "%s  %s\n" "$h" "$id"
  else
    echo "FAILED $id" >&2
  fi
done
