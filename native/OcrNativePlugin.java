package ir.sewingstats.app;   // <-- appId پروژه (inject-native.sh جایگزین می‌کند)

/* r85: پارامتر rm="int8" در rec ⇒ مدل ..._rec_int8.onnx (اگر در assets بود و ساخت session موفق شد) وگرنه fp32؛ پاسخ rm=مدل واقعی، rmw=دلیل fallback.
   r23 — تشخیص/rec بومی با ORT. تغییرات r23: (۱) ms = زمان خالص inference در پاسخ rec/det؛ (۲) پیکربندی قابل‌تنظیم از JS (ep/th/ith/spin/mp)؛ (۳) شمارش مرجع روی session برای جلوگیری از بسته‌شدن session حین اجرا.
/* r19 PoC — ONNX Runtime بومی برای rec.
   وابستگی app/build.gradle:  implementation 'com.microsoft.onnxruntime:onnxruntime-android:1.21.0'
   مدل: assets/public/ocr/paddle/arabic_PP-OCRv5_mobile_rec.onnx (همان فایل وب).
   مرحلهٔ ۱ (همین فایل): bench از کنسول: await ocrNativeBench(1024, 4, 5) ⇒ px/s را با WASM (≈۱۳۵۰) مقایسه کن.
   مرحلهٔ ۲ (r20، opt-in): ocrNative(1) ⇒ متد rec با مسیر Worker→ترد اصلی→پلاگین؛ دو batch اول با WASM اعتبارسنجی و در صورت اختلاف >۲٪ خودکار خاموش می‌شود. */

/* r255 (java-r255، H1/H2/H3 — پچ P18):
   (۱) H1: خطای راه‌اندازی ORT یک‌بار با زنجیرهٔ علت و فهرست lib/<abi>/*.so داخل APK ثبت می‌شود و برای همهٔ فراخوانی‌های بعدی همان متن برمی‌گردد (قبلاً بار اول UnsatisfiedLinkError و از بار دوم فقط «Could not initialize class ai.onnxruntime.OrtEnvironment» با getMessage() می‌رسید).
   (۲) پیام خطا با نام کلاس؛ (۳) متد info({init?,retry?}) ⇒ نسخه، زمان نصب APK، ABI، lib‌ها (با init: هش libonnxruntime.so)، ویژگی‌های CPU (H6)، وضعیت ORT (بدون init به ORT دست نمی‌زند)؛
   (۴) bench: گرم‌کردن ×wu (پیش‌فرض ۲)، msMed/pxsMed، علت بازگشت XNNPACK به CPU (xw)، پارامتر tws=«عرض‌ها» برای هزینهٔ اولین‌دیدن هر شکل (cold/warm)؛
   (۵) رویداد JS «life» با چرخهٔ حیات Activity (ساعت یکنواخت+دیواری+تعاملی/Doze/صرفه‌جویی). */
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.content.res.Configuration;
import android.util.Base64;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@CapacitorPlugin(name = "OcrNative")
public class OcrNativePlugin extends Plugin {
    private static final String MODEL = "public/ocr/paddle/arabic_PP-OCRv5_mobile_rec.onnx";
    private static final String MODEL8 = "public/ocr/paddle/arabic_PP-OCRv5_mobile_rec_int8.onnx";   // r85: اختیاری؛ فقط با rm=int8 و وجود فایل
    private static final int H = 48;
    private static final String NAT_VER = "java-r255";   // r255: نسخهٔ این فایل (در info)
    private OrtEnvironment env;
    private static final String DET = "public/ocr/paddle/PP-OCRv5_mobile_det.onnx";

    private byte[] readAsset(String p) throws Exception {
        try (InputStream in = getContext().getAssets().open(p)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(1 << 20);
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toByteArray();
        }
    }

    /* r23: پیکربندی ORT. پیش‌فرض‌ها = رفتار r22 (xnnpack، intra=th، spin و memPattern روشن) */
    private static final class Cfg {
        int th, ith; boolean xnn, spin, mp, i8;
        String key() { return th + "|" + ith + "|" + xnn + "|" + spin + "|" + mp; }
    }

