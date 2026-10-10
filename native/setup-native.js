#!/usr/bin/env node
/*
  نصب خودکار افزونهٔ بومی PiperNative در پروژهٔ Capacitor (اندروید)
  اجرا: از ریشهٔ پروژه (کنار capacitor.config.*):   node piper-native-android/setup-native.js
  بارها قابل اجراست (idempotent). Node 18 یا بالاتر لازم است.
*/
const fs = require('fs');
const path = require('path');

const SHERPA_VERSIONS = ['1.12.40', '1.12.39', '1.12.38'];   // اولین نسخه‌ای که دانلود شود
const KOTLIN_VERSION = '1.9.24';
const COMPRESS_VERSION = '1.26.1';

const root = process.cwd();
const here = __dirname;
const log = (...a) => console.log('•', ...a);
const die = (m) => { console.error('✗ ' + m); process.exit(1); };
const read = (p) => fs.readFileSync(p, 'utf8');
const write = (p, s) => fs.writeFileSync(p, s, 'utf8');
function backup(p) { if (!fs.existsSync(p + '.bak')) fs.copyFileSync(p, p + '.bak'); }

const androidDir = path.join(root, 'android');
if (!fs.existsSync(androidDir)) die('پوشهٔ android پیدا نشد. ابتدا «npx cap add android» را اجرا کنید.');

/* ---------- پیدا کردن MainActivity ---------- */
function walk(dir, out) {
  for (const f of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, f.name);
    if (f.isDirectory()) walk(p, out); else out.push(p);
  }
  return out;
}
const srcDir = path.join(androidDir, 'app', 'src', 'main');
const javaRoot = path.join(srcDir, 'java');
if (!fs.existsSync(javaRoot)) die('android/app/src/main/java پیدا نشد.');
const main = walk(javaRoot, []).find((p) => /MainActivity\.(java|kt)$/.test(p));
if (!main) die('MainActivity پیدا نشد.');
const isKt = main.endsWith('.kt');
let mainSrc = read(main);
const pkgMatch = mainSrc.match(/^\s*package\s+([\w.]+)/m);
if (!pkgMatch) die('نام package در MainActivity پیدا نشد.');
const pkg = pkgMatch[1];
log('package:', pkg, '| MainActivity:', path.relative(root, main));

/* ---------- کپی افزونه ---------- */
const pluginDst = path.join(path.dirname(main), 'PiperNativePlugin.kt');
write(pluginDst, read(path.join(here, 'PiperNativePlugin.kt')).replace(/__PKG__/g, pkg));
log('افزونه کپی شد:', path.relative(root, pluginDst));

