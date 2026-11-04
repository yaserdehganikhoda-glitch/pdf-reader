#!/usr/bin/env bash
# ساخت آوانگار بیرونی برای اندروید: piper-phonemize (+ espeak-ng) و پل JNI.
# sherpa-onnx ≥ 2.0 دیگر espeak ندارد؛ این کتابخانه جای آن را برای فارسی می‌گیرد.
#   usage: build-phonemizer.sh <jniLibs-dir> [assets-dir]
# متغیرهای اختیاری: PP_VER (پیش‌فرض v1.5.8 = بالاترین تگ v1.x که وجودش روی csukuangfj/piper-phonemize تأیید شد؛ v1.8.5 وجود ندارد)،
#   PP_VER_STRICT=1 (اگر تگ نبود به‌جای جایگزینی خطا بده)، ANDROID_NDK، OFFLINE_CACHE
# GPL: espeak-ng و piper-phonemize GPL-3.0 هستند (sherpa-onnx 1.x هم همین‌ها را داخل خودش داشت؛ وضعیت مجوز عوض نمی‌شود).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT_LIBS="$(cd "${1:?usage: build-phonemizer.sh <jniLibs-dir> [assets-dir]}" && pwd)"
OUT_ASSETS="${2:-}"
PP_VER="${PP_VER:-v1.5.8}"
CACHE="${OFFLINE_CACHE:-$HOME/.offline-cache}"; mkdir -p "$CACHE"
SIG="$(cat "$0" "$HERE/piperphon_jni.c" | sha256sum | cut -c1-12)"
STORE="$CACHE/phonemizer-$PP_VER-$SIG"
ABIS="arm64-v8a armeabi-v7a"
declare -A SCRIPT=( [arm64-v8a]=build-android-arm64-v8a.sh [armeabi-v7a]=build-android-armv7-eabi.sh )
declare -A CLANG=(  [arm64-v8a]=aarch64-linux-android21-clang [armeabi-v7a]=armv7a-linux-androideabi21-clang )
declare -A SYSLIB=( [arm64-v8a]=aarch64-linux-android [armeabi-v7a]=arm-linux-androideabi )