    private Cfg cfg(PluginCall call, int thDef) {
        Cfg c = new Cfg();
        c.th = Math.max(1, call.getInt("th", thDef));
        c.ith = Math.max(1, call.getInt("ith", c.th));
        c.xnn = !"cpu".equals(call.getString("ep", "xnn"));
        c.spin = call.getInt("spin", 1) != 0;
        c.mp = call.getInt("mp", 1) != 0;
        c.i8 = "int8".equals(call.getString("rm", ""));   // r85: فقط rec از آن استفاده می‌کند
        return c;
    }

    /* r23: session با شمارندهٔ استفاده؛ تعویض session فقط وقتی قدیمی بیکار است بسته می‌شود (بستن حین run = کرش بومی غیرقابل‌گرفتن) */
    private static final class Sess {
        OrtSession s; String key, in, ep = "cpu", rm = "fp32", why = "", xw = ""; int chans = 3, busy = 0; long loadMs; boolean retired = false;
    }
    private Sess recS, detS;

    /* r138: onTrimMemory — رویداد JS «trim» {level, freed}. فقط RUNNING_LOW(10)، RUNNING_CRITICAL(15) و ≥ MODERATE(60) اعلام می‌شود
       (UI_HIDDEN=20 و BACKGROUND=40 با هر رفتن به پس‌زمینه می‌آیند و نادیده‌اند). در 15 و ≥60 نشست‌های بیکار ORT بسته می‌شود؛ نشست مشغول دست‌نخورده و نشست بعدی در acquire ساخته می‌شود. */
    private ComponentCallbacks2 trimCb;

    private synchronized boolean trimSessions() {
        boolean f = false;
        if (recS != null && recS.busy == 0) { closeQuiet(recS); recS = null; f = true; }
        if (detS != null && detS.busy == 0) { closeQuiet(detS); detS = null; f = true; }
        return f;
    }

    @Override public void load() {
        trimCb = new ComponentCallbacks2() {
            @Override public void onTrimMemory(int level) {
                if (level != 10 && level != 15 && level < 60) return;
                boolean freed = level != 10 && trimSessions();
                JSObject o = new JSObject();
                o.put("level", level);
                o.put("freed", freed);
                notifyListeners("trim", o);
            }
            @Override public void onConfigurationChanged(Configuration c) { }
            @Override public void onLowMemory() { onTrimMemory(80); }
        };
        try { getContext().getApplicationContext().registerComponentCallbacks(trimCb); } catch (Throwable e) { trimCb = null; }
    }

    @Override protected void handleOnDestroy() {
        if (trimCb != null) { try { getContext().getApplicationContext().unregisterComponentCallbacks(trimCb); } catch (Throwable e) { } trimCb = null; }
    }

    /* ---------- r255 (H1): خطای راه‌اندازی ORT ---------- */
    private static volatile String initErr = null;

    private static String clip(String s, int n) { return s == null ? "" : (s.length() > n ? s.substring(0, n) : s); }

