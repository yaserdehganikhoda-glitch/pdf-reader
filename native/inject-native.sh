#!/usr/bin/env bash
# بعد از «npx cap add android» اجرا شود (داخل GitHub Actions).
# همهٔ کارها را خودش می‌کند: .so، API کاتلین، پلاگین، MainActivity، تنظیمات Gradle.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(pwd)"
VER="${SHERPA_VER:-1.12.13}"

# appId از تنظیمات Capacitor
CFG=$(ls capacitor.config.json capacitor.config.ts capacitor.config.js 2>/dev/null | head -1 || true)
APP_ID=$(grep -oE "appId['\"]?[[:space:]]*[:=][[:space:]]*['\"][^'\"]+" "$CFG" | head -1 | sed -E "s/.*['\"]//")
[ -n "$APP_ID" ] || { echo "appId پیدا نشد"; exit 1; }
echo "appId = $APP_ID"
PKG_PATH="android/app/src/main/java/$(echo "$APP_ID" | tr . /)"
[ -d android/app/src/main ] || { echo "پوشهٔ android ساخته نشده (اول npx cap add android)"; exit 1; }
mkdir -p "$PKG_PATH" android/app/src/main/jniLibs android/app/src/main/java/com/k2fsa/sherpa/onnx

# 1) کتابخانه‌های بومی sherpa-onnx
curl -fL --retry 3 -o /tmp/sherpa-android.tar.bz2 \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VER}/sherpa-onnx-v${VER}-android.tar.bz2"
tar xjf /tmp/sherpa-android.tar.bz2 -C /tmp
for ABI in ${PIPER_ABIS:-arm64-v8a armeabi-v7a}; do
  [ -d "/tmp/jniLibs/$ABI" ] && cp -r "/tmp/jniLibs/$ABI" android/app/src/main/jniLibs/
done

# 2) API کاتلین همان نسخه
git clone --depth 1 --branch "v${VER}" https://github.com/k2-fsa/sherpa-onnx /tmp/sherpa-src
cp /tmp/sherpa-src/sherpa-onnx/kotlin-api/*.kt android/app/src/main/java/com/k2fsa/sherpa/onnx/

# 3) پلاگین + MainActivity با appId درست
sed "s/^package .*/package $APP_ID/" "$HERE/PiperNativePlugin.kt" > "$PKG_PATH/PiperNativePlugin.kt"
sed "s/^package .*/package $APP_ID;/" "$HERE/MainActivity.java"   > "$PKG_PATH/MainActivity.java"

# 4) امضای ثابت (تا هر نسخه روی قبلی نصب شود)
cp "$HERE/debug.keystore" android/app/debug.keystore

# 4.5) مدل صدا داخل APK (بدون نیاز به دانلود؛ PIPER_BUNDLE=0 برای خاموش‌کردن)
if [ "${PIPER_BUNDLE:-1}" = "1" ]; then
  ASSETS=android/app/src/main/assets/piper
  mkdir -p "$ASSETS"
  for V in ${PIPER_VOICES:-amir-medium}; do
    if [ ! -f "$ASSETS/vits-piper-fa_IR-$V/tokens.txt" ]; then
      curl -fL --retry 3 -o "/tmp/voice-$V.tar.bz2" "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-fa_IR-$V.tar.bz2"
      tar xjf "/tmp/voice-$V.tar.bz2" -C "$ASSETS"
    fi
    [ -f "$ASSETS/vits-piper-fa_IR-$V/tokens.txt" ] || { echo "مدل $V کامل نیست"; exit 1; }
  done
fi

# 4.6) قاعدهٔ ProGuard (برای ساخت release)
PR=android/app/proguard-rules.pro
if [ -f "$PR" ] && ! grep -q k2fsa "$PR"; then
  printf '\n-keep class com.k2fsa.sherpa.onnx.** { *; }\n-keep class %s.PiperNativePlugin { *; }\n' "$APP_ID" >> "$PR"
fi

# 5) تنظیمات Gradle
python3 - <<'PY'
import re
p='android/build.gradle'; s=open(p).read()
if 'kotlin-gradle-plugin' not in s:
    s=re.sub(r"(classpath ['\"]com\.android\.tools\.build:gradle:[^'\"]+['\"])",
             r"\1\n        classpath 'org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.22'", s, count=1)
open(p,'w').write(s)

p='android/app/build.gradle'; s=open(p).read()
if "kotlin-android" not in s:
    s=re.sub(r"(apply plugin: ['\"]com\.android\.application['\"])", r"\1\napply plugin: 'kotlin-android'", s, count=1)
if 'kotlinOptions' not in s:
    s=re.sub(r"(android\s*\{)", r"\1\n    kotlinOptions { jvmTarget = '17' }\n    packagingOptions { jniLibs { useLegacyPackaging = true } }", s, count=1)
if 'signingConfigs' not in s:
    s=re.sub(r"(android\s*\{)", r"\1\n    signingConfigs {\n        debug {\n            storeFile file('debug.keystore')\n            storePassword 'android'\n            keyAlias 'androiddebugkey'\n            keyPassword 'android'\n        }\n    }", s, count=1)
if 'commons-compress' not in s:
    s=re.sub(r"(dependencies\s*\{)", r"\1\n    implementation 'org.apache.commons:commons-compress:1.26.2'", s, count=1)
open(p,'w').write(s)
PY
echo "inject-native: تمام شد"
