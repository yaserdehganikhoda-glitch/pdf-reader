#!/usr/bin/env bash
# بعد از «npx cap add android» اجرا شود (داخل GitHub Actions).
# همهٔ کارها را خودش می‌کند: .so، API کاتلین، پلاگین، MainActivity، تنظیمات Gradle.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(pwd)"
VER="${SHERPA_VER:-1.12.13}"
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

# appId از تنظیمات Capacitor
CFG=$(ls capacitor.config.json capacitor.config.ts capacitor.config.js 2>/dev/null | head -1 || true)
APP_ID=$(grep -oE "appId['\"]?[[:space:]]*[:=][[:space:]]*['\"][^'\"]+" "$CFG" | head -1 | sed -E "s/.*['\"]//")
[ -n "$APP_ID" ] || { echo "appId پیدا نشد"; exit 1; }
echo "appId = $APP_ID"
PKG_PATH="android/app/src/main/java/$(echo "$APP_ID" | tr . /)"
[ -d android/app/src/main ] || { echo "پوشهٔ android ساخته نشده (اول npx cap add android)"; exit 1; }
mkdir -p "$PKG_PATH" android/app/src/main/jniLibs android/app/src/main/java/com/k2fsa/sherpa/onnx

# 1) کتابخانه‌های بومی sherpa-onnx
SA="sherpa-onnx-v${VER}-android.tar.bz2"
SU="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VER}/$SA"
SP="$(dl "$SU" "$SA")"
if ! tar tjf "$SP" >/dev/null 2>&1; then rm -f "$SP"; SP="$(dl "$SU" "$SA")"; fi
tar xjf "$SP" -C /tmp
cp -r /tmp/jniLibs/* android/app/src/main/jniLibs/
# فقط ARM (گوشی‌های واقعی): حجم APK بسیار کمتر می‌شود
rm -rf android/app/src/main/jniLibs/x86 android/app/src/main/jniLibs/x86_64

# 1b) r23: نسخهٔ onnxruntime همراه sherpa. AAR جاوای ORT (برای OCR بومی) باید هم‌نسخه باشد؛ چون هر دو libonnxruntime.so دارند و فقط یکی داخل APK می‌ماند (pickFirsts پایین).
ORT_V="${ORT_VER:-}"
if [ -z "$ORT_V" ]; then
  ORT_V="$(strings android/app/src/main/jniLibs/arm64-v8a/libonnxruntime.so 2>/dev/null | grep -E '^1\.(1[4-9]|2[0-9])\.[0-9]+$' | sort -V | tail -1 || true)"
fi
[ -n "$ORT_V" ] || { ORT_V=1.22.0; echo "WARN: نسخهٔ ORT داخل sherpa تشخیص داده نشد؛ پیش‌فرض $ORT_V (با ORT_VER=... عوض کن)" >&2; }
echo "ORT نسخهٔ sherpa = $ORT_V"
export ORT_V

# 2) API کاتلین همان نسخه
rm -rf /tmp/sherpa-src
for i in 1 2 3; do
  if git clone --depth 1 --branch "v${VER}" https://github.com/k2-fsa/sherpa-onnx /tmp/sherpa-src; then break; fi
  rm -rf /tmp/sherpa-src
  if [ "$i" = 3 ]; then echo "git clone ناموفق"; exit 1; fi
  sleep 5
done
cp /tmp/sherpa-src/sherpa-onnx/kotlin-api/*.kt android/app/src/main/java/com/k2fsa/sherpa/onnx/

# 3) پلاگین + MainActivity با appId درست
sed "s/^package .*/package $APP_ID/" "$HERE/PiperNativePlugin.kt" > "$PKG_PATH/PiperNativePlugin.kt"
sed "s/^package .*/package $APP_ID;/" "$HERE/MainActivity.java"   > "$PKG_PATH/MainActivity.java"
sed "s/^package .*/package $APP_ID;/" "$HERE/OcrNativePlugin.java" > "$PKG_PATH/OcrNativePlugin.java"   # r23: OCR بومی (ORT)

