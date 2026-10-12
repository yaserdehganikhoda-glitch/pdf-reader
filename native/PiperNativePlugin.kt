package ir.sewingstats.app   // <-- تغییر بده به appId پروژهٔ خودت

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import androidx.activity.result.ActivityResult
import androidx.documentfile.provider.DocumentFile
import com.getcapacitor.annotation.ActivityCallback
import java.io.BufferedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors

/**
 * Piper (VITS) فارسی به‌صورت بومی با sherpa-onnx.
 * متدها: ping, stored, bundled, download({id}) = نصب صدا از فایل همراه برنامه (بدون اینترنت), synthesize({id, text, noiseScale?, noiseScaleW?, speed?}), remove({id})
 * پوشهٔ دلخواه کاربر: pickFolder, folder, exportVoices({ids?}), importVoices({ids?, overwrite?})
 * رویداد: "progress" {loaded, total}
 * کیفیت صدا: synthesize({..., clean?: 0..3, clarity?: 0..3}) → حذف نویز و ارتقای شفافیت/رسایی؛ ۰ خاموش، ۲ متعادل (پیش‌فرض)؛ جزئیات در SpeechClean (انتهای فایل)
 * مسترینگ: synthesize({..., master?: Boolean, fxPk?: dB, fxHs?: dB, fxLp?: Hz}) → EQ + کمپرسور + لیمیتر روی خروجی نهایی؛ جزئیات در MasterFx
 * ساخت صدا: synthesize({id, text, parts?:[{t, s}], gap?, noiseScale?, noiseScaleW?, speed?}) هر جمله (part) جدا ساخته و روی دیسک کش می‌شود؛
 *   cancel() کارهای صف‌شده و در حال ساخت را فوری رها می‌کند؛ cacheInfo / cacheClear / cacheLimit({mb})
 *
 * پخش بومی (AudioTrack): synthesize({..., keep: true}) به‌جای WAV یک handle برمی‌گرداند؛ playerPlay({handle, rate}), playerQueue({handle, gapMs}) (پخش پشت‌سرهم بی‌وقفه),
 *   playerRate({rate}) (کندکردن/تندکردن با WSOLA بدون تغییر زیر‌وبمی), playerStop(), release({handle?|all?})
 *   رویدادها: playerStart {h}, playerPos {h, p:0..1}, playerEnd {h}, playerError {h, msg}
 *
 * مسیر ذخیرهٔ صداها (یک‌جا قابل تغییر): <externalFilesDir>/piper/<id>/
 */
@CapacitorPlugin(name = "PiperNative")
class PiperNativePlugin : Plugin() {

    private val io = Executors.newSingleThreadExecutor()     // دانلود/استخراج
    @Volatile private var underruns = 0                      // مجموع underrun های AudioTrack از شروع برنامه (نشانهٔ لکنت پخش)

    private fun crashFile() = File(context.filesDir, "crash.log")

    /** ثبت کرش‌های کنترل‌نشده در فایل تا اجرای بعدی در «پیشرفته ‹ گزارش خطا» دیده شود؛ بعد از ثبت، رفتار پیش‌فرض سیستم ادامه می‌یابد */
    override fun load() {
        try {
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { th, ex ->
                try {
                    val sw = java.io.StringWriter()
                    ex.printStackTrace(java.io.PrintWriter(sw))
                    val f = crashFile()
                    val old = if (f.exists()) f.readText() else ""
                    val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                    f.writeText((old + "[" + stamp + "] thread=" + th.name + "\n" + sw.toString().take(3000) + "\n").takeLast(20000))
                } catch (e: Throwable) {}
                prev?.uncaughtException(th, ex)
            }
        } catch (e: Throwable) {}
    }

    @PluginMethod
    fun getCrashLog(call: PluginCall) {
        val f = crashFile()
        call.resolve(JSObject().put("text", try { if (f.exists()) f.readText() else "" } catch (e: Throwable) { "" }))
    }

    @PluginMethod
    fun clearCrashLog(call: PluginCall) {
        try { crashFile().delete() } catch (e: Throwable) {}
        call.resolve()
    }

    @PluginMethod
    fun diag(call: PluginCall) {
        call.resolve(JSObject().put("underruns", underruns).put("sampleRate", sampleRate).put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("abi", android.os.Build.SUPPORTED_ABIS.joinToString(",")).put("model", android.os.Build.MODEL))
    }
    private val cpu = Executors.newSingleThreadExecutor()    // ساخت صدا (پشت‌سرهم)
    private var tts: OfflineTts? = null
    @Volatile private var ttsId: String? = null
    private var ttsKey: String? = null   // شناسهٔ صدا + پارامترهای سبک؛ با تغییر آن موتور از نو ساخته می‌شود
    private var sampleRate = 22050

    /** آزادسازی موتور فقط روی رشتهٔ ساخت صدا؛ آزادسازی هم‌زمان با generate باعث بسته‌شدن ناگهانی برنامه می‌شد */
    private fun releaseEngine(id: String? = null) {
        try {
            cpu.submit(Runnable {
                if (id == null || ttsId == id) {
                    try { tts?.release() } catch (e: Throwable) {}
                    tts = null; ttsId = null; ttsKey = null
                }
            }).get()
        } catch (e: Throwable) {}
    }

