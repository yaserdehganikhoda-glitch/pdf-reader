package ir.sewingstats.app   // <-- تغییر بده به appId پروژهٔ خودت

import android.util.Base64
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
 * متدها: ping, stored, download({id, urls}), synthesize({id, text}), remove({id})
 * رویداد: "progress" {loaded, total}
 *
 * مسیر ذخیرهٔ صداها (یک‌جا قابل تغییر): <externalFilesDir>/piper/<id>/
 */
@CapacitorPlugin(name = "PiperNative")
class PiperNativePlugin : Plugin() {

    private val io = Executors.newSingleThreadExecutor()     // دانلود/استخراج
    private val cpu = Executors.newSingleThreadExecutor()    // ساخت صدا (پشت‌سرهم)
    private var tts: OfflineTts? = null
    private var ttsId: String? = null
    private var sampleRate = 22050

    private fun root(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "piper").also { it.mkdirs() }
    }
    private fun voiceDir(id: String) = File(root(), id.replace(Regex("[^A-Za-z0-9_.-]"), "_"))
    private fun okMark(id: String) = File(voiceDir(id), ".ok")

    @PluginMethod
    fun ping(call: PluginCall) {
        call.resolve(JSObject().put("ok", true))
    }

    @PluginMethod
    fun stored(call: PluginCall) {
        val ids = org.json.JSONArray()
        root().listFiles()?.forEach { d -> if (File(d, ".ok").exists()) ids.put(d.name) }
        call.resolve(JSObject().put("ids", ids))
    }

    @PluginMethod
    fun remove(call: PluginCall) {
        val id = call.getString("id") ?: return call.reject("id")
        if (ttsId == id) { tts?.release(); tts = null; ttsId = null }
        voiceDir(id).deleteRecursively()
        call.resolve()
    }

    @PluginMethod
    fun download(call: PluginCall) {
        val id = call.getString("id") ?: return call.reject("id")
        val urls = call.getArray("urls")?.toList<String>() ?: emptyList()
        if (urls.isEmpty()) return call.reject("urls")
        io.execute {
            val tmp = File(context.cacheDir, "piper-$id.tar.bz2")
            var lastErr: Exception? = null
            for (u in urls) {
                try { fetch(u, tmp); lastErr = null; break } catch (e: Exception) { lastErr = e }
            }
            if (lastErr != null) { call.reject("دانلود ناموفق: ${lastErr.message}"); return@execute }
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

    private fun fetch(url: String, out: File) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 30000; c.instanceFollowRedirects = true
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
        notifyListeners("progress", JSObject().put("loaded", loaded).put("total", loaded))
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

    private fun engineFor(id: String): OfflineTts {
        if (tts != null && ttsId == id) return tts!!
        tts?.release(); tts = null; ttsId = null
        val r = findRoot(voiceDir(id))
        val onnx = findOnnx(voiceDir(id)) ?: throw IllegalStateException("مدل پیدا نشد؛ دوباره دانلود کنید")
        val vits = OfflineTtsVitsModelConfig(
            model = onnx.absolutePath,
            tokens = File(r, "tokens.txt").absolutePath,
            dataDir = File(r, "espeak-ng-data").absolutePath,
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
        tts = t; ttsId = id; sampleRate = t.sampleRate()
        return t
    }

    @PluginMethod
    fun synthesize(call: PluginCall) {
        val id = call.getString("id") ?: return call.reject("id")
        val text = call.getString("text") ?: return call.reject("text")
        cpu.execute {
            try {
                if (!okMark(id).exists()) throw IllegalStateException("این صدا هنوز دانلود نشده است")
                val a = engineFor(id).generate(text = text, sid = 0, speed = 1.0f)
                val wav = toWav(a.samples, a.sampleRate)
                call.resolve(JSObject().put("wav", Base64.encodeToString(wav, Base64.NO_WRAP)).put("sampleRate", a.sampleRate))
            } catch (e: Throwable) {
                call.reject("خطای موتور بومی: ${e.message}")
            }
        }
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

    override fun handleOnDestroy() { tts?.release(); tts = null }
}