# 3b) صداهای فارسی: به‌طور پیش‌فرض داخل APK نیستند و بعد از نصب از داخل برنامه دانلود می‌شوند.
# برای همراه‌کردن (اختیاری): VOICES="fa_IR-gyro-medium ..." bash native/inject-native.sh
VOICES="${VOICES:-}"
VBASE="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"
VDIR=android/app/src/main/assets/voices
if [ -n "$VOICES" ]; then
mkdir -p "$VDIR"
GOT=0
for V in $VOICES; do
  F="vits-piper-$V.tar.bz2"; OKV=0; P=""
  for try in 1 2; do
    if P="$(dl "$VBASE/$F" "$F")" && tar tjf "$P" > "/tmp/$F.lst" 2>/dev/null \
       && grep -q "tokens.txt" "/tmp/$F.lst" && grep -q "\.onnx" "/tmp/$F.lst"; then OKV=1; break; fi
    rm -f "$CACHE/$F"
  done
  if [ "$OKV" = 1 ]; then
    cp "$P" "$VDIR/$V.tar.bz2"; GOT=$((GOT+1)); echo "voice ok: $V"
  else
    echo "WARN: صدا دانلود/تأیید نشد: $V"
  fi
done
[ "$GOT" -ge 1 ] || { echo "هیچ صدایی داخل APK قرار نگرفت"; exit 1; }
else
  echo "voices: داخل APK گذاشته نشد (دانلود بعد از نصب)"
fi

# 3c) مانیفست: سرویس پخش پیش‌زمینه و مجوزهای لازم (بدون آن پخش با صفحهٔ خاموش بی‌صدا کار نمی‌کند؛ دو مجوز آخر برای فهرست خودکار PDFها)
# r35: RECORD_AUDIO و MODIFY_AUDIO_SETTINGS برای یادداشت صوتی
python3 - <<'PY'
m='android/app/src/main/AndroidManifest.xml'; s=open(m,encoding='utf-8').read()
perms=''.join('    <uses-permission android:name="android.permission.%s"/>\n' % p
              for p in ['WAKE_LOCK','FOREGROUND_SERVICE','FOREGROUND_SERVICE_MEDIA_PLAYBACK','POST_NOTIFICATIONS','MANAGE_EXTERNAL_STORAGE','READ_EXTERNAL_STORAGE','RECORD_AUDIO','MODIFY_AUDIO_SETTINGS']
              if 'android.permission.%s"' % p not in s)
if perms: s=s.replace('<application', perms+'    <application', 1)
if 'PlayerService' not in s:
    s=s.replace('</application>','        <service android:name=".PlayerService" android:exported="false" android:foregroundServiceType="mediaPlayback" />\n    </application>',1)
open(m,'w',encoding='utf-8').write(s)
assert 'PlayerService' in s and 'WAKE_LOCK' in s and 'MANAGE_EXTERNAL_STORAGE' in s
PY

# 3d) r93: برگشت سیستم (کشیدن از لبه / دکمهٔ برگشت). بدون این، گوشی برنامه را به صفحهٔ خانه می‌برد.
# BackApp برای هر Activity یک OnBackPressedCallback اضافه می‌کند که اول از JS (window.__nativeBack) می‌پرسد؛ اگر JS مصرفش نکرد، رفتار پیش‌فرض اجرا می‌شود.
cat > "$PKG_PATH/BackApp.java" <<'JAVA'
package __APP_ID__;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import java.util.Map;
import java.util.WeakHashMap;

public class BackApp extends Application {
  private final Map<Activity, Boolean> done = new WeakHashMap<>();