    /* «کلاس: پیام ← علت …» تا ۴ سطح + اولین فریم ریشه */
    private static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable t = e, root = e;
        for (int i = 0; t != null && i < 4; i++) {
            if (i > 0) sb.append(" <- ");
            sb.append(t.getClass().getSimpleName()).append(": ").append(clip(String.valueOf(t.getMessage()), 300));
            root = t; t = t.getCause();
        }
        StackTraceElement[] st = root.getStackTrace();
        if (st != null && st.length > 0) sb.append(" @ ").append(st[0]);
        return sb.toString();
    }

    /* پیام خطا برای JS: نام کلاس + پیام (خطای راه‌اندازیِ از پیش توصیف‌شده بدون تغییر) */
    private static String errTxt(Throwable e) {
        String m = String.valueOf(e.getMessage());
        if (initErr != null && m.equals(initErr)) return m;
        return e.getClass().getSimpleName() + ": " + m;
    }

    /* فهرست lib/<abi>/*.so داخل APK (نام=اندازهٔ بی‌فشرده): کدام libonnxruntime.so بسته‌بندی شده؟ (ناهمخوانی JNI و .so = ریشهٔ محتمل H1) */
    private String libsInfo(boolean hash) {
        try {
            String abi = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "";
            String pre = "lib/" + abi + "/";
            StringBuilder sb = new StringBuilder("abi=" + abi + " sdk=" + Build.VERSION.SDK_INT + " libs:");
            try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(getContext().getApplicationInfo().sourceDir)) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> en = z.entries();
                int n = 0;
                while (en.hasMoreElements() && n < 24) {
                    java.util.zip.ZipEntry ze = en.nextElement();
                    String nm = ze.getName();
                    if (nm.startsWith(pre) && nm.endsWith(".so")) { sb.append(' ').append(nm.substring(pre.length())).append('=').append(ze.getSize()); if (hash && nm.equals(pre + "libonnxruntime.so")) sb.append('#').append(sha16(z, ze)); n++; }
                }
            }
            return sb.toString();
        } catch (Throwable e) { return "libs?" + clip(String.valueOf(e.getMessage()), 80); }
    }

    /* SHA-256 (۱۶ هگز اول) — برای مقایسه با «unzip -p <aar> jni/<abi>/libonnxruntime.so | sha256sum | cut -c1-16» و فهمیدن اینکه .so کدام AAR در APK نشسته */
    private static String sha16(java.util.zip.ZipFile z, java.util.zip.ZipEntry ze) {
        try (java.io.InputStream in = z.getInputStream(ze)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
            byte[] h = md.digest();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", h[i] & 0xFF));
            return sb.toString();
        } catch (Throwable e) { return "?"; }
    }

    /* r255 (H6): ویژگی‌های CPU از /proc/cpuinfo (asimddp/i8mm/…) و «CPU part»های یکتا (خوشه‌های big.LITTLE) */
    private static String cpuInfo() {
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader("/proc/cpuinfo"))) {
            String l, feat = "";
            java.util.LinkedHashSet<String> parts = new java.util.LinkedHashSet<>();
            int n = 0;
            while ((l = br.readLine()) != null && n++ < 400) {
                if (feat.isEmpty() && l.startsWith("Features")) feat = clip(l.substring(l.indexOf(':') + 1).trim(), 300);
                else if (l.startsWith("CPU part")) parts.add(l.substring(l.indexOf(':') + 1).trim());
            }
            return "feat=" + feat + " parts=" + parts;
        } catch (Throwable e) { return "cpu?" + clip(String.valueOf(e.getMessage()), 60); }
    }

    private synchronized OrtEnvironment envOrThrow() throws Exception {
        if (env != null) return env;
        if (initErr != null) throw new Exception(initErr);
        try { env = OrtEnvironment.getEnvironment(); return env; }
        catch (Throwable e) { initErr = "ORT-init " + describe(e) + " | " + libsInfo(true); throw new Exception(initErr); }
    }

    /* ---------- r255 (H3): چرخهٔ حیات Activity ⇒ رویداد JS «life» {e,t(elapsedRealtime s),w(epoch s),i,d,s} ---------- */
    private void life(String ev) {
        try {
            JSObject o = new JSObject();
            o.put("e", ev); o.put("t", SystemClock.elapsedRealtime() / 1000); o.put("w", System.currentTimeMillis() / 1000);
            PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                o.put("i", pm.isInteractive() ? 1 : 0);
                o.put("s", pm.isPowerSaveMode() ? 1 : 0);
                if (Build.VERSION.SDK_INT >= 23) o.put("d", pm.isDeviceIdleMode() ? 1 : 0);
            }
            notifyListeners("life", o);
        } catch (Throwable x) { }
    }
    @Override protected void handleOnPause() { life("pause"); }
    @Override protected void handleOnResume() { life("resume"); }
    @Override protected void handleOnStop() { life("stop"); }
    @Override protected void handleOnStart() { life("start"); }

    /* r255: اطلاعات نسخه/دستگاه. {init?:1, retry?:1} ⇒ {ver, inst(زمان نصب APK، ms), vn, abi, sdk, libs, ort:{ok, ver?, providers?, err?}}؛ بدون init به ORT دست نمی‌زند */
    @PluginMethod
    public void info(final PluginCall call) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    JSObject o = new JSObject();
                    o.put("ver", NAT_VER);
                    try {
                        PackageInfo pi = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
                        o.put("inst", pi.lastUpdateTime); o.put("vn", String.valueOf(pi.versionName));
                    } catch (Throwable e) { o.put("inst", 0); }
                    o.put("abi", Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "");
                    o.put("sdk", Build.VERSION.SDK_INT);
                    o.put("libs", libsInfo(call.getInt("init", 0) != 0)); o.put("cpu", cpuInfo());
                    if (call.getInt("init", 0) != 0) {
                        if (call.getInt("retry", 0) != 0) initErr = null;
                        JSObject ort = new JSObject();
                        try {
                            OrtEnvironment e = envOrThrow();
                            ort.put("ok", true);
                            try { ort.put("ver", String.valueOf(OrtEnvironment.class.getMethod("getVersion").invoke(e))); } catch (Throwable x) { }
                            try { ort.put("providers", String.valueOf(OrtEnvironment.class.getMethod("getAvailableProviders").invoke(null))); } catch (Throwable x) { }
                        } catch (Throwable e) { ort.put("ok", false); ort.put("err", clip(String.valueOf(e.getMessage()), 1500)); }
                        o.put("ort", ort);
                    } else if (initErr != null) o.put("initErr", clip(initErr, 1500));
                    call.resolve(o);
                } catch (Throwable e) { call.reject(errTxt(e)); }
            }
        }, "ocr-native-info").start();
    }

    private Sess build(String model, Cfg c, String key) throws Exception {
        long t0 = System.nanoTime();
        Sess x = new Sess();
        env = envOrThrow();   // r255: خطای راه‌اندازی ثبت‌شده و پایدار (sticky)
        try (OrtSession.SessionOptions o = new OrtSession.SessionOptions()) {
            o.setIntraOpNumThreads(c.ith);
            o.setInterOpNumThreads(1);
            o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            if (!c.mp) o.setMemoryPatternOptimization(false);                       // شکل‌های پویا (۳۷ شکل یکتا) ⇒ طرح حافظهٔ هر شکل جدا
            if (!c.spin) o.addConfigEntry("session.intra_op.allow_spinning", "0"); // کمتر داغ/رقابت با Workerها
            if (c.xnn) {
                try {
                    Map<String, String> m = new HashMap<>();
                    m.put("intra_op_num_threads", String.valueOf(c.th));
                    o.addXnnpack(m);
                    x.ep = "xnnpack";
                } catch (Throwable e) { x.ep = "cpu"; x.xw = clip(describe(e), 200); }   // r255: علت بازگشت XNNPACK→CPU ثبت می‌شود (xw)
            }
            x.s = env.createSession(readAsset(model), o);
        }
        x.key = key;
        x.in = x.s.getInputNames().iterator().next();
        long[] sh = ((TensorInfo) x.s.getInputInfo().get(x.in).getInfo()).getShape();
        x.chans = sh.length > 1 && sh[1] > 0 ? (int) sh[1] : 3;
        x.loadMs = (System.nanoTime() - t0) / 1000000;
        return x;
    }

    private void closeQuiet(Sess x) { try { if (x.s != null) x.s.close(); } catch (Throwable e) { } x.s = null; }

    private synchronized Sess acquire(boolean isDet, Cfg c) throws Exception {
        Sess cur = isDet ? detS : recS;
        final String k = isDet ? c.key() : c.key() + "|" + (c.i8 ? "i8" : "f");
        if (cur == null || !cur.key.equals(k)) {
            Sess n;
            if (!isDet && c.i8) {   // r85: int8؛ هر خطا (نبودن فایل/ناسازگاری EP) ⇒ fp32 و rm="fp32" در پاسخ
                try { n = build(MODEL8, c, k); n.rm = "int8"; }
                catch (Throwable e) { n = build(MODEL, c, k); n.rm = "fp32"; String m = String.valueOf(e.getMessage()); n.why = m.length() > 260 ? m.substring(m.length() - 260) : m; }   /* دلیل واقعی انتهای پیام است */
            } else n = build(isDet ? DET : MODEL, c, k);
            if (cur != null) { cur.retired = true; if (cur.busy == 0) closeQuiet(cur); }
            if (isDet) detS = n; else recS = n;
            cur = n;
        }
        cur.busy++;
        return cur;
    }

    private synchronized void release(Sess x) {
        x.busy--;
        if (x.retired && x.busy <= 0) closeQuiet(x);
    }

    /* تشخیص: {w,h,th,data(base64 uint8 [h*w] خاکستری),ep?,spin?,mp?} ⇒ {out: base64 uint8 [h*w] = احتمال×۲۵۵, ms}؛ نرمال‌سازی ImageNet همان JS (v/(255σ) − μ/σ) */
    @PluginMethod
    public void det(final PluginCall call) {
        final int w = call.getInt("w", 0), h = call.getInt("h", 0);
        final Cfg c = cfg(call, 2);
        final String data = call.getString("data", "");
        new Thread(new Runnable() {
            @Override public void run() {
                Sess S = null;
                try {
                    S = acquire(true, c);
                    byte[] u = Base64.decode(data, Base64.NO_WRAP);
                    final int n = w * h;
                    if (w <= 0 || h <= 0 || u.length != n) { call.reject("اندازهٔ ورودی نامعتبر"); return; }
                    float[] f = new float[3 * n];
                    final float k1 = 1f / (255f * 0.229f), k2 = 1f / (255f * 0.224f), k3 = 1f / (255f * 0.225f);
                    final float c1 = 0.485f / 0.229f, c2 = 0.456f / 0.224f, c3 = 0.406f / 0.225f;
                    for (int i = 0; i < n; i++) {
                        float v = u[i] & 0xFF;
                        f[i] = v * k1 - c1; f[n + i] = v * k2 - c2; f[2 * n + i] = v * k3 - c3;
                    }
                    try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(f), new long[]{1, 3, h, w})) {
                        long t0 = System.nanoTime();
                        try (OrtSession.Result r = S.s.run(Collections.singletonMap(S.in, t))) {
                            long ms = (System.nanoTime() - t0) / 1000000;
                            OnnxTensor out = (OnnxTensor) r.get(0);
                            FloatBuffer fb = out.getFloatBuffer();
                            if (fb.remaining() != n) { call.reject("اندازهٔ خروجی det نامعتبر"); return; }
                            byte[] q = new byte[n];
                            for (int i = 0; i < n; i++) {
                                float v = fb.get(i);
                                q[i] = (byte) (v <= 0f ? 0 : v >= 1f ? 255 : (int) (v * 255f + 0.5f));
                            }
                            JSObject o = new JSObject();
                            o.put("out", Base64.encodeToString(q, Base64.NO_WRAP));
                            o.put("ms", ms);
                            call.resolve(o);
                        }
                    }
                } catch (Throwable e) { call.reject(errTxt(e)); }
                finally { if (S != null) release(S); }
            }
        }, "ocr-native-det").start();
    }

    /* بنچ: {tw,b,n,th,ith?,ep?('xnn'|'cpu'),spin?,mp?} ⇒ {ep,th,cfg,chans,loadMs,msPerRun,msBest,pxs}؛ pxs = b*tw*1000/msPerRun (قابل‌مقایسه با Σ sumTW/ثانیه در لاگ) */
    @PluginMethod
    public void bench(final PluginCall call) {
        final int tw = Math.max(64, call.getInt("tw", 768)), b = Math.max(1, call.getInt("b", 3)), n = Math.max(1, call.getInt("n", 5));
        final int wu = Math.max(1, Math.min(5, call.getInt("wu", 2)));          // r255: تعداد گرم‌کردن (پیش‌فرض ۲)
        final String tws = call.getString("tws", "");                          // r255: عرض‌ها برای هزینهٔ اولین‌دیدن شکل، مثلاً "256,384,512"
        final Cfg c = cfg(call, 3);
        new Thread(new Runnable() {
            @Override public void run() {
                Sess S = null;
                try {
                    S = acquire(false, c);
                    float[] d = new float[b * S.chans * H * tw];
                    java.util.Arrays.fill(d, 0.1f);
                    long[] shape = new long[]{b, S.chans, H, tw};
                    long best = Long.MAX_VALUE, sum = 0;
                    long[] ts = new long[n];
                    for (int wi = 0; wi < wu; wi++) {   /* r255: گرم‌کردن ×wu */
                        try (OnnxTensor w = OnnxTensor.createTensor(env, FloatBuffer.wrap(d), shape);
                             OrtSession.Result wr = S.s.run(Collections.singletonMap(S.in, w))) { /* اجرای گرم‌کننده */ }
                    }
                    for (int i = 0; i < n; i++) {
                        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(d), shape)) {
                            long t0 = System.nanoTime();
                            try (OrtSession.Result r = S.s.run(Collections.singletonMap(S.in, t))) {
                                long dt = (System.nanoTime() - t0) / 1000000;
                                sum += dt; ts[i] = dt; if (dt < best) best = dt;
                            }
                        }
                    }
                    double avg = sum / (double) n;
                    JSObject o = new JSObject();
                    o.put("ep", S.ep); o.put("th", c.th); o.put("cfg", c.key()); o.put("chans", S.chans); o.put("loadMs", S.loadMs); o.put("rm", S.rm);
                    o.put("msPerRun", Math.round(avg)); o.put("msBest", best);
                    long[] sd = ts.clone(); java.util.Arrays.sort(sd);
                    double med = sd.length % 2 == 1 ? sd[sd.length / 2] : (sd[sd.length / 2 - 1] + sd[sd.length / 2]) / 2.0;   // r255: میانه (مقاوم به اجرای پرت)
                    o.put("msMed", Math.round(med)); o.put("pxsMed", med > 0 ? Math.round(b * (double) tw * 1000 / med) : 0); o.put("wu", wu);
                    if (!S.xw.isEmpty()) o.put("xw", S.xw);
                    o.put("pxs", Math.round(b * (double) tw * 1000 / avg));
                    if (!tws.isEmpty()) {   /* r255 (H2/H4): هزینهٔ «اولین دیدن شکل»: برای هر عرض یک اجرای سرد (شکل نو) و یک اجرای گرم؛ عرض‌ها باید با tw فرق داشته باشند */
                        String[] ws = tws.split(",");
                        java.util.ArrayList<Long> wl = new java.util.ArrayList<>(), cold = new java.util.ArrayList<>(), warm = new java.util.ArrayList<>();
                        for (int q = 0; q < ws.length && q < 8; q++) {
                            int w2 = Math.max(64, Math.min(2048, Integer.parseInt(ws[q].trim())));
                            float[] d2 = new float[b * S.chans * H * w2];
                            java.util.Arrays.fill(d2, 0.1f);
                            long[] sh2 = new long[]{b, S.chans, H, w2};
                            long[] tt = new long[2];
                            for (int r2 = 0; r2 < 2; r2++) {
                                try (OnnxTensor t2 = OnnxTensor.createTensor(env, FloatBuffer.wrap(d2), sh2)) {
                                    long t1 = System.nanoTime();
                                    try (OrtSession.Result rr = S.s.run(Collections.singletonMap(S.in, t2))) { tt[r2] = (System.nanoTime() - t1) / 1000000; }
                                }
                            }
                            wl.add((long) w2); cold.add(tt[0]); warm.add(tt[1]);
                        }
                        o.put("tws", new org.json.JSONArray(wl.toString())); o.put("cold", new org.json.JSONArray(cold.toString())); o.put("warm", new org.json.JSONArray(warm.toString()));
                    }
                    call.resolve(o);
                } catch (Throwable e) { call.reject(errTxt(e)); }
                finally { if (S != null) release(S); }
            }
        }, "ocr-native-bench").start();
    }

    /* اجرای واقعی: {b,tw,data(base64 uint8 [b,48,tw]),th,ith?,ep?,spin?,mp?} ⇒ {dims:[b,T,C], k:6, idx(base64 uint16), val(base64 float32), ms}؛ ms = زمان خالص inference؛ مصرف‌کننده: natServe در index.html */
    @PluginMethod
    public void rec(final PluginCall call) {
        final int tw = call.getInt("tw", 0), b = call.getInt("b", 0);
        final Cfg c = cfg(call, 3);
        final String data = call.getString("data", "");
        new Thread(new Runnable() {
            @Override public void run() {
                Sess S = null;
                try {
                    S = acquire(false, c);
                    final int chans = S.chans;
                    byte[] u = Base64.decode(data, Base64.NO_WRAP);
                    int pl = H * tw;
                    if (tw <= 0 || b <= 0 || u.length != b * pl) { call.reject("اندازهٔ ورودی نامعتبر"); return; }
                    float[] f = new float[b * chans * pl];
                    for (int i = 0; i < b; i++)
                        for (int ch = 0; ch < chans; ch++) {
                            int o = (i * chans + ch) * pl, s = i * pl;
                            for (int k = 0; k < pl; k++) f[o + k] = (u[s + k] & 0xFF) / 127.5f - 1f;   // همان نرمال‌سازی JS
                        }
                    try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(f), new long[]{b, chans, H, tw})) {
                        long t0 = System.nanoTime();
                        try (OrtSession.Result r = S.s.run(Collections.singletonMap(S.in, t))) {
                            long ms = (System.nanoTime() - t0) / 1000000;
                            OnnxTensor out = (OnnxTensor) r.get(0);
                            long[] dm = out.getInfo().getShape();
                            FloatBuffer fb = out.getFloatBuffer();
                            final int Bn = (int) dm[0], Tn = (int) dm[1], C = (int) dm[2], K = 6, rows = Bn * Tn;
                            ByteBuffer ib = ByteBuffer.allocate(rows * K * 2).order(ByteOrder.LITTLE_ENDIAN);
                            ByteBuffer vb = ByteBuffer.allocate(rows * K * 4).order(ByteOrder.LITTLE_ENDIAN);
                            int[] ti = new int[K];
                            float[] tv = new float[K];
                            for (int q = 0; q < rows; q++) {   // top-6 هر گام زمانی (حجم خروجی ~۱۰۰ برابر کمتر از logits کامل)
                                int base = q * C, mi = 0;
                                for (int k = 0; k < K; k++) { tv[k] = -1f; ti[k] = 0; }
                                for (int cc = 0; cc < C; cc++) {
                                    float v = fb.get(base + cc);
                                    if (v > tv[mi]) {
                                        tv[mi] = v; ti[mi] = cc; mi = 0;
                                        for (int k = 1; k < K; k++) if (tv[k] < tv[mi]) mi = k;
                                    }
                                }
                                for (int k = 0; k < K; k++) { ib.putShort((short) ti[k]); vb.putFloat(tv[k] < 0 ? 0f : tv[k]); }
                            }
                            JSObject o = new JSObject();
                            o.put("dims", new org.json.JSONArray(java.util.Arrays.toString(dm)));
                            o.put("k", K);
                            o.put("ms", ms);
                            o.put("rm", S.rm);                                  // r85: مدل واقعاً اجراشده
                            if (!S.why.isEmpty()) o.put("rmw", S.why);
                            o.put("idx", Base64.encodeToString(ib.array(), Base64.NO_WRAP));
                            o.put("val", Base64.encodeToString(vb.array(), Base64.NO_WRAP));
                            call.resolve(o);
                        }
                    }
                } catch (Throwable e) { call.reject(errTxt(e)); }
                finally { if (S != null) release(S); }
            }
        }, "ocr-native-rec").start();
    }
}
