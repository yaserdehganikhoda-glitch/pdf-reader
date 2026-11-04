#!/usr/bin/env python3
"""NeuralDenoiser.kt را از روی API کاتلین واقعیِ همان نسخهٔ sherpa-onnx می‌سازد (حذف نویز عصبی DPDFNet/GTCRN).
نام کلاس‌ها/پارامترها از سورس خوانده می‌شود؛ اگر چیزی نخواند، یک «استاب» ساخته می‌شود تا بیلد نشکند و برنامه فقط بدون حذف‌نویز عصبی کار کند.
usage: gen-denoiser.py <kotlin-api-dir> <out.kt> <appId>
"""
import glob, os, re, sys

kt_dir, out, app_id = sys.argv[1], sys.argv[2], sys.argv[3]
src = "\n".join(open(f, encoding="utf-8").read() for f in sorted(glob.glob(os.path.join(kt_dir, "*.kt"))))

def class_params(name):
    m = re.search(r"class\s+" + re.escape(name) + r"\s*\(", src)
    if not m:
        return None
    i = m.end(); depth = 1; j = i
    while depth and j < len(src):
        c = src[j]
        depth += (c == "(") - (c == ")")
        j += 1
    body = re.sub(r"=\s*[^,]*\([^)]*\)", "", src[i:j - 1])   # مقدارهای پیش‌فرضِ سازنده‌دار را حذف می‌کند
    return {pm.group(1): pm.group(2) for pm in re.finditer(r"(?:va[lr]\s+)?(\w+)\s*:\s*([\w.<>?]+)", body)}

STUB = """package %s

import android.content.Context

/** استاب: حذف نویز عصبی در این نسخهٔ sherpa-onnx/بیلد در دسترس نیست (%s). */
object NeuralDenoiser {
    fun status(ctx: Context): String = "unsupported"
    fun ready(ctx: Context): Boolean = false
    fun process(ctx: Context, x: FloatArray, sr: Int, mix: Float): FloatArray = x
}
"""

def stub(why):
    open(out, "w", encoding="utf-8").write(STUB % (app_id, why))
    print("gen-denoiser: STUB —", why)
    sys.exit(0)

mc = class_params("OfflineSpeechDenoiserModelConfig")
cfg = class_params("OfflineSpeechDenoiserConfig")
dn = class_params("OfflineSpeechDenoiser")
da = class_params("DenoisedAudio")
if not (mc and cfg and dn and da):
    stub("کلاس‌های OfflineSpeechDenoiser* در API کاتلین نیست")
if not re.search(r"fun\s+run\s*\(\s*samples\s*:\s*FloatArray\s*,\s*sampleRate\s*:\s*Int", src):
    stub("متد run(samples, sampleRate) پیدا نشد")
if "samples" not in da or "sampleRate" not in da:
    stub("DenoisedAudio فیلد samples/sampleRate ندارد")

cfg_field = next((k for k, t in cfg.items() if t.rstrip("?") == "OfflineSpeechDenoiserModelConfig"), None)
dn_cfg_param = next((k for k, t in dn.items() if t.rstrip("?") == "OfflineSpeechDenoiserConfig"), None)
if not cfg_field or not dn_cfg_param:
    stub("فیلد model در Config یا پارامتر config در OfflineSpeechDenoiser پیدا نشد")

variants = {}   # kind -> (field, type)
for k, t in mc.items():
    tt = t.rstrip("?")
    inner = class_params(tt)
    if inner is None or "model" not in inner:
        continue
    low = tt.lower()
    if "dpdf" in low:
        variants["dpdf"] = (k, tt)
    elif "gtcrn" in low:
        variants["gtcrn"] = (k, tt)
if not variants:
    stub("نوع مدل GTCRN/DPDFNet در ModelConfig نیست")

extra = []
if "numThreads" in mc: extra.append("numThreads = 2")
if "debug" in mc: extra.append("debug = false")
if "provider" in mc: extra.append('provider = "cpu"')
extra_s = (", " + ", ".join(extra)) if extra else ""

branches = []
for kind, (field, typ) in variants.items():
    cond = 'lower.startsWith("gtcrn")' if kind == "gtcrn" else 'lower.contains("dpdf")'
    branches.append('            %s -> OfflineSpeechDenoiserModelConfig(%s = %s(model = dst.absolutePath)%s)' % (cond, field, typ, extra_s))
when_body = "\n".join(branches) + '\n            else -> { err = "model-type"; return null }'
imports = "\n".join("import com.k2fsa.sherpa.onnx.%s" % c for c in sorted({"OfflineSpeechDenoiser", "OfflineSpeechDenoiserConfig", "OfflineSpeechDenoiserModelConfig"} | {t for _, t in variants.values()}))
am = "assetManager = null, " if "assetManager" in dn else ""