    private fun root(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "piper").also { it.mkdirs() }
    }
    private fun voiceDir(id: String) = File(root(), id.replace(Regex("[^A-Za-z0-9_.-]"), "_"))
    private fun okMark(id: String) = File(voiceDir(id), ".ok")

    /** خروج کامل از برنامه (دکمهٔ خروج) */
    @PluginMethod
    fun exitApp(call: PluginCall) {
        call.resolve()
        activity?.runOnUiThread { try { activity.finishAffinity() } catch (e: Throwable) { activity.finish() } }
    }

    /** قفل بیدارباش CPU هنگام پخش: با خاموش‌شدن صفحه، مکث‌ها و ساخت جمله‌های بعدی کند یا قطع نشود.
     *  نیازمند <uses-permission android:name="android.permission.WAKE_LOCK"/> در AndroidManifest.xml؛ اگر نباشد بی‌صدا نادیده گرفته می‌شود. */
    private var wl: android.os.PowerManager.WakeLock? = null

    @PluginMethod
    fun keepAwake(call: PluginCall) {
        val on = call.getBoolean("on", false) ?: false
        try {
            if (on) {
                if (wl == null) {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "pdfvoice:tts").also { it.setReferenceCounted(false) }
                }
                if (wl?.isHeld != true) wl?.acquire(4 * 60 * 60 * 1000L)
            } else {
                if (wl?.isHeld == true) wl?.release()
            }
        } catch (e: Throwable) { /* بدون مجوز یا خطای سیستم: پخش عادی ادامه دارد */ }
        // سرویس پیش‌زمینه: پخش با صفحهٔ خاموش یا در پس‌زمینه توسط سیستم کشته نشود
        try {
            val si = Intent(context, PlayerService::class.java)
            if (on) {
                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                    context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    try { activity?.requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 9317) } catch (e: Throwable) {}
                }
                if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(si) else context.startService(si)
            } else context.stopService(si)
        } catch (e: Throwable) { /* بدون اعلان یا مجوز: پخش بدون سرویس ادامه دارد */ }
        call.resolve()
    }

    @PluginMethod
    fun ping(call: PluginCall) {
        call.resolve(JSObject().put("ok", true).put("player", true))
    }

    @PluginMethod
    fun stored(call: PluginCall) {
        val ids = org.json.JSONArray()
        // فقط صدایی که فایل مدلش واقعاً هست «دانلودشده» حساب می‌شود؛ اگر فایل‌ها پاک شده باشند نشانهٔ .ok هم پاک می‌شود تا دکمهٔ دانلود دوباره بیاید
        root().listFiles()?.forEach { d ->
            val ok = File(d, ".ok")
            if (ok.exists()) { if (findOnnx(d) != null) ids.put(d.name) else ok.delete() }
        }
        call.resolve(JSObject().put("ids", ids))
    }


    // ---------- پوشهٔ دلخواه کاربر (Storage Access Framework): هر صدا یک فایل piper-<id>.zip ----------
    private val prefs get() = context.getSharedPreferences("piper_prefs", Context.MODE_PRIVATE)

    private fun treeDoc(): DocumentFile? {
        val s = prefs.getString("tree", null) ?: return null
        val u = Uri.parse(s)
        val ok = context.contentResolver.persistedUriPermissions.any { it.uri == u && it.isReadPermission && it.isWritePermission }
        if (!ok) return null
        val d = DocumentFile.fromTreeUri(context, u)
        return if (d != null && d.exists() && d.canWrite()) d else null
    }

    private fun folderInfo(): JSObject {
        val d = treeDoc()
        val ids = org.json.JSONArray()
        d?.listFiles()?.forEach { f ->
            val n = f.name ?: ""
            if (f.isFile && n.startsWith("piper-") && n.endsWith(".zip")) ids.put(n.removePrefix("piper-").removeSuffix(".zip"))
        }
        return JSObject().put("set", d != null).put("name", d?.name ?: "").put("ids", ids)
    }

    @PluginMethod
    fun folder(call: PluginCall) {
        io.execute { call.resolve(folderInfo()) }
    }

    @PluginMethod
    fun pickFolder(call: PluginCall) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        startActivityForResult(call, i, "folderResult")
    }

    @ActivityCallback
    private fun folderResult(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        val u = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || u == null) { call.reject("انتخاب پوشه لغو شد"); return }
        try {
            context.contentResolver.takePersistableUriPermission(
                u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) { call.reject("دسترسی دائمی به پوشه داده نشد"); return }
        prefs.edit().putString("tree", u.toString()).apply()
        io.execute { call.resolve(folderInfo()) }
    }

    @PluginMethod
    fun exportVoices(call: PluginCall) {
        val want: List<String>? = try { call.getArray("ids")?.toList<String>() } catch (e: Exception) { null }
        io.execute {
            try {
                val t = treeDoc() ?: throw IllegalStateException("پوشه انتخاب نشده یا دسترسی آن از بین رفته است")
                val ids = (root().listFiles() ?: emptyArray())
                    .filter { File(it, ".ok").exists() }.map { it.name }
                    .filter { want == null || want.contains(it) }
                var n = 0
                for (id in ids) {
                    val name = "piper-$id.zip"
                    t.findFile(name)?.delete()
                    val f = t.createFile("application/zip", name) ?: throw IllegalStateException("ساخت فایل در پوشه ممکن نشد")
                    val dir = voiceDir(id)
                    val os = context.contentResolver.openOutputStream(f.uri, "w") ?: throw IllegalStateException("نوشتن در پوشه ممکن نشد")
                    ZipOutputStream(BufferedOutputStream(os, 1 shl 16)).use { z ->
                        z.setLevel(1)
                        dir.walkTopDown().filter { it.isFile && it.name != ".ok" }.forEach { file ->
                            z.putNextEntry(ZipEntry(file.relativeTo(dir).path.replace('\\', '/')))
                            file.inputStream().use { it.copyTo(z) }
                            z.closeEntry()
                        }
                    }
                    n++
                    notifyListeners("progress", JSObject().put("stage", "export").put("n", n))
                }
                call.resolve(JSObject().put("count", n))
            } catch (e: Throwable) {
                call.reject("ذخیره در پوشه ناموفق: ${e.message}")
            }
        }
    }

    @PluginMethod
    fun importVoices(call: PluginCall) {
        val want: List<String>? = try { call.getArray("ids")?.toList<String>() } catch (e: Exception) { null }
        val over = call.getBoolean("overwrite", false) ?: false
        io.execute {
            try {
                val t = treeDoc() ?: throw IllegalStateException("پوشه انتخاب نشده یا دسترسی آن از بین رفته است")
                val done = org.json.JSONArray()
                var n = 0
                for (f in t.listFiles()) {
                    val nm = f.name ?: continue
                    if (!f.isFile || !nm.startsWith("piper-") || !nm.endsWith(".zip")) continue
                    val id = nm.removePrefix("piper-").removeSuffix(".zip").replace(Regex("[^A-Za-z0-9_.-]"), "_")
                    if (want != null && !want.contains(id)) continue
                    if (!over && okMark(id).exists()) continue
                    if (ttsId == id) releaseEngine(id)
                    val dst = voiceDir(id)
                    dst.deleteRecursively(); dst.mkdirs()
                    try {
                        val ins = context.contentResolver.openInputStream(f.uri) ?: throw IllegalStateException("خواندن از پوشه ممکن نشد")
                        ZipInputStream(BufferedInputStream(ins, 1 shl 16)).use { z ->
                            while (true) {
                                val e = z.nextEntry ?: break
                                val out = File(dst, e.name)
                                if (!out.canonicalPath.startsWith(dst.canonicalPath + File.separator)) continue   // جلوگیری از path traversal
                                if (e.isDirectory) { out.mkdirs(); continue }
                                out.parentFile?.mkdirs()
                                FileOutputStream(out).use { z.copyTo(it) }
                            }
                        }
                        if (findOnnx(dst) == null || !File(findRoot(dst), "tokens.txt").exists())
                            throw IllegalStateException("فایل مدل کامل نیست")
                        okMark(id).writeText("1")
                        done.put(id); n++
                    } catch (e: Exception) {
                        dst.deleteRecursively()
                    }
                    notifyListeners("progress", JSObject().put("stage", "import").put("n", n))
                }
                call.resolve(JSObject().put("count", n).put("ids", done))
            } catch (e: Throwable) {
                call.reject("بارگذاری از پوشه ناموفق: ${e.message}")
            }
        }
    }

    @PluginMethod
    fun remove(call: PluginCall) {
        val id = call.getString("id") ?: return call.reject("id")
        io.execute {
            if (ttsId == id) releaseEngine(id)
            voiceDir(id).deleteRecursively()
            call.resolve()
        }
    }

    /** صداهای همراه برنامه: assets/voices/<id>.tar.bz2 (توسط inject-native.sh در APK گذاشته می‌شود) */
    private fun bundledIds(): Set<String> = try {
        context.assets.list("voices")?.filter { it.endsWith(".tar.bz2") }?.map { it.removeSuffix(".tar.bz2") }?.toSet() ?: emptySet()
    } catch (e: Throwable) { emptySet() }

    @PluginMethod
    fun bundled(call: PluginCall) {
        val ids = org.json.JSONArray()
        bundledIds().sorted().forEach { ids.put(it) }
        call.resolve(JSObject().put("ids", ids))
    }

    private val VOICE_BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"

    /** نصب صدا: از assets اگر همراه APK باشد، وگرنه دانلود مستقیم و استخراج هم‌زمان؛ سپس بررسی کامل‌بودن */
    @PluginMethod
    fun download(call: PluginCall) {
        val id = call.getString("id") ?: return call.reject("id")
        if (!Regex("[A-Za-z0-9_-]+").matches(id)) return call.reject("شناسهٔ صدا نامعتبر است")
        io.execute {
            var conn: java.net.HttpURLConnection? = null
            // نصب تکراری (مثلاً هم‌زمان با بارگذاری از پوشه): اگر صدا کامل نصب است کاری لازم نیست
            if (okMark(id).exists() && findOnnx(voiceDir(id)) != null) { call.resolve(); return@execute }
            try {
                // بدون کپی موقت: جریان asset مستقیم به bz2 و tar می‌رود (نصف فضا و زمان)؛ پیشرفت از روی بایت‌های خوانده‌شده
                // اگر صدا همراه APK بود از assets؛ وگرنه (حالت پیش‌فرض) بعد از نصب از اینترنت دانلود می‌شود
                val src: java.io.InputStream
                val total0: Long
                if (id in bundledIds()) {
                    total0 = try { context.assets.openFd("voices/$id.tar.bz2").use { it.length } } catch (e: Throwable) { 70_000_000L }
                    src = context.assets.open("voices/$id.tar.bz2")
                } else {
                    val c = java.net.URL("$VOICE_BASE/vits-piper-$id.tar.bz2").openConnection() as java.net.HttpURLConnection
                    conn = c
                    c.connectTimeout = 20_000; c.readTimeout = 30_000; c.instanceFollowRedirects = true
                    if (c.responseCode != 200) throw IllegalStateException("دانلود ناموفق (کد ${c.responseCode}) — اتصال اینترنت را بررسی کنید")
                    total0 = if (c.contentLength > 0) c.contentLength.toLong() else 70_000_000L
                    src = c.inputStream
                }
                if (ttsId == id) releaseEngine(id)
                val dst = voiceDir(id)
                dst.deleteRecursively(); dst.mkdirs()
                var loaded = 0L; var lastEmit = 0L
                val emit = { l: Long, t: Long -> notifyListeners("progress", JSObject().put("loaded", l).put("total", t)) }
                val counting = object : java.io.FilterInputStream(src) {
                    private fun tick(n: Long) {
                        loaded += n
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 250) { lastEmit = now; emit(loaded, Math.max(loaded + 1, total0)) }
                    }
                    override fun read(): Int { val b = super.read(); if (b >= 0) tick(1L); return b }
                    override fun read(b: ByteArray, off: Int, len: Int): Int { val n = super.read(b, off, len); if (n > 0) tick(n.toLong()); return n }
                }
                counting.use { extract(it, dst) }
                emit(loaded, loaded)
                if (findOnnx(dst) == null || !File(findRoot(dst), "tokens.txt").exists())
                    throw IllegalStateException("فایل‌های مدل کامل نیست")
                okMark(id).writeText("1")
                call.resolve()
            } catch (e: Throwable) {
                voiceDir(id).deleteRecursively()
                call.reject("نصب ناموفق: ${e.message}")
            } finally {
                try { conn?.disconnect() } catch (e: Throwable) {}
            }
        }
    }

    private fun extract(ins: java.io.InputStream, dst: File) {
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(ins, 1 shl 16))).use { tar ->
            while (true) {
                val e = tar.nextTarEntry ?: break
                val f = File(dst, e.name)
                if (!f.canonicalPath.startsWith(dst.canonicalPath + File.separator)) continue   // جلوگیری از path traversal
                if (e.isDirectory) { f.mkdirs(); continue }
                f.parentFile?.mkdirs()
                FileOutputStream(f).use { tar.copyTo(it) }
            }
        }
    }

    /** پوشه‌ای که tokens.txt در آن است (معمولاً dst/vits-piper-<id>/) */
    private fun findRoot(dir: File): File {
        if (File(dir, "tokens.txt").exists()) return dir
        dir.listFiles()?.filter { it.isDirectory }?.forEach { d -> if (File(d, "tokens.txt").exists()) return d }
        return dir
    }
    private fun findOnnx(dir: File): File? =
        findRoot(dir).listFiles()?.firstOrNull { it.name.endsWith(".onnx") }

    // ---------- بررسی سازگاری مدل با sherpa-onnx (جلوگیری از بسته‌شدن ناگهانی برنامه) ----------
    // اگر مدل متادیتای sherpa را نداشته باشد یا espeak لازم باشد و نباشد، خود sherpa برنامه را با exit می‌بندد
    // و try/catch جلوی آن را نمی‌گیرد؛ پس قبل از ساخت موتور خودمان بررسی می‌کنیم.
    private val flagCache = HashMap<String, Int>()

    private fun indexOf(h: ByteArray, end: Int, p: ByteArray): Int {
        var i = 0; val last = end - p.size
        while (i <= last) {
            var j = 0
            while (j < p.size && h[i + j] == p[j]) j++
            if (j == p.size) return i
            i++
        }
        return -1
    }

    /** بیت ۱: sample_rate  |  بیت ۲: characters  |  بیت ۴: piper یا coqui */
    private fun scanFlags(f: File, from: Long): Int {
        val pats = listOf("sample_rate", "characters", "piper", "coqui").map { it.toByteArray(Charsets.ISO_8859_1) }
        val bits = intArrayOf(1, 2, 4, 4)
        var res = 0
        java.io.RandomAccessFile(f, "r").use { raf ->
            raf.seek(from)
            val buf = ByteArray(1 shl 20)
            var carry = 0
            while (true) {
                val n = raf.read(buf, carry, buf.size - carry)
                if (n < 0) break
                val end = carry + n
                for (k in pats.indices) if (indexOf(buf, end, pats[k]) >= 0) res = res or bits[k]
                carry = minOf(16, end)
                System.arraycopy(buf, end - carry, buf, 0, carry)
            }
        }
        return res
    }

    private fun modelFlags(onnx: File): Int {
        val key = onnx.absolutePath + "|" + onnx.length()
        flagCache[key]?.let { return it }
        val len = onnx.length()
        val good = { x: Int -> (x and 1) != 0 && (x and 6) != 0 }
        var f = scanFlags(onnx, maxOf(0L, len - 4_000_000L))   // متادیتا معمولاً انتهای فایل است
        if (!good(f) && len > 4_000_000L) f = scanFlags(onnx, 0L)
        flagCache[key] = f
        return f
    }

    /** espeak-ng-data از یکی از صداهای دیگرِ دانلودشده (برای مدل‌هایی که خودشان ندارند) */
    private fun sharedEspeak(except: String): File? =
        root().listFiles()?.filter { it.isDirectory && it.name != voiceDir(except).name && File(it, ".ok").exists() }
            ?.mapNotNull { File(findRoot(it), "espeak-ng-data").takeIf { d -> d.isDirectory } }
            ?.firstOrNull()

    private fun engineFor(id: String, ns: Float, nw: Float): OfflineTts {
        val key = "$id|$ns|$nw"
        if (tts != null && ttsId == id && ttsKey == key) return tts!!
        tts?.release(); tts = null; ttsId = null; ttsKey = null
        val r = findRoot(voiceDir(id))
        val onnx = findOnnx(voiceDir(id)) ?: throw IllegalStateException("مدل پیدا نشد؛ دوباره نصب کنید")
        val flags = modelFlags(onnx)
        if ((flags and 1) == 0 || (flags and 6) == 0)
            throw IllegalStateException("این مدل برای sherpa-onnx آماده نشده (متادیتا ندارد) و باز کردنش برنامه را می‌بندد؛ مدل دیگری برای آن لازم است")
        val ownEspeak = File(r, "espeak-ng-data")
        val espeak: File? = if (ownEspeak.isDirectory) ownEspeak else sharedEspeak(id)
        // مدل‌های حرفی (characters) به espeak نیاز ندارند؛ بقیه حتماً لازم دارند
        if (((flags and 2) == 0 || (flags and 4) != 0) && espeak == null)
            throw IllegalStateException("این صدا به دادهٔ espeak نیاز دارد؛ اول یکی از صداهای دیگر (مثلاً gyro) را دانلود کنید")
        val vits = OfflineTtsVitsModelConfig(
            model = onnx.absolutePath,
            tokens = File(r, "tokens.txt").absolutePath,
            dataDir = espeak?.absolutePath ?: "",
            // noiseScale/noiseScaleW: کمتر = یکنواخت‌تر و پایدارتر، بیشتر = پرحالت‌تر ولی ناپایدارتر
            noiseScale = ns,
            noiseScaleW = nw,
        )
        val cfg = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = vits,
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
                debug = false,
                provider = "cpu",
            ),
            maxNumSentences = 1,
        )
        // assetManager = null → مدل از مسیر فایل خوانده می‌شود
        val t = OfflineTts(assetManager = null, config = cfg)
        tts = t; ttsId = id; ttsKey = key; sampleRate = t.sampleRate()
        return t
    }

    // ---------- حافظهٔ نهان صدا روی دیسک ----------
    // هر «جمله» با کلید (صدا + پارامترها + سرعت + متن) جدا ذخیره می‌شود، نه کل تکه؛ پس مرز تکه‌ها فرقی نمی‌کند و
    // باز کردن دوبارهٔ کتاب (یا برگشتن به صفحه) بدون ساخت دوباره و فوری می‌شود. قالب فایل: [sampleRate:Int][PCM 16 بیتی].
    private val epoch = AtomicInteger(0)           // با cancel() بالا می‌رود؛ کارهای با epoch قدیمی رها می‌شوند
    private val cacheLock = Any()
    private var cacheBytes = -1L

    private fun cacheRoot(): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "tts-cache").also { it.mkdirs() }
    private fun cacheMax(): Long = prefs.getLong("cache_max", 600L * 1024L * 1024L)
    private fun cacheFiles(): List<File> = cacheRoot().listFiles()?.filter { it.name.endsWith(".pcm") } ?: emptyList()
    private fun cacheFile(key: String) = File(cacheRoot(), "$key.pcm")
    private fun hashKey(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun cacheGet(key: String, sr: Int): FloatArray? {
        val f = cacheFile(key)
        if (!f.isFile || f.length() < 8) return null
        return try {
            val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            if (b.getInt() != sr) return null
            val n = b.remaining() / 2
            val out = FloatArray(n)
            for (i in 0 until n) out[i] = b.getShort() / 32768f
            f.setLastModified(System.currentTimeMillis())   // برای حذف قدیمی‌ترین‌ها (LRU)
            out
        } catch (e: Throwable) { null }
    }

    private fun cachePut(key: String, sr: Int, f: FloatArray) {
        try {
            val bb = ByteBuffer.allocate(4 + f.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(sr)
            for (x in f) bb.putShort(Math.round(x.coerceIn(-1f, 1f) * 32767f).toShort())
            val dst = cacheFile(key)
            val tmp = File(dst.path + ".tmp")
            tmp.writeBytes(bb.array())
            if (!tmp.renameTo(dst)) { tmp.delete(); return }
            synchronized(cacheLock) { if (cacheBytes >= 0) cacheBytes += dst.length(); trimCache() }
        } catch (e: Throwable) { /* نبود جا یا خطای دیسک نباید خواندن را متوقف کند */ }
    }

    /** باید زیر cacheLock صدا زده شود: وقتی از سقف گذشت، قدیمی‌ترین فایل‌ها تا ۸۵٪ سقف حذف می‌شوند */
    private fun trimCache() {
        if (cacheBytes < 0) cacheBytes = cacheFiles().sumOf { it.length() }
        val max = cacheMax()
        if (cacheBytes <= max) return
        var total = cacheBytes
        val goal = (max * 0.85).toLong()
        for (f in cacheFiles().sortedBy { it.lastModified() }) {
            if (total <= goal) break
            val l = f.length()
            if (f.delete()) total -= l
        }
        cacheBytes = total
    }

    /** ساخت یک جمله با تولید جریانی (callback): با لغو، همان لحظه‌ای که تکهٔ جاری برسد ساخت قطع می‌شود */
    private fun genPart(eng: OfflineTts, text: String, sp: Float, ep: Int): FloatArray {
        val acc = ArrayList<FloatArray>()
        val a = eng.generateWithCallback(text = text, sid = 0, speed = sp, callback = { s: FloatArray ->
            acc.add(s.copyOf())
            if (ep != epoch.get()) 0 else 1      // ۰ = توقف
        })
        if (ep != epoch.get()) throw IllegalStateException("NATIVE_CANCELLED")
        if (a.samples.isNotEmpty()) return a.samples
        var n = 0
        for (x in acc) n += x.size
        val out = FloatArray(n)
        var p = 0
        for (x in acc) { System.arraycopy(x, 0, out, p, x.size); p += x.size }
        return out
    }

    @PluginMethod
    fun cancel(call: PluginCall) {
        epoch.incrementAndGet()
        call.resolve()
    }

    @PluginMethod
    fun cacheInfo(call: PluginCall) {
        io.execute {
            try {
                val fs = cacheFiles()
                val total = fs.sumOf { it.length() }
                synchronized(cacheLock) { cacheBytes = total }
                call.resolve(JSObject().put("bytes", total).put("files", fs.size).put("max", cacheMax()))
            } catch (e: Throwable) { call.reject("خطا: ${e.message}") }
        }
    }

    @PluginMethod
    fun cacheClear(call: PluginCall) {
        io.execute {
            try {
                synchronized(cacheLock) { cacheRoot().listFiles()?.forEach { it.delete() }; cacheBytes = 0L }
                call.resolve()
            } catch (e: Throwable) { call.reject("خطا: ${e.message}") }
        }
    }

    @PluginMethod
    fun cacheLimit(call: PluginCall) {
        val mb = call.getInt("mb") ?: return call.reject("mb")
        prefs.edit().putLong("cache_max", mb.coerceIn(50, 20000) * 1024L * 1024L).apply()
        io.execute { synchronized(cacheLock) { trimCache() } }
        call.resolve()
    }

    @PluginMethod
    fun synthesize(call: PluginCall) {
        val id = call.getString("id") ?: return call.reject("id")
        val text = call.getString("text") ?: return call.reject("text")
        if (text.isBlank()) return call.reject("متن خالی است")
        val ns = (call.getFloat("noiseScale") ?: 0.667f).coerceIn(0.2f, 1.2f)
        val nw = (call.getFloat("noiseScaleW") ?: 0.8f).coerceIn(0.2f, 1.2f)
        val sp = (call.getFloat("speed") ?: 1.0f).coerceIn(0.6f, 1.6f)   // <1 کندتر (سرعت هوشمند)
        val gap = (call.getFloat("gap") ?: 0.12f).coerceIn(0f, 0.6f)      // ثانیه، سکوت بین جمله‌ها
        val partsArr = call.getArray("parts")
        val lvlClean = (call.getInt("clean") ?: 2).coerceIn(0, 3)       // حذف نویز و صداهای اضافه
        val lvlClarity = (call.getInt("clarity") ?: 2).coerceIn(0, 3)   // شفافیت و رسایی
        val keep = call.getBoolean("keep") ?: false                      // true: PCM در حافظهٔ بومی می‌ماند و فقط handle برمی‌گردد (پخش بومی)
        val fxOn = call.getBoolean("master") ?: false                    // زنجیرهٔ EQ + کمپرسور + لیمیتر (MasterFx)
        val fxPk = (call.getFloat("fxPk") ?: 2f).coerceIn(-6f, 8f)
        val fxHs = (call.getFloat("fxHs") ?: 0.8f).coerceIn(-6f, 8f)
        val fxLp = (call.getFloat("fxLp") ?: 10500f).coerceIn(4000f, 20000f)
        val ep = epoch.get()   // لحظهٔ رسیدن درخواست؛ اگر بعدش cancel بیاید این کار رها می‌شود
        cpu.execute {
            try {
                if (ep != epoch.get()) throw IllegalStateException("NATIVE_CANCELLED")
                if (!okMark(id).exists()) throw IllegalStateException("این صدا هنوز نصب نشده است؛ چند لحظه صبر کنید تا نصب تمام شود")
                val eng = engineFor(id, ns, nw)
                val sr = eng.sampleRate()
                // هر جمله جدا (برای کش)؛ بدون parts کل متن یک جمله حساب می‌شود
                val items = ArrayList<Pair<String, Float>>()
                if (partsArr != null && partsArr.length() > 0) {
                    for (i in 0 until partsArr.length()) {
                        val o = partsArr.getJSONObject(i)
                        items.add(Pair(o.optString("t", ""), o.optDouble("s", sp.toDouble()).toFloat().coerceIn(0.6f, 1.6f)))
                    }
                } else items.add(Pair(text, sp))
                val pieces = ArrayList<FloatArray>()
                var genMs = 0L
                var hit = 0
                for ((pt, ps) in items) {
                    if (pt.isBlank() || pt.none { it.isLetterOrDigit() }) continue
                    val key = hashKey("v1|$id|$ns|$nw|" + String.format(java.util.Locale.US, "%.2f", ps) + "|" + pt)
                    val cached = cacheGet(key, sr)
                    val s = if (cached != null) { hit++; cached } else {
                        val t0 = System.nanoTime()
                        val g = genPart(eng, pt, ps, ep)
                        genMs += (System.nanoTime() - t0) / 1_000_000L   // زمان خالص مدل (بدون صیقل و انتقال)
                        cachePut(key, sr, g)
                        g
                    }
                    pieces.add(SpeechClean.process(s, sr, lvlClean, lvlClarity))   // روی نسخهٔ خام کش؛ خودِ کش دست‌نخورده می‌ماند
                }
                if (pieces.isEmpty()) throw IllegalStateException("متن قابل‌خواندن ندارد")
                val gapN = (gap * sr).toInt()
                var total = 0
                for (x in pieces) total += x.size
                total += gapN * (pieces.size - 1)
                val all = FloatArray(total)
                var pos = 0
                for ((k, x) in pieces.withIndex()) {
                    System.arraycopy(x, 0, all, pos, x.size); pos += x.size
                    if (k < pieces.size - 1) pos += gapN   // سکوت (صفر)
                }
                val pol = polish(all, sr)
                val fin = if (fxOn) MasterFx.process(pol, sr, fxPk, fxHs, fxLp) else pol
                if (keep) {
                    val h = pcmPut(fin, sr)
                    call.resolve(JSObject().put("handle", h).put("sampleRate", sr).put("durationMs", fin.size * 1000L / sr).put("genMs", genMs).put("hit", hit).put("parts", pieces.size))
                } else {
                    val wav = toWav(fin, sr)
                    call.resolve(JSObject().put("wav", Base64.encodeToString(wav, Base64.NO_WRAP)).put("sampleRate", sr).put("genMs", genMs).put("hit", hit).put("parts", pieces.size))
                }
            } catch (e: Throwable) {
                call.reject(if (e.message == "NATIVE_CANCELLED") "NATIVE_CANCELLED" else "خطای موتور بومی: ${e.message}")
            }
        }
    }

    /** صیقل خروجی هر تکه: حذف سکوت اضافهٔ ابتدا/انتها، محوشدن کوتاه (جلوگیری از «تق» بین جمله‌ها)، یکنواخت‌کردن بلندی صدا با محدودکنندهٔ نرم */
    private fun polish(f: FloatArray, sr: Int): FloatArray {
        if (f.size < sr / 5) return f
        var peak = 0f
        for (x in f) { val a = kotlin.math.abs(x); if (a > peak) peak = a }
        if (peak < 1e-3f) return f
        // آستانهٔ پایین: واج‌های سایشی (س، ش، ف، خ) کم‌انرژی‌اند و با آستانهٔ بالا از اول/آخر کلمه بریده می‌شدند
        val thr = kotlin.math.max(peak * 0.012f, 0.0015f)
        var s = 0
        while (s < f.size && kotlin.math.abs(f[s]) < thr) s++
        var e = f.size - 1
        while (e > s && kotlin.math.abs(f[e]) < thr) e--
        if (e - s < sr / 10) return f
        // ابتدا ۴۵ میلی‌ثانیه و انتها ۷۰ میلی‌ثانیه سکوت طبیعی نگه داشته می‌شود (مکث بین جمله‌ها را خود برنامه می‌گذارد)
        s = kotlin.math.max(0, s - (sr * 0.045).toInt())
        e = kotlin.math.min(f.size - 1, e + (sr * 0.07).toInt())
        val out = f.copyOfRange(s, e + 1)
        // بلندی یکنواخت: بر پایهٔ RMS نمونه‌های غیرسکوت؛ قله‌های اضافه با محدودکنندهٔ نرم (tanh) و نه بریدن سخت مهار می‌شوند
        var sum = 0.0; var cnt = 0; var pk = 0f
        for (x in out) {
            val a = kotlin.math.abs(x); if (a > pk) pk = a
            if (a >= thr) { sum += x.toDouble() * x; cnt++ }
        }
        if (cnt > 0) {
            val rms = kotlin.math.sqrt(sum / cnt).toFloat()
            if (rms > 1e-4f) {
                var g = (0.16f / rms).coerceIn(0.7f, 4f)
                if (pk * g > 1.6f) g = 1.6f / pk
                val knee = 0.8f; val room = 0.19f
                for (i in out.indices) {
                    val y = out[i] * g
                    val ay = kotlin.math.abs(y)
                    out[i] = if (ay <= knee) y else {
                        val c = knee + room * kotlin.math.tanh((ay - knee) / room)
                        if (y < 0f) -c else c
                    }
                }
            }
        }
        val fi = (sr * 0.006).toInt().coerceAtMost(out.size / 4)
        val fo = (sr * 0.02).toInt().coerceAtMost(out.size / 4)
        for (i in 0 until fi) out[i] *= i / fi.toFloat()
        for (i in 0 until fo) out[out.size - 1 - i] *= i / fo.toFloat()
        return out
    }

    private fun toWav(f: FloatArray, sr: Int): ByteArray {
        val n = f.size * 2
        val bb = ByteBuffer.allocate(44 + n).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + n); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1)
        bb.putInt(sr); bb.putInt(sr * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(n)
        for (x in f) bb.putShort(Math.round(x.coerceIn(-1f, 1f) * 32767f).toShort())
        return bb.array()
    }

    // ---------- پخش بومی (AudioTrack) ----------
    private class Pcm(val s: ShortArray, val sr: Int)
    private class PlItem(val h: Int, val pcm: Pcm, val gapMs: Int)

    private val pcmStore = java.util.concurrent.ConcurrentHashMap<Int, Pcm>()
    private val pcmIds = java.util.concurrent.ConcurrentLinkedQueue<Int>()
    private val pcmSeq = AtomicInteger(0)
    private val plEx = Executors.newSingleThreadExecutor()
    private val plLock = Any()
    @Volatile private var plGen = 0
    @Volatile private var plRate = 1f
    @Volatile private var plTrack: AudioTrack? = null
    private var plNext: PlItem? = null
    private var plActive = false
    @Volatile private var plRateAt = 0L                                  // لحظهٔ آخرین تغییر سرعت (برای debounce)
    private val stEx = Executors.newSingleThreadExecutor()               // محاسبهٔ WSOLA برای تغییر سرعت وسط پخش، جدا از رشتهٔ پخش
    private var noisyRx: android.content.BroadcastReceiver? = null
    private fun effRate(r: Float): Float = if (Math.abs(r - 1f) < 0.004f) 1f else r

    private fun pcmPut(f: FloatArray, sr: Int): Int {
        val s = ShortArray(f.size) { Math.round(f[it].coerceIn(-1f, 1f) * 32767f).toShort() }
        val h = pcmSeq.incrementAndGet()
        pcmStore[h] = Pcm(s, sr); pcmIds.add(h)
        var tot = 0L
        for (p in pcmStore.values) tot += p.s.size * 2L
        while (tot > 96L * 1024L * 1024L) {   // سقف حافظه: قدیمی‌ترین‌ها کنار می‌روند
            val old = pcmIds.poll() ?: break
            val p = pcmStore.remove(old)
            if (p != null) tot -= p.s.size * 2L
        }
        return h
    }

    @Suppress("DEPRECATION")
    private fun makeTrack(sr: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val size = Math.max(if (min > 0) min else 0, sr / 4 * 2)   // ≈ ۲۵۰ms: هم روان، هم رویدادها نزدیک به صدای واقعی
        return if (android.os.Build.VERSION.SDK_INT >= 23) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setBufferSizeInBytes(size).setTransferMode(AudioTrack.MODE_STREAM).build()
        } else {
            AudioTrack(AudioManager.STREAM_MUSIC, sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, size, AudioTrack.MODE_STREAM)
        }
    }

    private fun emitPl(ev: String, h: Int, p: Double = -1.0, msg: String? = null) {
        val o = JSObject().put("h", h)
        if (p >= 0) o.put("p", p)
        if (msg != null) o.put("msg", msg)
        notifyListeners(ev, o)
    }

    private fun takeNext(): PlItem? = synchronized(plLock) { val n = plNext; plNext = null; n }

    /** پایان صف: اگر هم‌زمان آیتمی نرسیده باشد پخش «غیرفعال» می‌شود (true = پایان واقعی) */
    private fun closeIfIdle(gen: Int): Boolean = synchronized(plLock) {
        if (plNext == null && gen == plGen) { plActive = false; true } else false
    }

    /** تماس تلفنی یا زنگ‌خوردن (فقط حالت‌های تلفن همراه؛ حالت‌های VoIP عمداً بررسی نمی‌شود تا برنامهٔ دیگری که حالت را رها کرده پخش را قفل نکند) */
    private fun inCall(): Boolean = try {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val m = am.mode
        m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_RINGTONE
    } catch (e: Throwable) { false }

    private fun emitInterrupt(reason: String) {
        notifyListeners("playerInterrupted", JSObject().put("reason", reason))
    }

    /** توقف از داخل رشتهٔ پخش (مثلاً تماس)؛ فقط اگر هنوز همان نوبت پخش باشد */
    private fun interrupt(gen: Int, reason: String) {
        if (gen != plGen) return
        stopPlayer()
        emitInterrupt(reason)
    }

    /** جداشدن هدفون/بلوتوث: صدا ناگهان از بلندگو پخش نشود */
    private fun ensureNoisyReceiver() {
        if (noisyRx != null) return
        try {
            val rx = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    if (i?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
                    val act = synchronized(plLock) { plActive }
                    if (act) { stopPlayer(); emitInterrupt("noisy") }
                }
            }
            val f = android.content.IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (android.os.Build.VERSION.SDK_INT >= 33) context.registerReceiver(rx, f, 0x4)   // 0x4 = RECEIVER_NOT_EXPORTED (عدد ثابت تا با compileSdk پایین‌تر هم بیلد شود)
            else context.registerReceiver(rx, f)
            noisyRx = rx
        } catch (e: Throwable) { /* بدون گیرنده: فقط اعلان جداشدن هدفون نمی‌رسد */ }
    }

    private fun stopPlayer() {
        var old: AudioTrack? = null
        synchronized(plLock) { plGen++; plNext = null; plActive = false; old = plTrack; plTrack = null }
        try { old?.pause(); old?.flush() } catch (e: Throwable) {}
    }

    private fun runPlayer(gen: Int, first: PlItem) {
        var track: AudioTrack? = null
        var cur = first
        try {
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (e: Throwable) {}
            if (gen != plGen) return
            val sr = first.pcm.sr
            val t = makeTrack(sr)
            track = t
            synchronized(plLock) { if (gen == plGen) plTrack = t }
            if (gen != plGen) return
            t.play()
            val base = t.playbackHeadPosition
            var written = 0L
            var lastU = 0
            val step = Math.max(256, sr / 50)
            val zeros = ShortArray(step)
            var item = first
            while (true) {
                cur = item
                var g = item.gapMs.toLong() * sr / 1000L
                while (g > 0) {   // سکوت میان تکه‌ها (مکث جمله/بند)
                    if (gen != plGen) return
                    val n = Math.min(g, step.toLong()).toInt()
                    val w = t.write(zeros, 0, n)
                    if (w < 0) throw IllegalStateException("write $w")
                    if (w == 0) Thread.sleep(2)
                    written += w; g -= w
                }
                if (gen != plGen) return
                emitPl("playerStart", item.h)
                val src = item.pcm.s
                var rate = effRate(plRate)
                var srcBase = 0
                var out = Stretch.run(src, srcBase, rate, sr)
                var pos = 0
                var lastEv = 0L
                var lastMode = 0L
                var retryAt = 0L
                var pend: java.util.concurrent.Future<ShortArray>? = null
                var pendRate = 1f
                var pendSrc = 0
                while (pos < out.size) {
                    if (gen != plGen) { pend?.cancel(true); return }
                    val nowT = android.os.SystemClock.elapsedRealtime()
                    // تماس تلفنی یا زنگ: پخش مکث می‌شود (کنترل روی رشتهٔ پخش؛ بدون درگیری با موسیقی داخل WebView)
                    if (nowT - lastMode >= 250) {
                        lastMode = nowT
                        if (inCall()) { pend?.cancel(true); interrupt(gen, "call"); return }
                    }
                    // تغییر سرعت وسط پخش: محاسبه در رشتهٔ جدا و بعد از ۱۶۰ms ثبات؛ تا آماده‌شدن، پخش با سرعت قبلی ادامه دارد،
                    // و اتصال با crossfade ۸ms انجام می‌شود (بدون لکنت و «تق» هنگام نگه‌داشتن + یا −)
                    val pf = pend
                    if (pf != null) {
                        if (pf.isDone) {
                            pend = null
                            val nw: ShortArray? = try { pf.get() } catch (e: Throwable) { null }
                            var swapped = false
                            if (nw != null) {
                                val curSrc = Math.min(src.size, srcBase + (pos * rate).toInt())
                                val skip = Math.max(0, ((curSrc - pendSrc) / pendRate).toInt())
                                if (skip < nw.size) {
                                    val x = Math.min(Math.min(out.size - pos, nw.size - skip), (sr * 0.008).toInt())
                                    val merged = ShortArray(nw.size - skip)
                                    for (k in 0 until x) {
                                        val f = k / x.toFloat()
                                        merged[k] = (out[pos + k] * (1f - f) + nw[skip + k] * f).toInt().toShort()
                                    }
                                    System.arraycopy(nw, skip + x, merged, x, nw.size - skip - x)
                                    out = merged; srcBase = curSrc; rate = pendRate; pos = 0
                                    swapped = true
                                }
                            }
                            if (swapped) continue
                            retryAt = nowT + 1000   // نتیجه کنار گذاشته شد؛ بلافاصله دوباره تلاش نشود
                        }
                    } else if (nowT >= retryAt) {
                        val r2 = effRate(plRate)
                        if (Math.abs(r2 - rate) > 0.004f && nowT - plRateAt >= 160) {
                            pendRate = r2
                            pendSrc = Math.min(src.size, srcBase + (pos * rate).toInt())
                            val ps = pendSrc
                            pend = stEx.submit(java.util.concurrent.Callable<ShortArray> { Stretch.run(src, ps, r2, sr) })
                        }
                    }
                    val n = Math.min(step, out.size - pos)
                    val w = t.write(out, pos, n)
                    if (w < 0) throw IllegalStateException("write $w")
                    if (w == 0) Thread.sleep(2)
                    pos += w; written += w
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastEv >= 100) {
                        lastEv = now
                        emitPl("playerPos", item.h, Math.min(1.0, (srcBase + pos * rate.toDouble()) / Math.max(1, src.size)))
                    }
                }
                pend?.cancel(true)
                if (android.os.Build.VERSION.SDK_INT >= 24) { val u = t.underrunCount; underruns += u - lastU; lastU = u }
                var nx: PlItem? = takeNext()
                while (nx == null) {   // تا خالی‌شدن بافر منتظر آیتم بعدی می‌مانیم
                    if (gen != plGen) return
                    if (t.playbackHeadPosition - base >= written) break
                    Thread.sleep(8)
                    nx = takeNext()
                }
                if (nx == null) {
                    if (closeIfIdle(gen)) { emitPl("playerEnd", item.h); return }
                    nx = takeNext()
                }
                item = nx ?: return
            }
        } catch (e: Throwable) {
            if (gen == plGen) {
                synchronized(plLock) { plActive = false }
                emitPl("playerError", cur.h, -1.0, e.message ?: "player")
            }
        } finally {
            try { track?.pause(); track?.flush(); track?.release() } catch (e: Throwable) {}
            synchronized(plLock) { if (plTrack === track) plTrack = null }
        }
    }

    @PluginMethod
    fun playerPlay(call: PluginCall) {
        val h = call.getInt("handle") ?: return call.reject("handle")
        val pcm = pcmStore[h] ?: return call.reject("NO_HANDLE")
        val rate = (call.getFloat("rate") ?: 1f).coerceIn(0.4f, 2.5f)
        var gen = 0
        var old: AudioTrack? = null
        synchronized(plLock) {
            plGen++; gen = plGen; plRate = rate; plNext = null; plActive = true
            old = plTrack; plTrack = null
        }
        try { old?.pause(); old?.flush() } catch (e: Throwable) {}
        val g = gen
        ensureNoisyReceiver()
        plEx.execute { runPlayer(g, PlItem(h, pcm, 0)) }
        call.resolve()
    }

    @PluginMethod
    fun playerQueue(call: PluginCall) {
        val h = call.getInt("handle") ?: return call.reject("handle")
        val pcm = pcmStore[h] ?: return call.reject("NO_HANDLE")
        val gap = (call.getInt("gapMs") ?: 0).coerceIn(0, 3000)
        var ok = false
        synchronized(plLock) { if (plActive) { plNext = PlItem(h, pcm, gap); ok = true } }
        call.resolve(JSObject().put("queued", ok))
    }

    @PluginMethod
    fun playerRate(call: PluginCall) {
        plRate = (call.getFloat("rate") ?: 1f).coerceIn(0.4f, 2.5f)
        plRateAt = android.os.SystemClock.elapsedRealtime()
        call.resolve()
    }

    @PluginMethod
    fun playerStop(call: PluginCall) {
        stopPlayer()
        call.resolve()
    }

    @PluginMethod
    fun release(call: PluginCall) {
        if (call.getBoolean("all", false) == true) { pcmStore.clear(); pcmIds.clear() }
        else { val h = call.getInt("handle"); if (h != null) pcmStore.remove(h) }
        call.resolve()
    }

    override fun handleOnDestroy() {
        try { stopPlayer(); plEx.shutdownNow(); stEx.shutdownNow() } catch (e: Throwable) {}
        try { noisyRx?.let { context.unregisterReceiver(it) } } catch (e: Throwable) {}
        noisyRx = null
        try { if (wl?.isHeld == true) wl?.release() } catch (e: Throwable) {}
        cpu.execute { try { tts?.release() } catch (e: Throwable) {}; tts = null; ttsId = null; ttsKey = null }
        cpu.shutdown(); io.shutdown()
    }
}