  @Override public void onCreate() {
    super.onCreate();
    registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
      @Override public void onActivityStarted(Activity a) { install(a); }
      @Override public void onActivityCreated(Activity a, Bundle b) {}
      @Override public void onActivityResumed(Activity a) {}
      @Override public void onActivityPaused(Activity a) {}
      @Override public void onActivityStopped(Activity a) {}
      @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
      @Override public void onActivityDestroyed(Activity a) {}
    });
  }

  private void install(final Activity a) {
    try {
      if (!(a instanceof ComponentActivity) || done.containsKey(a)) return;
      done.put(a, Boolean.TRUE);
      final ComponentActivity ca = (ComponentActivity) a;
      ca.getOnBackPressedDispatcher().addCallback(new OnBackPressedCallback(true) {
        @Override public void handleOnBackPressed() {
          final OnBackPressedCallback self = this;
          WebView w = find(ca.getWindow().getDecorView());
          if (w == null) { pass(ca, self); return; }
          try {
            w.evaluateJavascript("(function(){try{return window.__nativeBack?window.__nativeBack():false}catch(e){return false}})()", new ValueCallback<String>() {
              @Override public void onReceiveValue(String v) { if (!"true".equals(v)) pass(ca, self); }
            });
          } catch (Throwable t) { pass(ca, self); }
        }
      });
    } catch (Throwable t) { }
  }

  private static void pass(ComponentActivity ca, OnBackPressedCallback cb) {
    cb.setEnabled(false);
    ca.getOnBackPressedDispatcher().onBackPressed();
    cb.setEnabled(true);
  }

  private static WebView find(View v) {
    if (v instanceof WebView) return (WebView) v;
    if (v instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) v;
      for (int i = 0; i < g.getChildCount(); i++) { WebView w = find(g.getChildAt(i)); if (w != null) return w; }
    }
    return null;
  }
}
JAVA
sed -i "s/__APP_ID__/$APP_ID/" "$PKG_PATH/BackApp.java"
python3 - <<'PY'
import re
m='android/app/src/main/AndroidManifest.xml'; s=open(m,encoding='utf-8').read()
mm=re.search(r'<application\b[^>]*>', s)
if mm and 'android:name=' not in mm.group(0):
    s=s.replace('<application', '<application android:name=".BackApp"', 1)
    open(m,'w',encoding='utf-8').write(s); print('BackApp registered in manifest')
else:
    print('WARN: <application> دارای android:name است؛ BackApp ثبت نشد (برگشت بومی کار نمی‌کند)')
PY

# 4) امضای ثابت (تا هر نسخه روی قبلی نصب شود)
cp "$HERE/debug.keystore" android/app/debug.keystore

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
    s=re.sub(r"(android\s*\{)", r"\1\n    kotlinOptions { jvmTarget = '17' }\n    packagingOptions { jniLibs { useLegacyPackaging = true; pickFirsts.add('**/libonnxruntime.so') } }", s, count=1)
if 'signingConfigs' not in s:
    s=re.sub(r"(android\s*\{)", r"\1\n    signingConfigs {\n        debug {\n            storeFile file('debug.keystore')\n            storePassword 'android'\n            keyAlias 'androiddebugkey'\n            keyPassword 'android'\n        }\n    }", s, count=1)
if 'commons-compress' not in s:
    s=re.sub(r"(dependencies\s*\{)", r"\1\n    implementation 'org.apache.commons:commons-compress:1.26.2'", s, count=1)
if 'abiFilters' not in s and re.search(r"defaultConfig\s*\{", s):
    s=re.sub(r"(defaultConfig\s*\{)", r"\1\n        ndk { abiFilters 'arm64-v8a', 'armeabi-v7a' }", s, count=1)
if 'onnxruntime-android' not in s:
    import os
    s=re.sub(r"(dependencies\s*\{)", r"\1\n    implementation 'com.microsoft.onnxruntime:onnxruntime-android:" + os.environ['ORT_V'] + "'", s, count=1)
if 'documentfile' not in s:
    s=re.sub(r"(dependencies\s*\{)", r"\1\n    implementation 'androidx.documentfile:documentfile:1.0.1'", s, count=1)
open(p,'w').write(s)
PY
echo "inject-native: تمام شد"