/* ---------- ثبت افزونه در MainActivity ---------- */
if (/PiperNativePlugin/.test(mainSrc)) {
  log('MainActivity از قبل افزونه را ثبت کرده است.');
} else {
  backup(main);
  const reg = isKt ? 'registerPlugin(PiperNativePlugin::class.java)' : 'registerPlugin(PiperNativePlugin.class);';
  if (/super\.onCreate\s*\(/.test(mainSrc)) {
    mainSrc = mainSrc.replace(/(\s*)super\.onCreate\s*\(/, `$1${reg}$1super.onCreate(`);
  } else if (isKt) {
    mainSrc = mainSrc.replace(/class\s+MainActivity\s*:\s*BridgeActivity\(\)\s*(\{\s*\})?/,
      `class MainActivity : BridgeActivity() {\n    override fun onCreate(savedInstanceState: Bundle?) {\n        ${reg}\n        super.onCreate(savedInstanceState)\n    }\n}`);
    if (!/import android\.os\.Bundle/.test(mainSrc)) mainSrc = mainSrc.replace(/(package\s+[\w.]+\s*\n)/, '$1\nimport android.os.Bundle\n');
  } else {
    mainSrc = mainSrc.replace(/public\s+class\s+MainActivity\s+extends\s+BridgeActivity\s*\{\s*\}/,
      `public class MainActivity extends BridgeActivity {\n    @Override\n    public void onCreate(Bundle savedInstanceState) {\n        ${reg}\n        super.onCreate(savedInstanceState);\n    }\n}`);
    if (!/import android\.os\.Bundle;/.test(mainSrc)) mainSrc = mainSrc.replace(/(package\s+[\w.]+;\s*\n)/, '$1\nimport android.os.Bundle;\n');
  }
  if (!/PiperNativePlugin/.test(mainSrc)) {
    die('MainActivity ساختار غیرمعمول دارد. این خط را دستی پیش از super.onCreate اضافه کنید:\n  ' + reg);
  }
  write(main, mainSrc);
  log('افزونه در MainActivity ثبت شد (نسخهٔ پشتیبان: MainActivity.*.bak).');
}

/* ---------- Gradle: ریشه (Kotlin) ---------- */
const rootGradle = path.join(androidDir, 'build.gradle');
let rg = read(rootGradle);
if (!/kotlin-gradle-plugin/.test(rg)) {
  backup(rootGradle);
  const re = /(classpath\s+['"]com\.android\.tools\.build:gradle:[^'"]+['"])/;
  if (!re.test(rg)) die('خط classpath مربوط به Android Gradle Plugin در android/build.gradle پیدا نشد.');
  rg = rg.replace(re, `$1\n        classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:${KOTLIN_VERSION}"`);
  write(rootGradle, rg);
  log('Kotlin به android/build.gradle اضافه شد.');
} else log('Kotlin از قبل در android/build.gradle هست.');

/* ---------- Gradle: برنامه ---------- */
const appGradle = path.join(androidDir, 'app', 'build.gradle');
let ag = read(appGradle);
backup(appGradle);
if (!/kotlin-android/.test(ag)) {
  ag = ag.replace(/(apply plugin:\s*['"]com\.android\.application['"])/, "$1\napply plugin: 'kotlin-android'");
  if (!/kotlin-android/.test(ag)) die("خط apply plugin: 'com.android.application' در app/build.gradle پیدا نشد (فرمت kts پشتیبانی نمی‌شود).");
}
// ABI ها (حجم کمتر)
if (!/abiFilters/.test(ag) && !process.argv.includes('--all-abis')) {
  ag = ag.replace(/defaultConfig\s*\{/, "defaultConfig {\n        ndk { abiFilters 'arm64-v8a', 'armeabi-v7a' }");
}
// فایل AAR
const libs = path.join(androidDir, 'app', 'libs');
fs.mkdirSync(libs, { recursive: true });
async function ensureAar() {
  const have = fs.readdirSync(libs).find((f) => /^sherpa-onnx-[\d.]+\.aar$/.test(f));
  if (have) { log('AAR موجود است:', have); return have; }
  for (const v of SHERPA_VERSIONS) {
    const url = `https://github.com/k2-fsa/sherpa-onnx/releases/download/v${v}/sherpa-onnx-${v}.aar`;
    try {
      log('دانلود', url);
      const r = await fetch(url, { redirect: 'follow' });
      if (!r.ok) { log('  پاسخ', r.status); continue; }
      const buf = Buffer.from(await r.arrayBuffer());
      if (buf.length < 1_000_000) { log('  فایل کوچک‌تر از انتظار است'); continue; }
      const name = `sherpa-onnx-${v}.aar`;
      fs.writeFileSync(path.join(libs, name), buf);
      return name;
    } catch (e) { log('  خطا:', e.message); }
  }
  die('دانلود sherpa-onnx.aar ناموفق بود. فایل را از https://github.com/k2-fsa/sherpa-onnx/releases دستی بگیرید و در android/app/libs بگذارید، سپس دوباره اجرا کنید.');
}
(async () => {
  const aar = await ensureAar();
  const deps = [
    ['kotlin-bom', `    implementation platform("org.jetbrains.kotlin:kotlin-bom:${KOTLIN_VERSION}")`],
    ['commons-compress', `    implementation "org.apache.commons:commons-compress:${COMPRESS_VERSION}"`],
    ['sherpa-onnx-', `    implementation files("libs/${aar}")`]
  ];
  ag = ag.replace(/\n\s*implementation files\("libs\/sherpa-onnx-[\d.]+\.aar"\)/g, '');
  for (const [key, line] of deps) {
    if (key === 'sherpa-onnx-' || !ag.includes(key)) {
      if (!/dependencies\s*\{/.test(ag)) die('بلوک dependencies در app/build.gradle پیدا نشد.');
      ag = ag.replace(/dependencies\s*\{/, `dependencies {\n${line}`);
    }
  }
  write(appGradle, ag);
  log('app/build.gradle به‌روز شد.');

  // مدل‌ها فشرده نشوند + عدم خطای هم‌خوانی JVM
  const gp = path.join(androidDir, 'gradle.properties');
  let g = fs.existsSync(gp) ? read(gp) : '';
  if (!/kotlin\.jvm\.target\.validation\.mode/.test(g)) { g += (g.endsWith('\n') || !g ? '' : '\n') + 'kotlin.jvm.target.validation.mode=warning\n'; write(gp, g); }

  // proguard
  const pr = path.join(androidDir, 'app', 'proguard-rules.pro');
  let p = fs.existsSync(pr) ? read(pr) : '';
  if (!/k2fsa\.sherpa/.test(p)) { p += '\n-keep class com.k2fsa.sherpa.onnx.** { *; }\n-keep class org.apache.commons.compress.** { *; }\n-dontwarn org.apache.commons.compress.**\n'; write(pr, p); }

  console.log('\n✓ تمام شد. حالا:  npx cap sync android   و سپس build (Android Studio یا GitHub Actions).');
})();