/**
 * پاک‌سازی و ارتقای تخصصی خروجی خام مدل (هر جمله جدا و بعد از خواندن از کش؛ پس کش خام دست‌نخورده می‌ماند).
 * همه‌چیز روی یک STFT (پنجرهٔ ~۲۳ms، هم‌پوشانی ۷۵٪) انجام می‌شود:
 *  حذف نویز (clean):
 *   ۱) بالاگذر صفرفاز ۵۰Hz: حذف DC و غرّش
 *   ۲) Wiener تصمیم‌محور: نویز زمینه از ساکت‌ترین فریم‌های همان جمله برآورد می‌شود و فقط از فرکانس‌هایی کم می‌شود که در حد نویزند
 *   ۳) دروازهٔ نرم مکث‌ها: در فریم‌های بدون گفتار (ابتدا/انتها/مکث) نفس، «تق» و دمِ مدل بیشتر کم می‌شود؛ ورود گفتار با پیش‌نگری باز می‌شود
 *   ۴) محوشدن کوتاه لبه‌های جمله (حذف «تق» مرز)
 *  ارتقای شفافیت و رسایی (clarity):
 *   ۵) تراز صامت‌ها: صامت‌های کم‌صدا (ف، ه، ث، ح…) تا ۴dB بالا می‌آیند و سایشی‌های تند (س، ش) کمی نرم می‌شوند
 *   ۶) تراز آرام واکه‌ها: پایان کلمه‌های کم‌صدا و واکه‌های ضعیف کمی (تا ~۳٫۵dB) رساتر؛ واکه‌های بسیار بلند کمی (تا ~۲٫۵dB) کمتر
 *  اگر در جمله سکوتِ قابل‌اعتماد نباشد، بخش حذف نویز اصلاً اعمال نمی‌شود (جلوگیری از آسیب به گفتار).
 * clean و clarity: ۰ خاموش | ۱ ملایم | ۲ متعادل | ۳ قوی. هر دو ۰ = دقیقاً خروجی خام.
 */
