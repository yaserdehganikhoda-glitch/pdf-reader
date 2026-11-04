#!/usr/bin/env bash
# r17: بعد از «Inject native Piper» اجرا شود. مدل حذف‌نویز عصبی DPDFNet (۴۸kHz) را داخل APK می‌گذارد: android/app/src/main/assets/denoise/
# دانلودِ ناموفق بیلد را نمی‌شکند (فقط هشدار)؛ برنامه در این حالت فقط فیلتر کلاسیک را اجرا می‌کند.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CACHE="${OFFLINE_CACHE:-$HOME/.offline-cache}"; mkdir -p "$CACHE"
SUMS="$HERE/checksums.txt"
dl() {   # همان dl در inject-native.sh: حافظهٔ نهان + بررسی checksum اگر در checksums.txt باشد
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
M="dpdfnet2_48khz_hr.onnx"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/$M"
DST="android/app/src/main/assets/denoise"
[ -d android/app/src/main ] || { echo "پوشهٔ android نیست (اول cap add android)"; exit 1; }
if P="$(dl "$URL" "$M")" && [ "$(stat -c %s "$P")" -gt 1000000 ]; then
  mkdir -p "$DST"; cp "$P" "$DST/$M"
  echo "denoise model ok: $(stat -c %s "$P") bytes"
else
  rm -f "$CACHE/$M"
  echo "::warning::مدل DPDFNet دانلود نشد؛ حذف‌نویز عصبی غیرفعال می‌ماند (فقط فیلتر کلاسیک)"
  exit 0
fi
# آیا API کاتلین همین نسخهٔ sherpa از DPDFNet پشتیبانی می‌کند؟
if grep -rqi "dpdfnet" android/app/src/main/java/com/k2fsa/sherpa/onnx/ 2>/dev/null; then
  echo "sherpa Kotlin API: DPDFNet پشتیبانی می‌شود"
else
  echo "::warning::Kotlin API این نسخهٔ sherpa-onnx کلاس DPDFNet ندارد؛ SHERPA_VER را بالاتر ببر وگرنه حذف‌نویز عصبی کار نمی‌کند"
fi
# و کتابخانهٔ بومی JNI هم باید نام dpdfnet را داشته باشد (وگرنه Kotlin بی‌فایده است)
J=android/app/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so
if [ -s "$J" ]; then
  if strings "$J" | grep -qi dpdfnet; then echo "libsherpa-onnx-jni.so: DPDFNet پشتیبانی می‌شود"
  else echo "::warning::libsherpa-onnx-jni.so نام dpdfnet را ندارد؛ حذف‌نویز عصبی کار نمی‌کند"; fi
fi