KT = r'''package __APP__

import android.content.Context
__IMPORTS__
import java.io.File
import java.io.FileOutputStream

/**
 * r182: حذف نویز عصبی (DPDFNet/GTCRN از sherpa-onnx) برای نویز «پخته‌شده» در خروجی مدل‌هایی مثل gyro که فیلتر کلاسیک نمی‌تواند حذفش کند.
 * تولیدشده توسط gen-denoiser.py از روی API کاتلین همان نسخهٔ sherpa؛ دستی ویرایش نکنید.
 * مدل: assets/denoise/*.onnx (داخل APK؛ آفلاین). ورودی هر نرخی باشد sherpa خودش بازنمونه می‌کند؛ خروجی با نرخ بومی مدل است و اینجا به نرخ ورودی برمی‌گردد.
 */
object NeuralDenoiser {
    @Volatile private var dn: OfflineSpeechDenoiser? = null
    @Volatile private var err: String? = null
    @Volatile private var name = ""
    private val lock = Any()

    private fun modelName(ctx: Context): String? =
        try { ctx.assets.list("denoise")?.firstOrNull { it.endsWith(".onnx") } } catch (e: Throwable) { null }

    fun status(ctx: Context): String = when {
        err != null -> "off(" + err + ")"
        dn != null -> name
        modelName(ctx) == null -> "none"
        else -> "ready"
    }

    /** آیا حذف نویز عصبی قابل‌استفاده است (در صورت نیاز مدل را باز می‌کند) */
    fun ready(ctx: Context): Boolean = open(ctx) != null

    private fun open(ctx: Context): OfflineSpeechDenoiser? {
        dn?.let { return it }
        if (err != null) return null
        synchronized(lock) {
            dn?.let { return it }
            if (err != null) return null
            try {
                val n = modelName(ctx)
                if (n == null) { err = "no-model"; return null }
                val dst = File(ctx.filesDir, "denoise/" + n)
                if (!dst.isFile || dst.length() < 100_000L) {
                    dst.parentFile?.mkdirs()
                    val tmp = File(dst.path + ".tmp")
                    ctx.assets.open("denoise/" + n).use { i -> FileOutputStream(tmp).use { o -> i.copyTo(o, 64 * 1024) } }
                    if (!tmp.renameTo(dst)) { tmp.delete(); err = "copy"; return null }
                }
                val lower = n.lowercase()
                val mcfg = when {
__WHEN__
                }
                val d = OfflineSpeechDenoiser(__AM__ __CFGPARAM__ = OfflineSpeechDenoiserConfig(__CFGFIELD__ = mcfg))
                dn = d; name = n
                return d
            } catch (e: Throwable) {
                err = (e.message ?: e.javaClass.simpleName).replace(Regex("\\s+"), " ").take(60)
                return null
            }
        }
    }

    /**
     * x را با مدل عصبی پاک می‌کند و با mix (۰..۱) با اصل ترکیب می‌کند. هر خطا ⇒ x دست‌نخورده.
     * خروجی هم‌طول x است.
     */
    fun process(ctx: Context, x: FloatArray, sr: Int, mix: Float): FloatArray {
        if (mix <= 0f || x.size < sr / 4 || x.size > sr * 45) return x
        val d = open(ctx) ?: return x
        return try {
            val a = synchronized(lock) { d.run(x, sr) }
            var w = a.samples
            if (w.isEmpty()) return x
            if (a.sampleRate != sr) w = resample(w, a.sampleRate, sr)
            val lag = bestLag(x, w, sr)
            val n = x.size
            val m = mix.coerceIn(0f, 1f)
            val out = FloatArray(n)
            for (i in 0 until n) {
                val k = i + lag
                val wet = if (k >= 0 && k < w.size) w[k] else 0f
                out[i] = wet * m + x[i] * (1f - m)
            }
            out
        } catch (e: Throwable) {
            err = (e.message ?: e.javaClass.simpleName).replace(Regex("\\s+"), " ").take(60)
            x
        }
    }

    /** تأخیر (نمونه) که w را با x هم‌تراز می‌کند؛ روی بلندترین ~۱ ثانیهٔ x و ±۴۰ms */
    private fun bestLag(x: FloatArray, w: FloatArray, sr: Int): Int {
        val maxLag = (sr * 0.04).toInt()
        val win = minOf(sr, x.size / 2)
        var best = 0; var bestE = -1.0
        val step = maxOf(1, sr / 10)
        var s = 0
        while (s + win <= x.size) {
            var e = 0.0; var i = s; val end = s + win
            while (i < end) { val v = x[i].toDouble(); e += v * v; i += 4 }
            if (e > bestE) { bestE = e; best = s }
            s += step
        }
        var bl = 0; var bc = -1e30
        for (lag in -maxLag..maxLag) {
            var c = 0.0; var i = best; val end = best + win
            while (i < end) { val k = i + lag; if (k >= 0 && k < w.size) c += x[i].toDouble() * w[k]; i += 2 }
            if (c > bc) { bc = c; bl = lag }
        }
        return bl
    }

    /** بازنمونه‌برداری با sinc پنجره‌دار (کیفیت بالا؛ برای ۴۸k→۲۲٫۰۵k) */
    private fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to) return x
        val ratio = from.toDouble() / to
        val n = (x.size / ratio).toInt()
        val fc = minOf(1.0, to.toDouble() / from) * 0.97
        val half = (16.0 / fc).toInt() + 1
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i * ratio
            val c = t.toInt()
            var acc = 0.0; var ws = 0.0
            for (k in (c - half + 1)..(c + half)) {
                if (k < 0 || k >= x.size) continue
                val d = t - k
                val a = Math.PI * d * fc
                val sinc = if (Math.abs(a) < 1e-9) 1.0 else Math.sin(a) / a
                val u = d / half
                val win = if (Math.abs(u) >= 1.0) 0.0 else 0.5 + 0.5 * Math.cos(Math.PI * u)
                val h = fc * sinc * win
                acc += x[k] * h; ws += h
            }
            out[i] = if (ws > 1e-9) (acc / ws).toFloat() else 0f
        }
        return out
    }
}
'''
KT = (KT.replace("__APP__", app_id).replace("__IMPORTS__", imports).replace("__WHEN__", when_body)
        .replace("__AM__", am).replace("__CFGPARAM__", dn_cfg_param).replace("__CFGFIELD__", cfg_field))
open(out, "w", encoding="utf-8").write(KT)
print("gen-denoiser: ok — variants=%s cfgField=%s cfgParam=%s ctor=%s" % (list(variants), cfg_field, dn_cfg_param, "assetManager" if am else "no-assetManager"))