private object SpeechClean {
    private class Fft(private val n: Int) {
        private val cosT = DoubleArray(n / 2) { Math.cos(2.0 * Math.PI * it / n) }
        private val sinT = DoubleArray(n / 2) { Math.sin(2.0 * Math.PI * it / n) }
        private val rev = IntArray(n)
        init {
            var bits = 0
            while ((1 shl bits) < n) bits++
            for (i in 0 until n) {
                var r = 0
                var v = i
                for (b in 0 until bits) { r = (r shl 1) or (v and 1); v = v shr 1 }
                rev[i] = r
            }
        }
        fun forward(re: DoubleArray, im: DoubleArray) {
            for (i in 0 until n) {
                val j = rev[i]
                if (j > i) {
                    val tr = re[i]; re[i] = re[j]; re[j] = tr
                    val ti = im[i]; im[i] = im[j]; im[j] = ti
                }
            }
            var size = 2
            while (size <= n) {
                val half = size / 2
                val step = n / size
                var start = 0
                while (start < n) {
                    for (k in 0 until half) {
                        val c = cosT[k * step]
                        val s = sinT[k * step]
                        val a = start + k
                        val b = a + half
                        val tr = re[b] * c + im[b] * s
                        val ti = im[b] * c - re[b] * s
                        re[b] = re[a] - tr; im[b] = im[a] - ti
                        re[a] += tr; im[a] += ti
                    }
                    start += size
                }
                size *= 2
            }
        }
    }