install_from_store() {
  for abi in $ABIS; do
    mkdir -p "$OUT_LIBS/$abi"
    for f in "$STORE/$abi"/*.so; do
      b="$(basename "$f")"
      if [ "$b" = "libc++_shared.so" ]; then cp -n "$f" "$OUT_LIBS/$abi/$b" || true; else cp -f "$f" "$OUT_LIBS/$abi/$b"; fi
    done
  done
  if [ -n "$OUT_ASSETS" ] && [ -d "$STORE/espeak-ng-data" ]; then
    rm -rf "$OUT_ASSETS/phonemizer/espeak-ng-data"; mkdir -p "$OUT_ASSETS/phonemizer"
    cp -r "$STORE/espeak-ng-data" "$OUT_ASSETS/phonemizer/espeak-ng-data"
  fi
}

if [ -f "$STORE/.done" ]; then
  echo "phonemizer: از حافظهٔ نهان ($STORE)"
  install_from_store
  exit 0
fi

# ---- NDK ----
NDK="${ANDROID_NDK:-${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}}}"
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
  SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
  NDK="$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
fi
[ -n "$NDK" ] && [ -d "$NDK" ] || { echo "ERROR: NDK پیدا نشد (ANDROID_NDK را تنظیم کنید)"; exit 1; }
export ANDROID_NDK="$NDK"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
[ -d "$TC/bin" ] || { echo "ERROR: toolchain در $TC نیست"; exit 1; }
command -v cmake >/dev/null || { echo "ERROR: cmake نصب نیست"; exit 1; }
echo "phonemizer: NDK=$NDK ; piper-phonemize=$PP_VER"

# ---- سورس ----
SRC=/tmp/piper-phonemize-src
PP_URL=https://github.com/csukuangfj/piper-phonemize
# r182: پیش از کلون، وجود تگ را با ls-remote می‌سنجیم (قبلاً سه کلون بی‌فایده و خطای مبهم می‌داد)
PP_TAGS=""
for i in 1 2 3; do
  PP_TAGS="$(git ls-remote --tags --refs "$PP_URL" 2>/dev/null | sed -n 's#.*refs/tags/##p' || true)"
  [ -n "$PP_TAGS" ] && break
  sleep 3
done
if [ -n "$PP_TAGS" ] && ! grep -Fxq "$PP_VER" <<<"$PP_TAGS"; then
  PP_SEMVER="$(printf '%s\n' "$PP_TAGS" | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | sort -V || true)"
  echo "WARN: تگ $PP_VER در $PP_URL وجود ندارد. تگ‌های نسخه‌دار موجود (۱۵ آخر):"
  printf '%s\n' "$PP_SEMVER" | tail -15 | tr '\n' ' '; echo
  [ -z "${PP_VER_STRICT:-}" ] || { echo "ERROR: PP_VER_STRICT فعال است؛ تگ $PP_VER باید وجود داشته باشد"; exit 1; }
  PP_NEW="$(printf '%s\n' "$PP_SEMVER" | grep -E '^v1\.' | tail -1 || true)"   # فقط major=1 (امضای C API با پل JNI سازگار می‌ماند؛ بررسی نام توابع پایین‌تر انجام می‌شود)
  [ -n "$PP_NEW" ] || { echo "ERROR: هیچ تگ v1.x روی $PP_URL پیدا نشد؛ PP_VER را دستی بدهید"; exit 1; }
  echo "WARN: جایگزینی $PP_VER → $PP_NEW"
  PP_VER="$PP_NEW"
  # کلید حافظهٔ نهان باید نسخهٔ واقعاً ساخته‌شده را نشان بدهد
  STORE="$CACHE/phonemizer-$PP_VER-$SIG"
  if [ -f "$STORE/.done" ]; then echo "phonemizer: از حافظهٔ نهان ($STORE)"; install_from_store; exit 0; fi
elif [ -z "$PP_TAGS" ]; then
  echo "WARN: ls-remote پاسخ نداد؛ بدون بررسی تگ ادامه می‌دهیم"
fi
echo "phonemizer: piper-phonemize@$PP_VER"
rm -rf "$SRC"
for i in 1 2 3; do
  if git clone --depth 1 --branch "$PP_VER" "$PP_URL" "$SRC"; then break; fi
  rm -rf "$SRC"
  [ "$i" = 3 ] && { echo "ERROR: git clone piper-phonemize@$PP_VER ناموفق (شبکه؟ تگ وجود دارد؟)"; exit 1; }
  sleep 5
done
cd "$SRC"
HDR="$(grep -rl --include=c-api.h 'PiperPhonemizeInitialize' . | head -1 || true)"
[ -n "$HDR" ] || { echo "ERROR: c-api.h با PiperPhonemizeInitialize در سورس نیست"; exit 1; }
HDR_DIR="$(cd "$(dirname "$HDR")" && pwd)"
echo "phonemizer: header=$HDR_DIR/c-api.h"
for fn in PiperPhonemizeText PiperPhonemizeResultGetNumSentences PiperPhonemizeResultGetNumPhonemes PiperPhonemizeResultGetPhonemes PiperPhonemizeDestroyResult PiperPhonemizeGetVersionStr; do
  grep -q "$fn" "$HDR" || { echo "ERROR: $fn در c-api.h نیست؛ API این نسخه با پل JNI نمی‌خواند"; exit 1; }
done

mkdir -p "$STORE.tmp"; rm -rf "$STORE.tmp"/*
DATA_DIR=""
for abi in $ABIS; do
  [ -f "${SCRIPT[$abi]}" ] || { echo "ERROR: ${SCRIPT[$abi]} در سورس نیست"; exit 1; }
  echo "=== build $abi ==="
  bash "./${SCRIPT[$abi]}" > "/tmp/pp-build-$abi.log" 2>&1 || { echo "ERROR: ساخت $abi ناموفق؛ انتهای لاگ:"; tail -60 "/tmp/pp-build-$abi.log"; exit 1; }
  BD="build-android-$abi"; [ "$abi" = armeabi-v7a ] && BD="build-android-armv7-eabi"
  [ -d "$BD" ] || BD="$(ls -d build-android-* 2>/dev/null | grep -i "${abi%%-*}" | head -1 || true)"
  [ -d "$BD" ] || { echo "ERROR: پوشهٔ خروجی ساخت $abi پیدا نشد"; ls; exit 1; }
  LIB="$( (find "$BD" -name 'libpiper_phonemize.so' -type f | grep '/install/' ; find "$BD" -name 'libpiper_phonemize.so' -type f) | head -1 || true)"
  [ -n "$LIB" ] || { echo "ERROR: libpiper_phonemize.so برای $abi ساخته نشد"; find "$BD" -name '*.so' | head; exit 1; }
  LIBDIR="$(cd "$(dirname "$LIB")" && pwd)"
  O="$STORE.tmp/$abi"; mkdir -p "$O"
  # همهٔ .so های کنار آن، به‌جز onnxruntime و sherpa (آن‌ها را خود sherpa-onnx می‌آورد؛ کپی دوم نسخه را خراب می‌کرد)
  for f in "$LIBDIR"/*.so; do
    b="$(basename "$f")"
    case "$b" in libonnxruntime*|libsherpa*) continue;; esac
    cp -L "$f" "$O/$b"
  done
  [ -f "$O/libpiper_phonemize.so" ] || { echo "ERROR: libpiper_phonemize.so کپی نشد"; exit 1; }
  # پل JNI (ابتدا C؛ اگر هدر C++ می‌خواست، با C++)
  CC="$TC/bin/${CLANG[$abi]}"
  [ -x "$CC" ] || { echo "ERROR: کامپایلر $CC نیست"; exit 1; }
  OUTSO="$O/libpiperphon_jni.so"
  LINK=(-L"$O" -lpiper_phonemize -Wl,--no-undefined -Wl,-z,max-page-size=16384)
  if ! "$CC" -shared -fPIC -O2 -Wall -I"$HDR_DIR" "$HERE/piperphon_jni.c" -o "$OUTSO" "${LINK[@]}" 2>/tmp/pp-jni-c.log; then
    echo "WARN: ساخت JNI با C ناموفق؛ تلاش با C++"; tail -20 /tmp/pp-jni-c.log
    "${CC}++" -x c++ -static-libstdc++ -shared -fPIC -O2 -Wall -I"$HDR_DIR" "$HERE/piperphon_jni.c" -o "$OUTSO" "${LINK[@]}" \
      || { echo "ERROR: ساخت پل JNI برای $abi ناموفق"; exit 1; }
  fi
  [ -s "$OUTSO" ] || { echo "ERROR: libpiperphon_jni.so ساخته نشد"; exit 1; }
  # وابستگی‌ها: هر NEEDED باید یا سیستمی باشد یا کنار هم کپی شده یا از sherpa بیاید
  for so in "$O"/libpiperphon_jni.so "$O"/libpiper_phonemize.so; do
    for need in $("$TC/bin/llvm-readelf" -d "$so" | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p'); do
      case "$need" in
        libc.so|libm.so|libdl.so|liblog.so|libstdc++.so|libandroid.so|libz.so|libonnxruntime.so) ;;
        libc++_shared.so) [ -f "$O/libc++_shared.so" ] || cp "$TC/sysroot/usr/lib/${SYSLIB[$abi]}/libc++_shared.so" "$O/libc++_shared.so" ;;
        *) [ -f "$O/$need" ] || { echo "ERROR: $so به $need وابسته است ولی همراهش نیست"; exit 1; } ;;
      esac
    done
  done
  # دادهٔ espeak هم‌نسخهٔ همین کتابخانه (فقط یک‌بار)
  if [ -z "$DATA_DIR" ]; then
    P="$(find . -maxdepth 9 -type f -name phondata 2>/dev/null | head -1 || true)"
    if [ -n "$P" ] && [ -f "$(dirname "$P")/phontab" ] && [ -f "$(dirname "$P")/fa_dict" ]; then DATA_DIR="$(cd "$(dirname "$P")" && pwd)"; fi
  fi
  echo "ok: $abi → $(ls "$O" | tr '\n' ' ')"
done

if [ -n "$DATA_DIR" ]; then
  rm -rf "$STORE.tmp/espeak-ng-data"; cp -r "$DATA_DIR" "$STORE.tmp/espeak-ng-data"
  echo "phonemizer: espeak-ng-data هم‌نسخه از $DATA_DIR ($(du -sh "$STORE.tmp/espeak-ng-data" | cut -f1))"
else
  echo "WARN: espeak-ng-data هم‌نسخه (با fa_dict) در خروجی ساخت پیدا نشد؛ از دادهٔ داخل صداهای نصب‌شده استفاده می‌شود"
fi
rm -rf "$STORE"; mv "$STORE.tmp" "$STORE"; touch "$STORE/.done"
install_from_store
echo "phonemizer: تمام شد"
