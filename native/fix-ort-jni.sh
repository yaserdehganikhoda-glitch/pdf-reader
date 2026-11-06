#!/usr/bin/env bash
# libonnxruntime4j_jni.so داخل AAR نسخهٔ نمادِ OrtGetApiBase@VERS_<AAR> می‌خواهد، ولی libonnxruntime.so مانده در APK (sherpa) فقط VERS_<sherpa> دارد
# (مثلاً 1.28.0 در برابر 1.28.2) ← بارگذاری Java API (مانا/OCR بومی) خطا می‌دهد. اینجا نیاز نسخه‌دار را برمی‌داریم تا به نماد پیش‌فرض وصل شود.
set -euo pipefail
J=android/app/src/main/jniLibs
G=android/app/build.gradle
C="${OFFLINE_CACHE:-$HOME/.offline-cache}"; mkdir -p "$C"
V="$(grep -oE "onnxruntime-android:[0-9]+\.[0-9]+\.[0-9]+" "$G" | head -1 | cut -d: -f2)"
[ -n "$V" ] || { echo "::warning::نسخهٔ onnxruntime-android در build.gradle پیدا نشد؛ fix-ort-jni رد شد"; exit 0; }
AAR="$C/onnxruntime-android-$V.aar"
if [ ! -s "$AAR" ]; then
  for M in https://repo1.maven.org/maven2 https://repo.maven.apache.org/maven2 https://maven-central.storage-download.googleapis.com/maven2; do
    curl -fsSL --retry 3 --max-time 300 -o "$AAR.part" "$M/com/microsoft/onnxruntime/onnxruntime-android/$V/onnxruntime-android-$V.aar" && mv "$AAR.part" "$AAR" && break || rm -f "$AAR.part"
  done
fi
[ -s "$AAR" ] || { echo "::warning::AAR onnxruntime-android:$V دانلود نشد؛ fix-ort-jni رد شد"; exit 0; }
command -v patchelf >/dev/null || { sudo apt-get update -qq && sudo apt-get install -y -qq patchelf; }
T="$(mktemp -d)"; DONE=0
for abi in arm64-v8a armeabi-v7a; do
  PROV="$(readelf --dyn-syms -W "$J/$abi/libonnxruntime.so" | grep -oE 'OrtGetApiBase@@VERS_[0-9.]+' | head -1 | sed 's/.*@@//' || true)"
  unzip -o -q "$AAR" "jni/$abi/libonnxruntime4j_jni.so" -d "$T"
  F="$T/jni/$abi/libonnxruntime4j_jni.so"
  NEED="$(readelf --dyn-syms -W "$F" | grep -oE 'OrtGetApiBase@VERS_[0-9.]+' | head -1 | sed 's/.*@//' || true)"
  echo "$abi: sherpa ORT=${PROV:-?} · JNI نیاز=${NEED:-بدون نسخه}"
  [ -n "$NEED" ] && [ "$NEED" != "$PROV" ] || continue
  patchelf --clear-symbol-version OrtGetApiBase "$F"
  if readelf --dyn-syms -W "$F" | grep -qE 'OrtGetApiBase@VERS_'; then echo "::error::$abi: نیاز نسخه‌دار برداشته نشد"; exit 1; fi
  cp -f "$F" "$J/$abi/libonnxruntime4j_jni.so"; DONE=$((DONE+1))
done
if [ "$DONE" -gt 0 ] && ! grep -q "libonnxruntime4j_jni.so" "$G"; then
  sed -i "s#pickFirsts.add('\*\*/libonnxruntime.so');#pickFirsts.add('**/libonnxruntime.so'); pickFirsts.add('**/libonnxruntime4j_jni.so');#" "$G"
  grep -q "libonnxruntime4j_jni.so" "$G" || { echo "::error::pickFirsts به build.gradle اضافه نشد"; exit 1; }
fi
echo "fix-ort-jni: $DONE ABI اصلاح شد"