    private var fft: Fft? = null
    private var fftN = 0
    private fun fftFor(n: Int): Fft {
        val f = fft
        if (f != null && fftN == n) return f
        val g = Fft(n)
        fft = g; fftN = n
        return g
    }

    @Synchronized
    fun process(src: FloatArray, sr: Int, clean: Int, clarity: Int): FloatArray {
        if ((clean <= 0 && clarity <= 0) || src.size < sr / 20) return src
        var x = src.copyOf()
        try {
            if (clean > 0) highpass(x, sr, 50.0)
            if (x.size >= sr / 4 && x.size <= sr * 45) {
                val cleanDb = when (clean) { 1 -> 8.0; 2 -> 12.0; 3 -> 18.0; else -> 0.0 }
                val beta = when (clean) { 1 -> 1.0; 3 -> 1.5; else -> 1.2 }
                val silDb = when (clean) { 1 -> 3.0; 2 -> 6.0; 3 -> 10.0; else -> 0.0 }
                val cl = when (clarity) { 1 -> 0.6; 2 -> 1.0; 3 -> 1.4; else -> 0.0 }
                x = enhance(x, sr, cleanDb, beta, silDb, cl)
            }
        } catch (e: Throwable) {
            return src   // هر خطای پیش‌بینی‌نشده: صدای اصلی بدون پردازش
        }
        if (clean > 0) {
            // محوشدن کوتاه (کسینوسی) فقط در لبهٔ جمله، که معمولاً بخش ساکت است
            val fi = minOf((sr * 0.006).toInt(), x.size / 4)
            val fo = minOf((sr * 0.012).toInt(), x.size / 4)
            for (i in 0 until fi) x[i] = (x[i] * (0.5 - 0.5 * Math.cos(Math.PI * i / fi))).toFloat()
            for (i in 0 until fo) { val k = x.size - 1 - i; x[k] = (x[k] * (0.5 - 0.5 * Math.cos(Math.PI * i / fo))).toFloat() }
        }
        return x
    }

