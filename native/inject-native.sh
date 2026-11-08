#!/usr/bin/env bash
# بعد از «npx cap add android» اجرا شود (داخل GitHub Actions).
# همهٔ کارها را خودش می‌کند: .so، API کاتلین، پلاگین، MainActivity، تنظیمات Gradle.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(pwd)"
# r181: sherpa-onnx 2.x (بدون espeak؛ آوانگاری فارسی با piper-phonemize بیرونی). برای برگشت به قبل باید پلاگین و اسکریپت r180 را با هم برگرداند
VER="${SHERPA_VER:-2.0.1}"
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
rm -rf /tmp/jniLibs
tar xjf "$SP" -C /tmp
[ -d /tmp/jniLibs/arm64-v8a ] || { echo "ERROR: jniLibs/arm64-v8a در بستهٔ sherpa-onnx v$VER نیست"; exit 1; }
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
# r181: نسخهٔ ORT در sherpa 2.x عوض شده؛ AAR هم‌نسخه باید روی Maven باشد وگرنه Gradle بعد از ده‌ها دقیقه می‌شکند
ort_code() {   # کد HTTP وجود onnxruntime-android:$1 روی Maven (200 = موجود)
  local v="$1" c="000" M
  for M in https://repo1.maven.org/maven2 https://repo.maven.apache.org/maven2 https://maven-central.storage-download.googleapis.com/maven2; do
    c="$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 "$M/com/microsoft/onnxruntime/onnxruntime-android/$v/onnxruntime-android-$v.pom" || echo 000)"
    [ "$c" = 200 ] && break
  done
  echo "$c"
}
ORT_HTTP="$(ort_code "$ORT_V")"
# r182: sherpa-onnx ≥ 1.13.8 از بیلد اختصاصی ORT (مثلاً 1.28.2) استفاده می‌کند که روی Maven نیست (فقط 1.28.0). API در یک minor ثابت است؛ پس AAR همان minor با نزدیک‌ترین patch کمتر سازگار است. اگر ORT_VER دستی داده شده باشد، دست نمی‌زنیم.
if [ "$ORT_HTTP" = 404 ] && [ -z "${ORT_VER:-}" ] && [[ "$ORT_V" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  ORT_MM="${ORT_V%.*}"; ORT_P="${ORT_V##*.}"
  while [ "$ORT_P" -gt 0 ]; do
    ORT_P=$((ORT_P-1))
    if [ "$(ort_code "$ORT_MM.$ORT_P")" = 200 ]; then
      echo "WARN: AAR onnxruntime-android:$ORT_V روی Maven نیست؛ از $ORT_MM.$ORT_P (همان minor، API یکسان) استفاده می‌شود"
      ORT_V="$ORT_MM.$ORT_P"; export ORT_V; ORT_HTTP=200; break
    fi
  done
fi
echo "ORT نسخهٔ AAR = $ORT_V"
if [ "$ORT_HTTP" = 404 ]; then
  echo "ERROR: onnxruntime-android:$ORT_V روی Maven نیست. نسخهٔ موجود را با ORT_VER=... بدهید (باید با libonnxruntime.so داخل sherpa سازگار باشد)"; exit 1
elif [ "$ORT_HTTP" != 200 ]; then
  echo "WARN: وجود onnxruntime-android:$ORT_V روی Maven تأیید نشد (HTTP $ORT_HTTP)"
fi

# 2) API کاتلین همان نسخه
rm -rf /tmp/sherpa-src
for i in 1 2 3; do
  if git clone --depth 1 --branch "v${VER}" https://github.com/k2-fsa/sherpa-onnx /tmp/sherpa-src; then break; fi
  rm -rf /tmp/sherpa-src
  if [ "$i" = 3 ]; then echo "git clone ناموفق"; exit 1; fi
  sleep 5
done
cp /tmp/sherpa-src/sherpa-onnx/kotlin-api/*.kt android/app/src/main/java/com/k2fsa/sherpa/onnx/

# 2b) r181: sherpa-onnx ≥ 2.0 آوانگاری را به بیرون سپرده؛ API کاتلین باید phonemeCodepoints و generateWithConfigAndCallback را داشته باشد
KT=android/app/src/main/java/com/k2fsa/sherpa/onnx
case "$VER" in 1.*) NEED_PHON=0;; *) NEED_PHON=1;; esac   # ۱.x: espeak داخل خود sherpa بود
if [ "$NEED_PHON" = 1 ]; then
  grep -q 'phonemeCodepoints' "$KT"/*.kt || { echo "ERROR: API کاتلین sherpa-onnx v$VER فیلد phonemeCodepoints ندارد؛ این نسخه با پلاگین سازگار نیست"; exit 1; }
  grep -q 'generateWithConfigAndCallback' "$KT"/*.kt || { echo "ERROR: generateWithConfigAndCallback در API کاتلین v$VER نیست"; exit 1; }
  echo "--- تعریف‌های Kotlin API (برای لاگ) ---"; grep -n 'phonemeCodepoints\|fun generateWithConfigAndCallback' "$KT"/*.kt | head -8
  # آوانگار بیرونی: piper-phonemize + espeak-ng + پل JNI (کتابخانه‌ها داخل jniLibs، دادهٔ espeak هم‌نسخه داخل assets/phonemizer)
  mkdir -p android/app/src/main/java/com/pdfvoice/phon android/app/src/main/assets
  cp "$HERE/Phonemizer.java" android/app/src/main/java/com/pdfvoice/phon/Phonemizer.java
  OFFLINE_CACHE="$CACHE" bash "$HERE/build-phonemizer.sh" android/app/src/main/jniLibs android/app/src/main/assets
  # r187: واج‌آرایی اختصاصی مانا (کسرهٔ اضافه با ALBERT/ONNX): کد کاتلین + دادهٔ مدل که هنگام بیلد دانلود می‌شود (≈ ۴۰ مگابایت به APK اضافه می‌کند)
  cp "$HERE/ManaPhon.kt" android/app/src/main/java/com/pdfvoice/phon/ManaPhon.kt
  OFFLINE_CACHE="$CACHE" bash "$HERE/build-mana-phon.sh" android/app/src/main/assets || echo "WARN: build-mana-phon.sh ناموفق؛ مانا بدون لایهٔ اختصاصی کار می‌کند"
  for abi in arm64-v8a armeabi-v7a; do
    for so in libpiper_phonemize_core.so libpiperphon_jni.so libsherpa-onnx-jni.so libonnxruntime.so; do
      [ -s "android/app/src/main/jniLibs/$abi/$so" ] || { echo "ERROR: $abi/$so در jniLibs نیست"; exit 1; }
    done
  done
else
  echo "ERROR: SHERPA_VER=$VER (۱.x) با این پلاگین (r181) سازگار نیست؛ برای برگشت، اسکریپت و PiperNativePlugin.kt نسخهٔ r180 را با هم برگردانید"; exit 1
fi

# 3) پلاگین + MainActivity با appId درست
sed -e "s/^package .*/package $APP_ID/" -e "s/__SHERPA_VER__/$VER/" "$HERE/PiperNativePlugin.kt" > "$PKG_PATH/PiperNativePlugin.kt"
sed "s/^package .*/package $APP_ID;/" "$HERE/MainActivity.java"   > "$PKG_PATH/MainActivity.java"
sed "s/^package .*/package $APP_ID;/" "$HERE/OcrNativePlugin.java" > "$PKG_PATH/OcrNativePlugin.java"   # r23: OCR بومی (ORT)

# 3a) r188: مدل حذف‌نویز عصبی فقط «پایه» (dpdfnet_baseline.onnx، ۱۶kHz، سبک) و مستقیم از release رسمی sherpa-onnx؛ دیگر add-denoise-model.sh لازم نیست.
# برای نگذاشتن آن داخل APK: NO_DENOISE=1 (برنامه فقط با فیلتر کلاسیک کار می‌کند).
if [ "${NO_DENOISE:-0}" != 1 ]; then
  NDF="dpdfnet_baseline.onnx"
  NDU="https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/$NDF"
  mkdir -p android/app/src/main/assets/denoise
  rm -f android/app/src/main/assets/denoise/dpdfnet2.onnx android/app/src/main/assets/denoise/dpdfnet4.onnx android/app/src/main/assets/denoise/dpdfnet8.onnx android/app/src/main/assets/denoise/dpdfnet2_48khz_hr.onnx
  if NDP="$(dl "$NDU" "$NDF")" && [ -s "$NDP" ]; then
    cp "$NDP" "android/app/src/main/assets/denoise/$NDF"; echo "denoise: $NDF ($(du -h "$NDP" | cut -f1))"
  else
    echo "WARN: $NDF دانلود نشد؛ حذف‌نویز عصبی غیرفعال می‌ماند (فقط فیلتر کلاسیک)"
  fi
else
  echo "denoise: داخل APK گذاشته نشد (NO_DENOISE=1)"
fi

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

# 3c2) kt-r27 (P09-09): آیکن دکمه‌های «سرعت» و «توقف کامل» کارت مدیا/قفل‌صفحه (PlaybackState.CustomAction).
# مستند Android: آیکن باید در بستهٔ همان MediaSession باشد؛ پس به‌جای android.R.drawable دو drawable خود برنامه ساخته می‌شود (Kotlin با getIdentifier می‌خواند؛ نبودنشان فقط یعنی این دو دکمه نیست).
RESD=android/app/src/main/res/drawable
mkdir -p "$RESD"
cat > "$RESD/ic_pv_speed.xml" <<'XML'
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M4,18l8.5,-6L4,6v12zM13,6v12l8.5,-6L13,6z"/>
</vector>
XML
cat > "$RESD/ic_pv_stop.xml" <<'XML'
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M6,6h12v12H6z"/>
</vector>
XML

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

# 3e) kt-r27 (P0/P2): شواهد و گارد مانیفست. خروجی هم در لاگ و هم در «خلاصهٔ اجرا» (GITHUB_STEP_SUMMARY) چاپ می‌شود تا از گوشی خوانده شود.
# نبودِ ثبت PlayerService / نوع mediaPlayback / مجوزهای پخش پیش‌زمینه ⇒ بیلد قرمز (برای عبور موقت: MANIFEST_GUARD=0 در env گام).
PKG_PATH="$PKG_PATH" python3 - <<'PY'
import os, re, sys
m = 'android/app/src/main/AndroidManifest.xml'
s = open(m, encoding='utf-8').read()
pkg = os.environ.get('PKG_PATH', '')
bad = []
rep = ['### inject-native: شواهد مانیفست و Gradle (kt-r27)', '']
perms = re.findall(r'<uses-permission[^>]*>', s)
rep += ['**uses-permission**', '```'] + perms + ['```']
svc = re.search(r'<service\b[^>]*PlayerService[^>]*>', s)
rep += ['**PlayerService**', '```', svc.group(0) if svc else '(ثبت نشده)', '```']
app = re.search(r'<application\b[^>]*>', s)
act = re.search(r'<activity\b[^>]*>', s)
rep += ['**application / activity**', '```', app.group(0) if app else '(نیست)', act.group(0) if act else '(نیست)', '```']
rep += ['**BackApp.java**: ' + ('موجود' if os.path.exists(os.path.join(pkg, 'BackApp.java')) else 'نیست')]
for g in ('android/variables.gradle', 'android/app/build.gradle'):
    if os.path.exists(g):
        ls = [l.strip() for l in open(g, encoding='utf-8').read().splitlines() if re.search(r'\b(compileSdk|targetSdk|minSdk)(Version)?\b|\bnamespace\b|\bapplicationId\b', l)]
        rep += ['**' + g + '**', '```'] + ls + ['```']
for p in ('FOREGROUND_SERVICE', 'FOREGROUND_SERVICE_MEDIA_PLAYBACK', 'WAKE_LOCK', 'POST_NOTIFICATIONS'):
    if 'android.permission.%s"' % p not in s: bad.append('مجوز %s در مانیفست نیست' % p)
if not svc: bad.append('PlayerService در مانیفست ثبت نشده')
elif not re.search(r'foregroundServiceType\s*=\s*"[^"]*mediaPlayback', svc.group(0)): bad.append('PlayerService بدون foregroundServiceType=mediaPlayback است')
kt = os.path.join(pkg, 'PiperNativePlugin.kt')
if not os.path.exists(kt) or 'class PlayerService' not in open(kt, encoding='utf-8').read(): bad.append('کلاس PlayerService در PiperNativePlugin.kt نیست')
for d in ('ic_pv_speed.xml', 'ic_pv_stop.xml'):
    if not os.path.exists('android/app/src/main/res/drawable/' + d): rep.append('هشدار: ' + d + ' نیست (دکمه‌های مدیای سرعت/توقف نمایش داده نمی‌شوند)')
rep += ['', ('**گارد مانیفست: ناموفق** — ' + '؛ '.join(bad)) if bad else '**گارد مانیفست: سالم**']
txt = '\n'.join(rep) + '\n'
print(txt)
sm = os.environ.get('GITHUB_STEP_SUMMARY')
if sm:
    try: open(sm, 'a', encoding='utf-8').write(txt)
    except Exception as e: print('WARN: نوشتن خلاصه ممکن نشد', e)
if bad:
    off = os.environ.get('MANIFEST_GUARD', '1') == '0'
    for b in bad: print(('::warning::' if off else '::error::') + b)
    if not off: sys.exit(1)
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
    s=re.sub(r"(android\s*\{)", r"\1\n    kotlinOptions { jvmTarget = '17' }\n    packagingOptions { jniLibs { useLegacyPackaging = true; pickFirsts.add('**/libonnxruntime.so'); pickFirsts.add('**/libc++_shared.so') } }", s, count=1)
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
# 6) r184: onnxruntime-android:1.28.x در مانیفست خودش minSdk 24 اعلام می‌کند، ولی پروژهٔ Capacitor با 22 ساخته می‌شد
#    (خطای manifest merger). minSdk را به 24 می‌بریم؛ یعنی Android 5.x و 6.0 دیگر پشتیبانی نمی‌شوند.
python3 - <<'PY'
import re, os, sys
REQ = 24
changed = False
for p in ('android/variables.gradle', 'android/app/build.gradle'):
    if not os.path.exists(p): continue
    s = open(p, encoding='utf-8').read()
    def fix(m):
        global changed
        v = int(m.group(2))
        if v >= REQ: return m.group(0)
        changed = True
        return m.group(1) + str(REQ)
    n = re.sub(r'(\bminSdk(?:Version)?\s*=?\s*)(\d+)', fix, s)
    if n != s: open(p, 'w', encoding='utf-8').write(n)
    for line in n.splitlines():
        if re.search(r'\bminSdk(?:Version)?\b', line): print(p + ': ' + line.strip())
if not changed:
    print('WARN: مقدار عددی minSdk کمتر از %d پیدا/اصلاح نشد؛ خطوط بالا را ببینید' % REQ)
PY
echo "inject-native: تمام شد"
