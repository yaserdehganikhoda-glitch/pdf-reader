package ir.sewingstats.app   // <-- تغییر بده به appId پروژهٔ خودت

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
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
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * Piper (VITS) فارسی به‌صورت بومی با sherpa-onnx.
 * متدها: ping, stored, download({id, urls} یا {id, files:[{name, urls}]}), synthesize({id, text, noiseScale?, noiseScaleW?, speed?}), remove({id})
 * پوشهٔ دلخواه کاربر: pickFolder, folder, exportVoices({ids?}), importVoices({ids?, overwrite?})
 * رویداد: "progress" {loaded, total}
 *
 * مسیر ذخیرهٔ صداها (یک‌جا قابل تغییر): <externalFilesDir>/piper/<id>/
 */
@CapacitorPlugin(name = "PiperNative")
class PiperNativePlugin : Plugin() {

    private val io = Executors.newSingleThreadExecutor()     // دانلود/استخراج
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

    @PluginMethod
    fun ping(call: PluginCall) {
        call.resolve(JSObject().put("ok", true))
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
                                if (!out.canonicalPath.startsWith(dst.canonicalPath)) continue   // جلوگیری از path traversal
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

    @PluginMethod
    fun download(call: PluginCall) {
        val id = call.getString("id") ?: return call.reject("id")
        val files = call.getArray("files")
        if (files != null && files.length() > 0) return downloadFiles(call, id, files)   // مدل‌های بدون آرشیو (مثل Coqui)
        val urls = call.getArray("urls")?.toList<String>() ?: emptyList()
        if (urls.isEmpty()) return call.reject("urls")
        io.execute {
            val tmp = File(context.cacheDir, "piper-$id.tar.bz2")
            var lastErr: Exception? = null
            for (u in urls) {
                try { fetch(u, tmp); lastErr = null; break } catch (e: Exception) { lastErr = e }
            }
            if (lastErr != null) { tmp.delete(); call.reject("دانلود ناموفق: ${lastErr.message}"); return@execute }
            try {
                val dst = voiceDir(id)
                dst.deleteRecursively(); dst.mkdirs()
                extract(tmp, dst)
                tmp.delete()
                if (findOnnx(dst) == null || !File(findRoot(dst), "tokens.txt").exists())
                    throw IllegalStateException("فایل‌های مدل کامل نیست")
                okMark(id).writeText("1")
                call.resolve()
            } catch (e: Exception) {
                voiceDir(id).deleteRecursively()
                call.reject("استخراج ناموفق: ${e.message}")
            }
        }
    }

    /** دانلود چند فایل جدا (مثلاً model.onnx و tokens.txt) بدون آرشیو؛ files = [{name, urls:[...]}] */
    private fun downloadFiles(call: PluginCall, id: String, files: org.json.JSONArray) {
        io.execute {
            try {
                val dst = voiceDir(id)
                dst.deleteRecursively(); dst.mkdirs()
                for (i in 0 until files.length()) {
                    val o = files.getJSONObject(i)
                    val name = o.getString("name").replace(Regex("[^A-Za-z0-9_.-]"), "_")
                    val us = o.getJSONArray("urls")
                    val tmp = File(dst, "$name.part")
                    var lastErr: Exception? = null
                    for (k in 0 until us.length()) {
                        try { fetch(us.getString(k), tmp); lastErr = null; break } catch (e: Exception) { lastErr = e }
                    }
                    if (lastErr != null) throw lastErr
                    if (!tmp.renameTo(File(dst, name))) throw IllegalStateException("ذخیرهٔ فایل $name ممکن نشد")
                }
                // مدل‌های Coqui (مثل نگار) برای تبدیل متن به آوا به espeak-ng-data نیاز دارند؛ بدون آن sherpa برنامه را می‌بندد
                if (call.getBoolean("espeak", false) == true) {
                    val sh = sharedEspeak(id)
                    if (sh != null) {
                        // داده‌ای که از قبل کنار صدای دیگری دانلود شده کپی می‌شود (بدون اینترنت و مستقل از حذف آن صدا)
                        sh.copyRecursively(File(dst, "espeak-ng-data"), overwrite = true)
                    } else {
                        val etmp = File(context.cacheDir, "espeak-ng-data.tar.bz2")
                        val eu = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/espeak-ng-data.tar.bz2"
                        var eErr: Exception? = null
                        for (u in listOf(eu, "https://ghfast.top/$eu")) {
                            try { fetch(u, etmp); eErr = null; break } catch (e: Exception) { eErr = e }
                        }
                        if (eErr != null) { etmp.delete(); throw eErr }
                        extract(etmp, dst)
                        etmp.delete()
                    }
                    if (!File(dst, "espeak-ng-data").isDirectory) throw IllegalStateException("دادهٔ espeak کامل نیست")
                }
                if (findOnnx(dst) == null || !File(findRoot(dst), "tokens.txt").exists())
                    throw IllegalStateException("فایل‌های مدل کامل نیست")
                okMark(id).writeText("1")
                call.resolve()
            } catch (e: Throwable) {
                voiceDir(id).deleteRecursively()
                call.reject("دانلود ناموفق: ${e.message}")
            }
        }
    }

    private fun fetch(url: String, out: File) {
        var last: Exception? = null
        for (attempt in 0 until 2) {   // یک تلاش دوباره برای قطع‌شدن‌های کوتاه شبکه/VPN
            try { fetchOnce(url, out); return } catch (e: Exception) { last = e; out.delete() }
        }
        throw last ?: IllegalStateException("دانلود ناموفق")
    }

    private fun fetchOnce(url: String, out: File) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20000; c.readTimeout = 30000; c.instanceFollowRedirects = true
            c.setRequestProperty("Accept-Encoding", "identity")   // اندازهٔ واقعی فایل برای بررسی کامل‌بودن
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) PiperNative")
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            var loaded = 0L; var lastEmit = 0L
            c.inputStream.use { ins ->
                FileOutputStream(out).use { os ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break
                        os.write(buf, 0, n); loaded += n
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 250) {
                            lastEmit = now
                            notifyListeners("progress", JSObject().put("loaded", loaded).put("total", if (total > 0) total else 60_000_000L))
                        }
                    }
                }
            }
            if (total > 0 && loaded != total) throw IllegalStateException("دانلود ناقص ماند")
            notifyListeners("progress", JSObject().put("loaded", loaded).put("total", loaded))
        } finally {
            try { c.disconnect() } catch (e: Throwable) {}
        }
    }

    private fun extract(archive: File, dst: File) {
        var cnt = 0; var lastEmit = 0L
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream(), 1 shl 16))).use { tar ->
            while (true) {
                val e = tar.nextTarEntry ?: break
                val f = File(dst, e.name)
                if (!f.canonicalPath.startsWith(dst.canonicalPath)) continue   // جلوگیری از path traversal
                if (e.isDirectory) { f.mkdirs(); continue }
                f.parentFile?.mkdirs()
                FileOutputStream(f).use { tar.copyTo(it) }
                cnt++
                val now = System.currentTimeMillis()
                if (now - lastEmit > 400) {
                    lastEmit = now
                    notifyListeners("progress", JSObject().put("stage", "extract").put("n", cnt))
                }
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
        val onnx = findOnnx(voiceDir(id)) ?: throw IllegalStateException("مدل پیدا نشد؛ دوباره دانلود کنید")
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

    @PluginMethod
    fun synthesize(call: PluginCall) {
        val id = call.getString("id") ?: return call.reject("id")
        val text = call.getString("text") ?: return call.reject("text")
        if (text.isBlank()) return call.reject("متن خالی است")
        val ns = (call.getFloat("noiseScale") ?: 0.667f).coerceIn(0.2f, 1.2f)
        val nw = (call.getFloat("noiseScaleW") ?: 0.8f).coerceIn(0.2f, 1.2f)
        val sp = (call.getFloat("speed") ?: 1.0f).coerceIn(0.6f, 1.6f)   // <1 کندتر (سرعت هوشمند)
        cpu.execute {
            try {
                if (!okMark(id).exists()) throw IllegalStateException("این صدا هنوز دانلود نشده است")
                val eng = engineFor(id, ns, nw)
                val t0 = System.nanoTime()
                val a = eng.generate(text = text, sid = 0, speed = sp)
                val genMs = (System.nanoTime() - t0) / 1_000_000L   // زمان خالص مدل (بدون صیقل و انتقال)
                val wav = toWav(polish(a.samples, a.sampleRate), a.sampleRate)
                call.resolve(JSObject().put("wav", Base64.encodeToString(wav, Base64.NO_WRAP)).put("sampleRate", a.sampleRate).put("genMs", genMs))
            } catch (e: Throwable) {
                call.reject("خطای موتور بومی: ${e.message}")
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
        for (x in f) bb.putShort((x.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        return bb.array()
    }

    override fun handleOnDestroy() {
        cpu.execute { try { tts?.release() } catch (e: Throwable) {}; tts = null; ttsId = null; ttsKey = null }
        cpu.shutdown(); io.shutdown()
    }
}