    /** بالاگذر مرتبهٔ ۲ باترورث، یک بار رو به جلو و یک بار رو به عقب (صفرفاز) */
    private fun highpass(x: FloatArray, sr: Int, fc: Double) {
        val w0 = 2.0 * Math.PI * fc / sr
        val cw = Math.cos(w0)
        val al = Math.sin(w0) / (2.0 * 0.70710678)
        val a0 = 1.0 + al
        val b0 = (1.0 + cw) / 2.0 / a0
        val b1 = -(1.0 + cw) / a0
        val b2 = b0
        val a1 = -2.0 * cw / a0
        val a2 = (1.0 - al) / a0
        var z1 = 0.0
        var z2 = 0.0
        for (i in 0 until x.size) {
            val v = x[i].toDouble()
            val o = b0 * v + z1
            z1 = b1 * v - a1 * o + z2
            z2 = b2 * v - a2 * o
            x[i] = o.toFloat()
        }
        z1 = 0.0; z2 = 0.0
        for (i in x.size - 1 downTo 0) {
            val v = x[i].toDouble()
            val o = b0 * v + z1
            z1 = b1 * v - a1 * o + z2
            z2 = b2 * v - a2 * o
            x[i] = o.toFloat()
        }
    }

    /** هموارسازی یک‌قطبی رو به جلو و رو به عقب (بدون تأخیر) */
    private fun smoothZp(v: DoubleArray, coef: Double) {
        for (m in 1 until v.size) v[m] = v[m - 1] + coef * (v[m] - v[m - 1])
        for (m in v.size - 2 downTo 0) v[m] = v[m + 1] + coef * (v[m] - v[m + 1])
    }

