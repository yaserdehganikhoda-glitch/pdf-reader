package __PKG__

import android.os.Build
import android.os.StatFs
import android.util.Base64
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
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
 * افزونهٔ Capacitor برای اجرای بومی Piper با sherpa-onnx.
 * روش‌ها (مطابق index.html): ping, stored, download, synthesize, remove
 * رویداد: progress {loaded, total} یا {stage: 'extract'}
 */
@CapacitorPlugin(name = "PiperNative")
class PiperNativePlugin : Plugin() {

    private val dlPool = Executors.newSingleThreadExecutor()
    private val synPool = Executors.newSingleThreadExecutor()
    private val root: File by lazy { File(context.filesDir, "piper").apply { mkdirs() } }

    private var curId: String? = null
    private var curTts: OfflineTts? = null

    private fun validId(id: String?): Boolean = id != null && Regex("^[A-Za-z0-9_\\-]{3,80}$").matches(id)

    private fun isComplete(dir: File): Boolean =
        dir.isDirectory &&
            File(dir, "tokens.txt").isFile &&
            File(dir, "espeak-ng-data").isDirectory &&
            (dir.listFiles { f -> f.isFile && f.name.endsWith(".onnx") }?.isNotEmpty() == true)

    /* ---------------- ping ---------------- */
    @PluginMethod
    fun ping(call: PluginCall) {
        val r = JSObject()
        r.put("ok", true)
        r.put("engine", "sherpa-onnx")
        r.put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "?")
        r.put("sdk", Build.VERSION.SDK_INT)
        r.put("path", root.absolutePath)
        try {
            Class.forName("com.k2fsa.sherpa.onnx.OfflineTts")
            r.put("sherpa", true)
        } catch (e: Throwable) {
            r.put("sherpa", false)
            r.put("error", (e.javaClass.simpleName + ": " + (e.message ?: "")).take(200))
        }
        try {
            val st = StatFs(root.absolutePath)
            r.put("freeMb", st.availableBytes / (1024 * 1024))
        } catch (e: Throwable) { /* ignore */ }
        call.resolve(r)
    }

    /* ---------------- stored ---------------- */
    @PluginMethod
    fun stored(call: PluginCall) {
        val ids = JSArray()
        root.listFiles()?.filter { isComplete(it) }?.sortedBy { it.name }?.forEach { ids.put(it.name) }
        val r = JSObject()
        r.put("ids", ids)
        call.resolve(r)
    }

    /* ---------------- remove ---------------- */
    @PluginMethod
    fun remove(call: PluginCall) {
        val id = call.getString("id")
        if (!validId(id)) { call.reject("شناسهٔ صدا نادرست است"); return }
        synchronized(this) {
            if (curId == id) { try { curTts?.release() } catch (e: Throwable) {}; curTts = null; curId = null }
        }
        File(root, id!!).deleteRecursively()
        call.resolve()
    }

    /* ---------------- download ---------------- */
    @PluginMethod
    fun download(call: PluginCall) {
        val id = call.getString("id")
        if (!validId(id)) { call.reject("شناسهٔ صدا نادرست است"); return }
        val urls = ArrayList<String>()
        try {
            val arr = call.getArray("urls")
            if (arr != null) for (i in 0 until arr.length()) urls.add(arr.getString(i))
        } catch (e: Throwable) { /* ignore */ }
        if (urls.isEmpty()) { call.reject("نشانی دانلود داده نشده است"); return }
        dlPool.execute {
            try {
                doDownload(id!!, urls)
                call.resolve()
            } catch (e: Throwable) {
                call.reject(e.message ?: e.toString())
            }
        }
    }

    private fun emit(loaded: Long, total: Long) {
        val o = JSObject()
        o.put("loaded", loaded)
        o.put("total", if (total > 0) total else maxOf(loaded, 1L))
        notifyListeners("progress", o)
    }

    private fun doDownload(id: String, urls: List<String>) {
        val part = File(context.cacheDir, "$id.tar.bz2.part")
        var lastErr: Throwable? = null
        var ok = false
        for (u in urls) {
            try {
                var conn = URL(u).openConnection() as HttpURLConnection
                var hops = 0
                while (true) {
                    conn.connectTimeout = 20000
                    conn.readTimeout = 30000
                    conn.instanceFollowRedirects = false
                    conn.setRequestProperty("User-Agent", "PdfReader/6")
                    val code = conn.responseCode
                    if (code in 300..399 && hops++ < 6) {
                        val loc = conn.getHeaderField("Location") ?: throw RuntimeException("تغییر مسیر نامعتبر")
                        val next = URL(URL(u), loc)
                        conn.disconnect()
                        conn = next.openConnection() as HttpURLConnection
                        continue
                    }
                    if (code != 200) throw RuntimeException("پاسخ سرور: $code")
                    break
                }
                val total = conn.contentLengthLong
                var loaded = 0L
                var lastT = 0L
                conn.inputStream.use { ins ->
                    FileOutputStream(part).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            loaded += n
                            val now = System.currentTimeMillis()
                            if (now - lastT > 250) { lastT = now; emit(loaded, total) }
                        }
                    }
                }
                conn.disconnect()
                emit(loaded, if (total > 0) total else loaded)
                ok = true
                break
            } catch (e: Throwable) {
                lastErr = e
            }
        }
        if (!ok) { part.delete(); throw RuntimeException("دانلود ناموفق: " + (lastErr?.message ?: "نامشخص")) }

        val o = JSObject(); o.put("stage", "extract"); notifyListeners("progress", o)
        val tmp = File(root, "$id.tmp")
        tmp.deleteRecursively(); tmp.mkdirs()
        try {
            extractTarBz2(part, tmp)
            if (!isComplete(tmp)) throw RuntimeException("فایل مدل ناقص است (tokens.txt یا espeak-ng-data پیدا نشد)")
            val dst = File(root, id)
            synchronized(this) {
                if (curId == id) { try { curTts?.release() } catch (e: Throwable) {}; curTts = null; curId = null }
            }
            dst.deleteRecursively()
            if (!tmp.renameTo(dst)) throw RuntimeException("انتقال مدل به پوشهٔ نهایی ناموفق بود")
        } finally {
            tmp.deleteRecursively()
            part.delete()
        }
    }

    private fun extractTarBz2(src: File, dest: File) {
        val destCanon = dest.canonicalPath + File.separator
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(src.inputStream(), 1 shl 16))).use { tar ->
            var e: TarArchiveEntry? = tar.nextTarEntry
            while (e != null) {
                // حذف پوشهٔ سطح اول آرشیو (vits-piper-...)
                val name = e.name.replace('\\', '/').trimStart('/')
                val rel = name.substringAfter('/', "")
                if (rel.isNotEmpty()) {
                    val f = File(dest, rel)
                    if (!f.canonicalPath.startsWith(destCanon)) throw RuntimeException("مسیر نامعتبر در آرشیو")
                    if (e.isDirectory) f.mkdirs()
                    else if (e.isFile) {
                        f.parentFile?.mkdirs()
                        FileOutputStream(f).use { out -> tar.copyTo(out, 1 shl 16) }
                    }
                }
                e = tar.nextTarEntry
            }
        }
    }

    /* ---------------- synthesize ---------------- */
    @Synchronized
    private fun engine(id: String): OfflineTts {
        if (curId == id && curTts != null) return curTts!!
        try { curTts?.release() } catch (e: Throwable) {}
        curTts = null; curId = null
        val dir = File(root, id)
        if (!isComplete(dir)) throw RuntimeException("این صدا هنوز دانلود نشده است")
        val onnx = dir.listFiles { f -> f.isFile && f.name.endsWith(".onnx") }!!.first()
        val vits = OfflineTtsVitsModelConfig(
            model = onnx.absolutePath,
            tokens = File(dir, "tokens.txt").absolutePath,
            dataDir = File(dir, "espeak-ng-data").absolutePath
        )
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val model = OfflineTtsModelConfig(vits = vits, numThreads = threads, debug = false, provider = "cpu")
        val cfg = OfflineTtsConfig(model = model, maxNumSentences = 1)
        val t = OfflineTts(config = cfg)
        curTts = t; curId = id
        return t
    }

    @PluginMethod
    fun synthesize(call: PluginCall) {
        val id = call.getString("id")
        val text = (call.getString("text") ?: "").trim()
        if (!validId(id)) { call.reject("شناسهٔ صدا نادرست است"); return }
        if (text.isEmpty()) { call.reject("متن خالی است"); return }
        synPool.execute {
            try {
                val t0 = System.nanoTime()
                val tts = engine(id!!)
                val audio = tts.generate(text, 0, 1.0f)
                val wav = toWav(audio.samples, audio.sampleRate)
                val r = JSObject()
                r.put("wav", Base64.encodeToString(wav, Base64.NO_WRAP))
                r.put("ms", (System.nanoTime() - t0) / 1_000_000)
                r.put("sr", audio.sampleRate)
                call.resolve(r)
            } catch (e: Throwable) {
                call.reject(e.message ?: e.toString())
            }
        }
    }

    private fun toWav(samples: FloatArray, sr: Int): ByteArray {
        val n = samples.size
        val bb = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + n * 2); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1)
        bb.putInt(sr); bb.putInt(sr * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(n * 2)
        for (s in samples) {
            val v = (s * 32767f).toInt().coerceIn(-32768, 32767)
            bb.putShort(v.toShort())
        }
        return bb.array()
    }

    override fun handleOnDestroy() {
        try { curTts?.release() } catch (e: Throwable) {}
        curTts = null; curId = null
        super.handleOnDestroy()
    }
}