    private fun enhance(x: FloatArray, sr: Int, cleanDb: Double, beta: Double, silDb: Double, cl: Double): FloatArray {
        val n = x.size
        var nn = 256
        while (nn < sr * 0.0232) nn *= 2
        val hop = nn / 4
        val half = nn / 2
        val bins = half + 1
        val pad = nn
        val xp = DoubleArray(n + 2 * pad)
        for (i in 0 until n) xp[pad + i] = x[i].toDouble()
        val frames = (xp.size - nn) / hop + 1
        if (frames < 8) return x
        val ft = fftFor(nn)
        val win = DoubleArray(nn) { Math.sqrt(0.5 - 0.5 * Math.cos(2.0 * Math.PI * it / nn)) }
        val re = DoubleArray(nn)
        val im = DoubleArray(nn)

        // گذر ۱: توان هر فریم
        val pw = DoubleArray(frames * bins)
        val e = DoubleArray(frames)
        for (m in 0 until frames) {
            val o = m * hop
            for (i in 0 until nn) { re[i] = xp[o + i] * win[i]; im[i] = 0.0 }
            ft.forward(re, im)
            var s = 0.0
            for (k in 0 until bins) {
                val p = re[k] * re[k] + im[k] * im[k]
                pw[m * bins + k] = p
                if (k in 1 until half) s += p
            }
            e[m] = s
        }

        // برآورد نویز: فریم‌هایی که انرژی‌شان تا ۶dB بالاتر از ساکت‌ترین فریم‌هاست
        val order = (0 until frames).sortedBy { e[it] }
        val nb = minOf(frames, maxOf(3, (frames * 0.03).toInt()))
        var base = 0.0
        for (i in 0 until nb) base += e[order[i]]
        base /= nb
        val kMax = maxOf(3, (frames * 0.25).toInt())
        val sel = ArrayList<Int>()
        for (i in 0 until minOf(kMax, frames)) { val m = order[i]; if (e[m] <= base * 4.0) sel.add(m) }
        if (sel.size < 3) { sel.clear(); for (i in 0 until 3) sel.add(order[i]) }
        val raw = DoubleArray(bins)
        for (m in sel) for (k in 0 until bins) raw[k] += pw[m * bins + k]
        for (k in 0 until bins) raw[k] /= sel.size.toDouble()
        val kHi = minOf(frames, maxOf(3, (frames * 0.4).toInt()))
        var speech = 0.0
        for (i in 0 until kHi) speech += e[order[frames - 1 - i]]
        speech /= kHi
        var en = 0.0
        for (k in 1 until half) en += raw[k]
        if (speech < 1e-9) return x     // بی‌صدا
        // مرجع نویز قابل‌اعتماد نیست (سکوتی در جمله نیست): حذف نویز و دروازه اعمال نمی‌شود
        val reliable = en <= speech * Math.pow(10.0, -2.5)
        if (!reliable && cl <= 0.0) return x
        val noiseRaw = DoubleArray(bins)
        for (k in 0 until bins) {
            val a = maxOf(0, k - 2)
            val b = minOf(half, k + 2)
            var s = 0.0
            for (j in a..b) s += raw[j]
            noiseRaw[k] = maxOf(s / (b - a + 1), 1e-12)
        }
        val noise = DoubleArray(bins) { noiseRaw[it] * beta }
        var enN = 0.0
        if (reliable) { for (k in 1 until half) enN += noiseRaw[k] } else enN = speech * Math.pow(10.0, -3.5)

        // حضور گفتار در هر فریم (۰ تا ۱): از ۳dB تا ۹dB بالاتر از نویز؛ با پیش‌نگری ۳ فریم و رهایی آهسته
        val sp = DoubleArray(frames)
        for (m in 0 until frames) sp[m] = ((10.0 * Math.log10((e[m] + 1e-20) / (enN + 1e-20)) - 3.0) / 6.0).coerceIn(0.0, 1.0)
        val spS = DoubleArray(frames)
        for (m in 0 until frames) {
            var mx = sp[m]
            for (j in 1..3) if (m + j < frames && sp[m + j] > mx) mx = sp[m + j]
            spS[m] = if (m > 0) maxOf(mx, spS[m - 1] * 0.9) else mx
        }

        // ارتقای شفافیت و رسایی: بهرهٔ هر فریم از روی تراز باندها
        val bC = DoubleArray(frames)    // dB برای فرکانس‌های بالا (صامت‌ها)
        val rD = DoubleArray(frames)    // dB پهن‌باند (واکه‌ها)
        val df = sr.toDouble() / nn
        val k1 = Math.round(150.0 / df).toInt()
        val k2 = Math.round(1000.0 / df).toInt()
        val k3 = Math.round(2500.0 / df).toInt()
        val k4 = minOf(half, Math.round(minOf(9000.0, 0.45 * sr) / df).toInt())
        if (cl > 0.0 && k1 < k2 && k2 < k3 && k3 + 2 < k4) {
            val lv = DoubleArray(frames)
            val wc = DoubleArray(frames)
            for (m in 0 until frames) {
                var elf = 0.0; var emf = 0.0; var ehf = 0.0
                val o = m * bins
                for (k in k1 until k4) {
                    val nr = if (reliable) noiseRaw[k] else 0.0
                    val p = maxOf(pw[o + k] - nr, 0.0)
                    if (k < k2) elf += p else if (k < k3) emf += p else ehf += p
                }
                lv[m] = 10.0 * Math.log10(elf + emf + ehf + 1e-20)
                wc[m] = (((ehf / (elf + emf + 1e-20)) - 0.25) / 0.5).coerceIn(0.0, 1.0)   // صامت‌وار بودن فریم
            }
            var cnt = 0
            var ref = 0.0
            for (m in 0 until frames) if (sp[m] > 0.8) { ref += lv[m]; cnt++ }
            if (cnt >= 3) {
                ref /= cnt
                val refC = ref - 8.0
                for (m in 0 until frames) {
                    val d = refC - lv[m]
                    val b = if (d > 0.0) minOf(4.0, 0.4 * d) else if (d < -6.0) -minOf(2.5, 0.3 * (-d - 6.0)) else 0.0
                    bC[m] = b * wc[m] * sp[m] * cl
                    val r = minOf(3.5, maxOf(-2.5, 0.4 * (ref - lv[m])))
                    rD[m] = r * (1.0 - wc[m]) * sp[m] * cl
                }
                smoothZp(bC, 0.35)
                smoothZp(rD, 0.12)
            }
        }
        val kA = Math.round(1500.0 / df).toInt()
        val kB = Math.round(2500.0 / df).toInt()
        val taper = DoubleArray(bins) { ((it - kA).toDouble() / maxOf(1, kB - kA)).coerceIn(0.0, 1.0) }

        // گذر ۲: Wiener تصمیم‌محور + دروازهٔ مکث + ارتقا، هموارسازی در فرکانس و زمان، بازسازی با هم‌پوشانی
        val doWiener = cleanDb > 0.0 && reliable
        val gmin = if (cleanDb > 0.0) Math.pow(10.0, -cleanDb / 20.0) else 1.0
        val gsp = if (cleanDb > 0.0) Math.pow(10.0, -(0.5 * cleanDb) / 20.0) else 1.0   // کفِ بالاتر در فریم‌های گفتار: صامت‌های ضعیف خورده نشوند
        val sil = if (reliable && silDb > 0.0) Math.pow(10.0, -silDb / 20.0) else 1.0
        val alpha = 0.92
        val prevP = DoubleArray(bins)
        val gPrev = DoubleArray(bins) { 1.0 }
        val g = DoubleArray(bins)
        val out = DoubleArray(xp.size)
        val norm = DoubleArray(xp.size)
        for (m in 0 until frames) {
            val o = m * hop
            for (i in 0 until nn) { re[i] = xp[o + i] * win[i]; im[i] = 0.0 }
            ft.forward(re, im)
            if (doWiener) {
                for (k in 0 until bins) {
                    val p = re[k] * re[k] + im[k] * im[k]
                    val gamma = p / noise[k]
                    val xi = alpha * (prevP[k] / noise[k]) + (1.0 - alpha) * maxOf(gamma - 1.0, 0.0)
                    g[k] = xi / (1.0 + xi)
                }
            }
            val gfl = gmin + (gsp - gmin) * spS[m]
            val silF = 1.0 - (1.0 - sil) * (1.0 - spS[m])
            val riderLin = Math.pow(10.0, rD[m] / 20.0)
            val bcDb = bC[m]
            for (k in 0 until bins) {
                var v = 1.0
                if (doWiener) {
                    v = if (k == 0 || k == half) g[k] else 0.25 * g[k - 1] + 0.5 * g[k] + 0.25 * g[k + 1]
                    if (v < gfl) v = gfl
                    val lim = gPrev[k] * 0.6        // بهره در یک فریم بیش از حد نیفتد (جلوگیری از «بریدگی» و نویز موزیکال)
                    if (v < lim) v = lim
                    if (v > 1.0) v = 1.0
                    gPrev[k] = v
                    prevP[k] = (re[k] * re[k] + im[k] * im[k]) * v * v
                }
                var tot = v * silF * riderLin
                if (bcDb > 0.01 || bcDb < -0.01) { if (taper[k] > 0.0) tot *= Math.pow(10.0, bcDb * taper[k] / 20.0) }
                re[k] *= tot; im[k] *= tot
            }
            for (k in 1 until half) { re[nn - k] = re[k]; im[nn - k] = -im[k] }
            for (i in 0 until nn) im[i] = -im[i]      // وارونهٔ FFT با ترفند مزدوج
            ft.forward(re, im)
            for (i in 0 until nn) {
                out[o + i] += re[i] / nn * win[i]
                norm[o + i] += win[i] * win[i]
            }
        }
        val y = FloatArray(n)
        for (i in 0 until n) y[i] = (out[pad + i] / maxOf(norm[pad + i], 1e-3)).toFloat()
        return y
    }
}

/**
 * زنجیرهٔ مسترینگ بومی؛ جایگزین گراف WebAudio در جاوااسکریپت (همان ضرایب):
 * بالاگذر ۷۵Hz → شلف پایین ۱۸۰Hz (+۱٫۳dB) → پیک ۲٫۸kHz → دی‌اِسر ۷kHz (−۱٫۸dB) → شلف بالا ۶kHz → پایین‌گذر
 * → کمپرسور (−۲۲dB، ۳:۱) → ×۱٫۱ → لیمیتر (−۲dB، ۲۰:۱، پیش‌نگر ۳ms) → نرم‌برش ایمنی.
 * روی خروجی صیقل‌شدهٔ هر تکه اجرا می‌شود؛ پس کش جمله‌ها دست‌نخورده می‌ماند.
 */
private object MasterFx {
    private class Bq(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        fun run(x: DoubleArray) {
            var z1 = 0.0; var z2 = 0.0
            for (i in x.indices) {
                val v = x[i]
                val y = b0 * v + z1
                z1 = b1 * v - a1 * y + z2
                z2 = b2 * v - a2 * y
                x[i] = y
            }
        }
    }

    private fun norm(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
        Bq(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

    private fun w0(fc: Double, sr: Int) = 2.0 * Math.PI * Math.min(fc, sr * 0.45) / sr

    private fun highpass(fc: Double, q: Double, sr: Int): Bq {
        val w = w0(fc, sr); val c = Math.cos(w); val al = Math.sin(w) / (2.0 * q)
        return norm((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al)
    }

    private fun lowpass(fc: Double, q: Double, sr: Int): Bq {
        val w = w0(fc, sr); val c = Math.cos(w); val al = Math.sin(w) / (2.0 * q)
        return norm((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al)
    }

    private fun peaking(fc: Double, q: Double, db: Double, sr: Int): Bq {
        val w = w0(fc, sr); val c = Math.cos(w); val al = Math.sin(w) / (2.0 * q)
        val a = Math.pow(10.0, db / 40.0)
        return norm(1 + al * a, -2 * c, 1 - al * a, 1 + al / a, -2 * c, 1 - al / a)
    }

    private fun lowShelf(fc: Double, db: Double, sr: Int): Bq {
        val w = w0(fc, sr); val c = Math.cos(w)
        val a = Math.pow(10.0, db / 40.0)
        val t = 2.0 * Math.sqrt(a) * (Math.sin(w) / 2.0 * Math.sqrt(2.0))
        return norm(
            a * ((a + 1) - (a - 1) * c + t), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - t),
            (a + 1) + (a - 1) * c + t, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - t
        )
    }

    private fun highShelf(fc: Double, db: Double, sr: Int): Bq {
        val w = w0(fc, sr); val c = Math.cos(w)
        val a = Math.pow(10.0, db / 40.0)
        val t = 2.0 * Math.sqrt(a) * (Math.sin(w) / 2.0 * Math.sqrt(2.0))
        return norm(
            a * ((a + 1) + (a - 1) * c + t), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - t),
            (a + 1) - (a - 1) * c + t, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - t
        )
    }

    /** کمپرسور/لیمیتر با زانوی نرم، آشکارساز قله، پیش‌نگر و جبران بلندی (مثل DynamicsCompressor مرورگر) */
    private fun comp(x: DoubleArray, sr: Int, thr: Double, knee: Double, ratio: Double, att: Double, rel: Double, look: Double) {
        val n = x.size
        val la = (look * sr).toInt()
        val ac = Math.exp(-1.0 / (att * sr)); val rc = Math.exp(-1.0 / (rel * sr))
        fun gainDb(inDb: Double): Double {
            val over = inDb - thr
            val out = when {
                2 * over < -knee -> inDb
                knee > 0 && 2 * Math.abs(over) <= knee -> inDb + (1.0 / ratio - 1.0) * (over + knee / 2) * (over + knee / 2) / (2 * knee)
                else -> thr + over / ratio
            }
            return out - inDb
        }
        val mk = Math.pow(Math.pow(10.0, -gainDb(0.0) / 20.0), 0.6)
        val g = DoubleArray(n)
        var cur = 0.0
        for (i in 0 until n) {
            val a = Math.abs(x[i])
            val lvl = if (a < 1e-6) -120.0 else 20.0 * Math.log10(a)
            val tgt = gainDb(lvl)
            val cf = if (tgt < cur) ac else rc
            cur = cf * cur + (1 - cf) * tgt
            g[i] = Math.pow(10.0, cur / 20.0)
        }
        for (i in 0 until n) x[i] *= g[Math.min(n - 1, i + la)] * mk
    }

    private fun softClip(v: Double): Float {
        val a = Math.abs(v)
        if (a <= 0.98) return v.toFloat()
        val y = 0.98 + 0.02 * Math.tanh((a - 0.98) / 0.02)
        return (if (v < 0) -y else y).toFloat()
    }

    fun process(src: FloatArray, sr: Int, pk: Float, hs: Float, lp: Float): FloatArray {
        if (src.isEmpty()) return src
        val x = DoubleArray(src.size) { src[it].toDouble() }
        highpass(75.0, 1.122, sr).run(x)
        lowShelf(180.0, 1.3, sr).run(x)
        peaking(2800.0, 0.9, pk.toDouble(), sr).run(x)
        peaking(7000.0, 1.3, -1.8, sr).run(x)
        highShelf(6000.0, hs.toDouble(), sr).run(x)
        lowpass(lp.toDouble(), 1.085, sr).run(x)
        comp(x, sr, -22.0, 24.0, 3.0, 0.005, 0.15, 0.006)
        for (i in x.indices) x[i] *= 1.1
        comp(x, sr, -2.0, 0.0, 20.0, 0.001, 0.08, 0.003)
        return FloatArray(x.size) { softClip(x[it]) }
    }
}

/** کندکردن/تندکردن گفتار بدون تغییر زیر‌وبمی (WSOLA؛ همان الگوریتم نسخهٔ جاوااسکریپت) */
private object Stretch {
    fun run(src: ShortArray, from: Int, rate: Float, sr: Int): ShortArray {
        val n = src.size - from
        if (n <= 0) return ShortArray(0)
        if (Math.abs(rate - 1f) < 0.004f) return src.copyOfRange(from, src.size)   // ناحیهٔ مردهٔ بسیار کوچک: گام‌های ۰٫۰۱ سرعت همه اثر دارند
        val x = FloatArray(n) { src[from + it] / 32768f }
        val y = wsola(x, sr, rate.toDouble())
        return ShortArray(y.size) {
            val v = Math.round(y[it] * 32768f)
            (if (v > 32767) 32767 else if (v < -32768) -32768 else v).toShort()
        }
    }

    private fun wsola(x: FloatArray, sr: Int, rate: Double): FloatArray {
        val n = x.size
        var nn = Math.round(sr * 0.032).toInt(); nn += nn and 1
        val hs = nn shr 1
        val ha = hs * rate
        val d = Math.round(sr * 0.015).toInt()
        val p = d
        val frames = Math.ceil(n / ha).toInt() + 2
        val xp = FloatArray(n + nn + 2 * d + 8)
        System.arraycopy(x, 0, xp, p, n)
        val win = FloatArray(nn) { (0.5 - 0.5 * Math.cos(2.0 * Math.PI * it / nn)).toFloat() }
        val out = FloatArray(frames * hs + nn + 8)
        fun sc(cand: Int, rf: Int, st: Int): Double {
            var s = 0.0; var e = 0.0
            var q = 0
            while (q < hs) { val a = xp[cand + p + q]; s += a * xp[rf + p + q]; e += a * a; q += st }
            return s / Math.sqrt(e + 1e-6)
        }
        for (j in 0 until nn) out[j] = xp[p + j] * (if (j < hs) 1f else win[j])
        var prev = 0
        for (m in 1 until frames) {
            val tp = Math.round(m * ha).toInt()
            if (tp >= n) break
            val ref = prev + hs
            var best = tp
            var bs = sc(tp, ref, 4)
            var c = tp - d
            while (c <= tp + d) { val v = sc(c, ref, 4); if (v > bs) { bs = v; best = c }; c += 2 }
            var k = best
            bs = Double.NEGATIVE_INFINITY
            for (c2 in best - 2..best + 2) {
                if (c2 < tp - d || c2 > tp + d) continue
                val v = sc(c2, ref, 1)
                if (v > bs) { bs = v; k = c2 }
            }
            val o = m * hs
            for (j in 0 until nn) out[o + j] += xp[k + p + j] * win[j]
            prev = k
        }
        val len = Math.min(Math.round(n / rate).toInt(), out.size)
        val res = out.copyOf(len)
        val f = Math.min(len, Math.round(sr * 0.01).toInt())
        for (i in 0 until f) res[len - 1 - i] *= i.toFloat() / f
        return res
    }
}

/** سرویس پیش‌زمینهٔ پخش (نوع mediaPlayback)؛ فقط اعلان نگه می‌دارد و پخش را AudioTrack در پلاگین انجام می‌دهد. */
class PlayerService : android.app.Service() {
    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ch = "pdfvoice_play"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(android.app.NotificationChannel(ch, "پخش صوتی", android.app.NotificationManager.IMPORTANCE_LOW))
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pi = if (launch != null) android.app.PendingIntent.getActivity(this, 0, launch, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT) else null
        val b = if (android.os.Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(this, ch) else android.app.Notification.Builder(this)
        b.setContentTitle("خوانندهٔ صوتی PDF").setContentText("در حال خواندن…").setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true)
        if (pi != null) b.setContentIntent(pi)
        val n = b.build()
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(7421, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(7421, n)
        } catch (e: Throwable) { stopSelf() }
        return START_NOT_STICKY
    }
}

