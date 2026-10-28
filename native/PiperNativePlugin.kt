package ir.sewingstats.app   // <-- تغییر بده به appId پروژهٔ خودت

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.activity.result.ActivityResult
import androidx.documentfile.provider.DocumentFile
import com.getcapacitor.annotation.ActivityCallback
import java.io.BufferedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
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
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
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
 * فهرست PDFهای دستگاه (صفحهٔ اصلی): listPdfs, pdfRequest
 * ذخیرهٔ متن: pickTextFolder, saveText({name,text,uri?})
 * پوشهٔ دلخواه کاربر: pickFolder, folder, exportVoices({ids?}), importVoices({ids?, overwrite?})
 * رویداد: "progress" {loaded, total}
 * کیفیت صدا: synthesize({..., clean?: 0..3, clarity?: 0..3}) → حذف نویز و ارتقای شفافیت/رسایی؛ ۰ خاموش، ۲ متعادل (پیش‌فرض)؛ جزئیات در SpeechClean (انتهای فایل)
 * مسترینگ: synthesize({..., master?: Boolean, fxPk?: dB, fxHs?: dB, fxLp?: Hz}) → EQ + کمپرسور + لیمیتر روی خروجی نهایی؛ جزئیات در MasterFx
 * ساخت صدا: synthesize({id, text, parts?:[{t, s}], gap?, noiseScale?, noiseScaleW?, speed?}) هر جمله (part) جدا ساخته و روی دیسک کش می‌شود؛
 *   کش دولایهٔ جمله‌ای روی دیسک (v1 خام، v2 پس از SpeechClean)؛ کلید فقط به متن همان جمله، صدا، نویز، سرعت و سطح پاک‌سازی بستگی دارد، نه به مرز تکه.
 *   سرعت s/speed بین ۰٫۴ و ۲٫۵ (سرعت کاربر داخل مدل).
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

    // ---- فشرده‌سازی کش صوت: WAV (۱۶بیت، تک‌کاناله) ⇄ AAC-LC در قالب m4a (حدود ۸ برابر کوچک‌تر) ----
    private val acEx = Executors.newSingleThreadExecutor()

    @PluginMethod
    fun encodeAac(call: PluginCall) {
        val s = call.getString("wav")
        if (s.isNullOrEmpty()) { call.reject("wav?"); return }
        acEx.execute {
            val out = File(context.cacheDir, "ac_" + System.nanoTime() + ".m4a")
            var codec: MediaCodec? = null
            var mux: MediaMuxer? = null
            var muxOn = false
            try {
                val wav = Base64.decode(s, Base64.DEFAULT)
                val bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
                if (wav.size < 128 || wav[0] != 0x52.toByte() || wav[36] != 0x64.toByte() || bb.getShort(22).toInt() != 1 || bb.getShort(34).toInt() != 16) throw IllegalArgumentException("fmt")
                val sr = bb.getInt(24)
                if (sr < 8000 || sr > 48000) throw IllegalArgumentException("sr")
                val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, 1)
                fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                fmt.setInteger(MediaFormat.KEY_BIT_RATE, 40000)
                fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                val cd = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC); codec = cd
                cd.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); cd.start()
                val mx = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4); mux = mx
                val info = MediaCodec.BufferInfo()
                var track = -1; var pos = 44; var inDone = false; var outDone = false; var pts = 0L
                val t0 = System.currentTimeMillis()
                while (!outDone) {
                    if (System.currentTimeMillis() - t0 > 20000) throw IllegalStateException("timeout")
                    if (!inDone) {
                        val ii = cd.dequeueInputBuffer(10000)
                        if (ii >= 0) {
                            val ib = cd.getInputBuffer(ii)!!
                            ib.clear()
                            val n = minOf(ib.capacity(), wav.size - pos) and 1.inv()
                            if (n <= 0) { cd.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                            else { ib.put(wav, pos, n); cd.queueInputBuffer(ii, 0, n, pts, 0); pos += n; pts += (n / 2).toLong() * 1000000L / sr }
                        }
                    }
                    val oi = cd.dequeueOutputBuffer(info, 10000)
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = mx.addTrack(cd.outputFormat); mx.start(); muxOn = true
                    } else if (oi >= 0) {
                        val ob = cd.getOutputBuffer(oi)!!
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                        if (info.size > 0 && muxOn) { ob.position(info.offset); ob.limit(info.offset + info.size); mx.writeSampleData(track, ob, info) }
                        cd.releaseOutputBuffer(oi, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
                    }
                }
                if (!muxOn) throw IllegalStateException("nomux")
                mx.stop(); muxOn = false
                call.resolve(JSObject().put("aac", Base64.encodeToString(out.readBytes(), Base64.NO_WRAP)).put("sampleRate", sr))
            } catch (e: Throwable) {
                call.reject("encodeAac: " + (e.message ?: e.javaClass.simpleName))
            } finally {
                try { codec?.stop() } catch (e: Throwable) {}
                try { codec?.release() } catch (e: Throwable) {}
                try { if (muxOn) mux?.stop() } catch (e: Throwable) {}
                try { mux?.release() } catch (e: Throwable) {}
                try { out.delete() } catch (e: Throwable) {}
            }
        }
    }

    @PluginMethod
    fun decodeAac(call: PluginCall) {
        val s = call.getString("aac")
        if (s.isNullOrEmpty()) { call.reject("aac?"); return }
        acEx.execute {
            val f = File(context.cacheDir, "ad_" + System.nanoTime() + ".m4a")
            var ex: MediaExtractor? = null
            var codec: MediaCodec? = null
            try {
                f.writeBytes(Base64.decode(s, Base64.DEFAULT))
                val e = MediaExtractor(); ex = e
                e.setDataSource(f.absolutePath)
                var tr = -1
                var inFmt: MediaFormat? = null
                for (i in 0 until e.trackCount) {
                    val fm = e.getTrackFormat(i)
                    if ((fm.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { tr = i; inFmt = fm; break }
                }
                if (tr < 0 || inFmt == null) throw IllegalArgumentException("track")
                e.selectTrack(tr)
                val cd = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!); codec = cd
                cd.configure(inFmt, null, null, 0); cd.start()
                val pcm = java.io.ByteArrayOutputStream()
                val info = MediaCodec.BufferInfo()
                var sIn = false; var sOut = false
                var sr = inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = 1
                val t0 = System.currentTimeMillis()
                while (!sOut) {
                    if (System.currentTimeMillis() - t0 > 20000) throw IllegalStateException("timeout")
                    if (!sIn) {
                        val ii = cd.dequeueInputBuffer(10000)
                        if (ii >= 0) {
                            val ib = cd.getInputBuffer(ii)!!
                            val n = e.readSampleData(ib, 0)
                            if (n < 0) { cd.queueInputBuffer(ii, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM); sIn = true }
                            else { cd.queueInputBuffer(ii, 0, n, e.sampleTime, 0); e.advance() }
                        }
                    }
                    val oi = cd.dequeueOutputBuffer(info, 10000)
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val of2 = cd.outputFormat
                        sr = of2.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = of2.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else if (oi >= 0) {
                        val ob = cd.getOutputBuffer(oi)!!
                        if (info.size > 0) { val a = ByteArray(info.size); ob.position(info.offset); ob.get(a); pcm.write(a) }
                        cd.releaseOutputBuffer(oi, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sOut = true
                    }
                }
                if (ch != 1) throw IllegalStateException("ch")
                val d = pcm.toByteArray()
                val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                h.put("RIFF".toByteArray()); h.putInt(36 + d.size); h.put("WAVEfmt ".toByteArray()); h.putInt(16)
                h.putShort(1.toShort()); h.putShort(1.toShort()); h.putInt(sr); h.putInt(sr * 2)
                h.putShort(2.toShort()); h.putShort(16.toShort()); h.put("data".toByteArray()); h.putInt(d.size)
                call.resolve(JSObject().put("wav", Base64.encodeToString(h.array() + d, Base64.NO_WRAP)).put("sampleRate", sr))
            } catch (e: Throwable) {
                call.reject("decodeAac: " + (e.message ?: e.javaClass.simpleName))
            } finally {
                try { codec?.stop() } catch (e: Throwable) {}
                try { codec?.release() } catch (e: Throwable) {}
                try { ex?.release() } catch (e: Throwable) {}
                try { f.delete() } catch (e: Throwable) {}
            }
        }
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
    /** فهرست PDFهای دستگاه برای صفحهٔ اصلی: listPdfs() ← {access, files:[{path, name, size, mtime(ثانیه)}]}؛ pdfRequest() صفحهٔ دسترسی را باز می‌کند.
     *  دسترسی کامل: اندروید ۱۱+ ← MANAGE_EXTERNAL_STORAGE، پایین‌تر ← READ_EXTERNAL_STORAGE (در AndroidManifest.xml با native/add-pdf-permissions.sh اعلام می‌شود).
     *  بدون دسترسی کامل فقط فایل‌هایی که سیستم نشان می‌دهد برمی‌گردد (access=false). */
    private fun hasPdfAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else context.checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE") == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** آیا مجوز لازم در AndroidManifest.xml همین نسخه اعلام شده است؟ بدون آن سیستم دکمهٔ دسترسی را غیرفعال می‌کند. */
    private fun pdfDeclared(): Boolean = try {
        val need = if (Build.VERSION.SDK_INT >= 30) "android.permission.MANAGE_EXTERNAL_STORAGE" else "android.permission.READ_EXTERNAL_STORAGE"
        val perms = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions
        perms != null && perms.any { it == need }
    } catch (e: Throwable) { true }

    @PluginMethod
    fun pdfRequest(call: PluginCall) {
        try {
            if (!pdfDeclared()) { val r0 = JSObject(); r0.put("access", false); r0.put("declared", false); call.resolve(r0); return }
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Throwable) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            } else {
                activity?.requestPermissions(arrayOf("android.permission.READ_EXTERNAL_STORAGE"), 9318)
            }
            val r = JSObject(); r.put("access", hasPdfAccess()); r.put("declared", true); call.resolve(r)
        } catch (e: Throwable) { call.reject(e.message ?: "request failed") }
    }

    @PluginMethod
    fun listPdfs(call: PluginCall) {
        Thread {
            try {
                val arr = org.json.JSONArray()
                val cols = arrayOf(MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.SIZE, MediaStore.Files.FileColumns.DATE_MODIFIED)
                val sel = "(" + MediaStore.Files.FileColumns.MIME_TYPE + " = ? OR " + MediaStore.Files.FileColumns.DISPLAY_NAME + " LIKE ?) AND " + MediaStore.Files.FileColumns.SIZE + " > 0"
                val seen = HashSet<String>()
                context.contentResolver.query(MediaStore.Files.getContentUri("external"), cols, sel, arrayOf("application/pdf", "%.pdf"), MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC")?.use { c ->
                    val iD = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                    val iN = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    val iS = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                    val iM = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                    while (c.moveToNext() && arr.length() < 5000) {
                        val path = c.getString(iD) ?: continue
                        val name = c.getString(iN) ?: File(path).name
                        if (!name.endsWith(".pdf", ignoreCase = true) || path.contains("/Android/data/") || path.contains("/.Trash") || path.contains("/.trash")) continue
                        if (!seen.add(path) || !File(path).canRead()) continue
                        val o = JSObject()
                        o.put("path", path); o.put("name", name); o.put("size", c.getLong(iS)); o.put("mtime", c.getLong(iM))
                        arr.put(o)
                    }
                }
                val r = JSObject(); r.put("access", hasPdfAccess()); r.put("declared", pdfDeclared()); r.put("files", arr); call.resolve(r)
            } catch (e: Throwable) { call.reject(e.message ?: "scan failed") }
        }.start()
    }

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
                PlayerService.startFgs(context, si)
            } else if (!MediaBridge.active) PlayerService.stopSafe(context)
        } catch (e: Throwable) { /* بدون اعلان یا مجوز: پخش بدون سرویس ادامه دارد */ }
        call.resolve()
    }

    /* ---- اعلان رسانه‌ای: mediaStart/mediaUpdate/mediaStop؛ رویداد mediaAction {action: play|pause|next|prev|seek|speed|stop, pos} ---- */
    private fun mediaApply(call: PluginCall) {
        call.getString("title")?.let { MediaBridge.title = it }
        call.getString("sub")?.let { MediaBridge.sub = it }
        call.getInt("total")?.let { MediaBridge.total = it }
        call.getInt("index")?.let { MediaBridge.index = it }
        call.getBoolean("playing")?.let { MediaBridge.playing = it }
        call.getFloat("rate")?.let { MediaBridge.rate = it }
        call.getDouble("dur")?.let { MediaBridge.durMs = it.toLong() }
        call.getDouble("pos")?.let { MediaBridge.posMs = it.toLong() }
        call.getString("cover")?.let { b64 ->
            try { val by = android.util.Base64.decode(b64, android.util.Base64.DEFAULT); MediaBridge.cover = android.graphics.BitmapFactory.decodeByteArray(by, 0, by.size) } catch (e: Throwable) {}
        }
    }
    private fun mediaPush() {
        try {
            val si = Intent(context, PlayerService::class.java).setAction(PlayerService.A_SYNC)
            PlayerService.startFgs(context, si)
        } catch (e: Throwable) { /* شروع سرویس از پس‌زمینه ممنوع/بدون مجوز: پخش بدون اعلان ادامه دارد */ }
    }
    @PluginMethod
    fun mediaStart(call: PluginCall) {
        mediaApply(call)
        MediaBridge.active = true
        MediaBridge.cb = { a, p -> notifyListeners("mediaAction", JSObject().put("action", a).put("pos", p)) }
        mediaPush(); call.resolve()
    }
    @PluginMethod
    fun mediaUpdate(call: PluginCall) {
        if (!MediaBridge.active) { call.reject("NO_MEDIA"); return }
        mediaApply(call); mediaPush(); call.resolve()
    }
    @PluginMethod
    fun mediaStop(call: PluginCall) {
        MediaBridge.active = false; MediaBridge.playing = false; MediaBridge.cb = null; MediaBridge.cover = null
        PlayerService.stopSafe(context)
        call.resolve()
    }

    @PluginMethod
    fun ping(call: PluginCall) {
        call.resolve(JSObject().put("ok", true).put("player", true).put("build", "kt-r8-focus").put("media", true))
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

    // ---------- ذخیرهٔ فایل متنی (اسکن دستی و اصلاح، برون‌بری واژه‌نامه): پوشهٔ دلخواه (SAF) یا «ذخیره به‌عنوان» ----------
    private var pendText = ""

    @PluginMethod
    fun pickTextFolder(call: PluginCall) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        startActivityForResult(call, i, "textFolderResult")
    }

    @ActivityCallback
    private fun textFolderResult(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        val u = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || u == null) { call.reject("CANCELLED"); return }
        try {
            context.contentResolver.takePersistableUriPermission(
                u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) { call.reject("دسترسی دائمی به پوشه داده نشد"); return }
        val name = DocumentFile.fromTreeUri(context, u)?.name ?: "پوشهٔ انتخابی"
        call.resolve(JSObject().put("uri", u.toString()).put("name", name))
    }

    @PluginMethod
    fun saveText(call: PluginCall) {
        val name = (call.getString("name") ?: "text.txt").map { c -> if (c in "/\\:*?\"<>|") '_' else c }.joinToString("")
        val text = call.getString("text") ?: ""
        val uri = call.getString("uri") ?: ""
        if (uri.isNotEmpty()) {
            io.execute {
                try {
                    val u = Uri.parse(uri)
                    val ok = context.contentResolver.persistedUriPermissions.any { it.uri == u && it.isWritePermission }
                    if (!ok) { call.reject("دسترسی به پوشه از بین رفته؛ مسیر را دوباره انتخاب کنید"); return@execute }
                    val dir = DocumentFile.fromTreeUri(context, u)
                    if (dir == null || !dir.canWrite()) { call.reject("پوشه قابل نوشتن نیست؛ مسیر را دوباره انتخاب کنید"); return@execute }
                    val f = dir.findFile(name) ?: dir.createFile("text/plain", name.removeSuffix(".txt"))
                    if (f == null) { call.reject("ساخت فایل ناموفق بود"); return@execute }
                    context.contentResolver.openOutputStream(f.uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    call.resolve(JSObject().put("name", f.name ?: name))
                } catch (e: Exception) { call.reject("ذخیره ناموفق بود: " + (e.message ?: "")) }
            }
            return
        }
        pendText = text
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/plain").putExtra(Intent.EXTRA_TITLE, name)
        startActivityForResult(call, i, "textSaveResult")
    }

    @ActivityCallback
    private fun textSaveResult(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        val u = result.data?.data
        val txt = pendText; pendText = ""
        if (result.resultCode != Activity.RESULT_OK || u == null) { call.reject("CANCELLED"); return }
        io.execute {
            try {
                context.contentResolver.openOutputStream(u, "wt")!!.use { it.write(txt.toByteArray(Charsets.UTF_8)) }
                call.resolve(JSObject().put("name", DocumentFile.fromSingleUri(context, u)?.name ?: ""))
            } catch (e: Exception) { call.reject("ذخیره ناموفق بود: " + (e.message ?: "")) }
        }
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

    // ---------- صداهای زنانهٔ دانلودی (Coqui VITS فارسی؛ model.onnx + tokens.txt آمادهٔ sherpa) ----------
    // بعد از نصب برنامه دانلود می‌شوند (نه داخل APK). آوانگاری با espeak است، پس espeak-ng-data از صدای gyro کپی می‌شود.
    private class Dl(val folder: String)
    // آینهٔ دوم برای شبکه‌هایی که huggingface.co در آن‌ها کند یا مسدود است؛ فایل ناقص از همان نقطه ادامه می‌یابد
    private val DL_BASES = listOf(
        "https://huggingface.co/karim23657/persian-tts-vits/resolve/main/",
        "https://hf-mirror.com/karim23657/persian-tts-vits/resolve/main/"
    )
    private val DOWNLOADABLE = mapOf(
        "fa_IR-negin-coqui" to Dl("female-GPTInformal-coqui-vits"),
        "fa_IR-sahar-coqui" to Dl("female-female-coqui-vits")
    )
    private val ESPEAK_DONOR = "fa_IR-gyro-medium"   // فقط برای کپی espeak-ng-data (مدل‌های Coqui خودشان espeak ندارند)

    private fun fetchMirrors(path: String, part: File, emit: (Long, Long) -> Unit) {
        var last: Throwable? = null
        for (b in DL_BASES) {
            try { fetchResumable(b + path, part, emit); return } catch (e: IllegalStateException) { last = e }
        }
        throw last ?: IllegalStateException("دانلود ناموفق")
    }

    private fun downloadPair(call: PluginCall, id: String, dl: Dl) {
        io.execute {
            if (okMark(id).exists() && findOnnx(voiceDir(id)) != null) { call.resolve(); return@execute }
            val partOnnx = File(root(), "$id.dl")
            val partTok = File(root(), "$id.tok.dl")
            try {
                if (ttsId == id) releaseEngine(id)
                // فضای خالی: دانلود + کپی نهایی + espeak (حدود ۴۵۰ مگابایت در بدترین حالت)
                if (root().usableSpace < 450_000_000L) throw IllegalStateException("فضای خالی گوشی کافی نیست (حداقل ۴۵۰ مگابایت)")
                val emit = { l: Long, t: Long -> notifyListeners("progress", JSObject().put("loaded", l).put("total", t)) }
                // ۱) espeak-ng-data: از صدای gyro (اگر نصب نیست، یک‌بار دانلود می‌شود)
                var donorBytes = 0L
                var espeakSrc: File? = sharedEspeak(id)
                if (espeakSrc == null) {
                    val dpart = File(root(), "$ESPEAK_DONOR.dl")
                    fetchResumable("$VOICE_BASE/vits-piper-$ESPEAK_DONOR.tar.bz2", dpart) { l, t -> emit(l, t + 114_000_000L) }
                    donorBytes = dpart.length()
                    val dd = voiceDir(ESPEAK_DONOR)
                    dd.deleteRecursively(); dd.mkdirs()
                    java.io.FileInputStream(dpart).use { extract(it, dd) }
                    if (findOnnx(dd) == null || !File(findRoot(dd), "tokens.txt").exists())
                        throw IllegalStateException("فایل‌های espeak کامل نیست")
                    okMark(ESPEAK_DONOR).writeText("1")
                    dpart.delete()
                    espeakSrc = File(findRoot(dd), "espeak-ng-data").takeIf { it.isDirectory }
                }
                if (espeakSrc == null) throw IllegalStateException("espeak-ng-data پیدا نشد")
                // ۲) توکن‌ها و مدل (با ادامه پس از قطع شبکه)
                fetchMirrors(dl.folder + "/tokens.txt", partTok) { _, _ -> }
                fetchMirrors(dl.folder + "/model.onnx", partOnnx) { l, t -> emit(donorBytes + l, donorBytes + t) }
                if (partOnnx.length() < 20_000_000L) { partOnnx.delete(); throw IllegalStateException("فایل مدل ناقص یا نامعتبر است؛ دوباره امتحان کنید") }
                if (partTok.length() < 20L) { partTok.delete(); throw IllegalStateException("فایل توکن‌ها ناقص است؛ دوباره امتحان کنید") }
                // ۳) چیدن فایل‌ها: onnx + tokens.txt + espeak-ng-data
                val dst = voiceDir(id)
                dst.deleteRecursively(); dst.mkdirs()
                partOnnx.copyTo(File(dst, "$id.onnx"), overwrite = true)
                partTok.copyTo(File(dst, "tokens.txt"), overwrite = true)
                espeakSrc.copyRecursively(File(dst, "espeak-ng-data"), overwrite = true)
                if (findOnnx(dst) == null || !File(findRoot(dst), "tokens.txt").exists())
                    throw IllegalStateException("فایل‌های مدل کامل نیست")
                okMark(id).writeText("1")
                partOnnx.delete(); partTok.delete()
                emit(1, 1)
                call.resolve()
            } catch (e: Throwable) {
                voiceDir(id).deleteRecursively()   // فایل‌های ناقص دانلود (.dl) برای ادامه می‌مانند
                call.reject("نصب ناموفق: ${e.message}")
            }
        }
    }

    // ---------- صدای مانا (Piper فارسی MahtaFetrat؛ MIT): onnx خام + json ⇒ تبدیل به قالب sherpa ----------
    // مدل خام Piper متادیتای sherpa و tokens.txt ندارد؛ بعد از دانلود، tokens.txt از phoneme_id_map ساخته و متادیتا به انتهای onnx افزوده می‌شود.
    private val MANA_ID = "fa_IR-mana-medium"
    private val MANA_BASES = listOf(
        "https://huggingface.co/MahtaFetrat/Mana-Persian-Piper/resolve/main/",
        "https://hf-mirror.com/MahtaFetrat/Mana-Persian-Piper/resolve/main/"
    )

    private fun fetchFrom(bases: List<String>, path: String, part: File, emit: (Long, Long) -> Unit) {
        var last: Throwable? = null
        for (b in bases) {
            try { fetchResumable(b + path, part, emit); return } catch (e: IllegalStateException) { last = e }
        }
        throw last ?: IllegalStateException("دانلود ناموفق")
    }

    private fun pbVarint(n: Int): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        var v = n
        while (v >= 0x80) { o.write((v and 0x7F) or 0x80); v = v ushr 7 }
        o.write(v)
        return o.toByteArray()
    }

    /** ModelProto.metadata_props (فیلد ۱۴) = StringStringEntryProto{key=1, value=2}؛ افزودن در انتهای فایل در protobuf معتبر است */
    private fun pbMetaEntry(k: String, v: String): ByteArray {
        val kb = k.toByteArray(Charsets.UTF_8)
        val vb = v.toByteArray(Charsets.UTF_8)
        val inner = java.io.ByteArrayOutputStream()
        inner.write(0x0A); inner.write(pbVarint(kb.size)); inner.write(kb)
        inner.write(0x12); inner.write(pbVarint(vb.size)); inner.write(vb)
        val ib = inner.toByteArray()
        val out = java.io.ByteArrayOutputStream()
        out.write(0x72); out.write(pbVarint(ib.size)); out.write(ib)
        return out.toByteArray()
    }

    private fun addOnnxMeta(onnx: File, meta: List<Pair<String, String>>) {
        FileOutputStream(onnx, true).use { o -> for (kv in meta) o.write(pbMetaEntry(kv.first, kv.second)) }
    }

    /** tokens.txt برای sherpa از phoneme_id_map پیکربندی Piper: «نماد شناسه» در هر خط */
    private fun tokensFromPiperJson(json: org.json.JSONObject): String {
        val map = json.getJSONObject("phoneme_id_map")
        val rows = ArrayList<Pair<Int, String>>()
        val keys = map.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val ids = map.getJSONArray(k)
            if (ids.length() > 0) rows.add(Pair(ids.getInt(0), k))
        }
        rows.sortBy { it.first }
        val sb = StringBuilder()
        for (r in rows) sb.append(r.second).append(' ').append(r.first).append('\n')
        return sb.toString()
    }

    private fun downloadMana(call: PluginCall, id: String) {
        io.execute {
            if (okMark(id).exists() && findOnnx(voiceDir(id)) != null) { call.resolve(); return@execute }
            val partOnnx = File(root(), "$id.dl")
            val partJson = File(root(), "$id.json.dl")
            try {
                if (ttsId == id) releaseEngine(id)
                if (root().usableSpace < 450_000_000L) throw IllegalStateException("فضای خالی گوشی کافی نیست (حداقل ۴۵۰ مگابایت)")
                val emit = { l: Long, t: Long -> notifyListeners("progress", JSObject().put("loaded", l).put("total", t)) }
                // ۱) espeak-ng-data: از صدای gyro (اگر نصب نیست، یک‌بار دانلود می‌شود)
                var donorBytes = 0L
                var espeakSrc: File? = sharedEspeak(id)
                if (espeakSrc == null) {
                    val dpart = File(root(), "$ESPEAK_DONOR.dl")
                    fetchResumable("$VOICE_BASE/vits-piper-$ESPEAK_DONOR.tar.bz2", dpart) { l, t -> emit(l, t + 64_000_000L) }
                    donorBytes = dpart.length()
                    val dd = voiceDir(ESPEAK_DONOR)
                    dd.deleteRecursively(); dd.mkdirs()
                    java.io.FileInputStream(dpart).use { extract(it, dd) }
                    if (findOnnx(dd) == null || !File(findRoot(dd), "tokens.txt").exists())
                        throw IllegalStateException("فایل‌های espeak کامل نیست")
                    okMark(ESPEAK_DONOR).writeText("1")
                    dpart.delete()
                    espeakSrc = File(findRoot(dd), "espeak-ng-data").takeIf { it.isDirectory }
                }
                if (espeakSrc == null) throw IllegalStateException("espeak-ng-data پیدا نشد")
                // ۲) پیکربندی و مدل (با ادامه پس از قطع شبکه)
                fetchFrom(MANA_BASES, "fa_IR-mana-medium.onnx.json", partJson) { _, _ -> }
                fetchFrom(MANA_BASES, "fa_IR-mana-medium.onnx", partOnnx) { l, t -> emit(donorBytes + l, donorBytes + t) }
                if (partOnnx.length() < 20_000_000L) { partOnnx.delete(); throw IllegalStateException("فایل مدل ناقص یا نامعتبر است؛ دوباره امتحان کنید") }
                val cfg = try { org.json.JSONObject(partJson.readText(Charsets.UTF_8)) } catch (e: Throwable) { partJson.delete(); throw IllegalStateException("فایل پیکربندی مانا خراب است؛ دوباره امتحان کنید") }
                val tokens = tokensFromPiperJson(cfg)
                if (tokens.length < 40) throw IllegalStateException("جدول نمادهای مانا نامعتبر است")
                val sr = cfg.optJSONObject("audio")?.optInt("sample_rate", 22050) ?: 22050
                val ns = cfg.optInt("num_speakers", 1)
                val voice = cfg.optJSONObject("espeak")?.optString("voice", "fa") ?: "fa"
                val lang = cfg.optJSONObject("language")?.optString("name_english", "Persian") ?: "Persian"
                // ۳) چیدن فایل‌ها: onnx (+متادیتای sherpa) + tokens.txt + espeak-ng-data
                val dst = voiceDir(id)
                dst.deleteRecursively(); dst.mkdirs()
                val outOnnx = File(dst, "$id.onnx")
                partOnnx.copyTo(outOnnx, overwrite = true)
                addOnnxMeta(outOnnx, listOf(
                    Pair("model_type", "vits"), Pair("comment", "piper"), Pair("language", lang),
                    Pair("voice", voice), Pair("has_espeak", "1"), Pair("n_speakers", ns.toString()), Pair("sample_rate", sr.toString())
                ))
                File(dst, "tokens.txt").writeText(tokens, Charsets.UTF_8)
                espeakSrc.copyRecursively(File(dst, "espeak-ng-data"), overwrite = true)
                if (findOnnx(dst) == null || !File(findRoot(dst), "tokens.txt").exists())
                    throw IllegalStateException("فایل‌های مدل کامل نیست")
                okMark(id).writeText("1")
                partOnnx.delete(); partJson.delete()
                emit(1, 1)
                call.resolve()
            } catch (e: Throwable) {
                voiceDir(id).deleteRecursively()   // فایل‌های ناقص دانلود (.dl) برای ادامه می‌مانند
                call.reject("نصب ناموفق: ${e.message}")
            }
        }
    }

    /** نصب صدا: از assets اگر همراه APK باشد، وگرنه دانلود مستقیم و استخراج هم‌زمان؛ سپس بررسی کامل‌بودن */
    @PluginMethod
    fun download(call: PluginCall) {
        val id = call.getString("id") ?: return call.reject("id")
        if (!Regex("[A-Za-z0-9_-]+").matches(id)) return call.reject("شناسهٔ صدا نامعتبر است")
        DOWNLOADABLE[id]?.let { downloadPair(call, id, it); return }
        if (id == MANA_ID) { downloadMana(call, id); return }
        io.execute {
            val part = File(root(), "$id.dl")
            var netDone = false
            // نصب تکراری (مثلاً هم‌زمان با بارگذاری از پوشه): اگر صدا کامل نصب است کاری لازم نیست
            if (okMark(id).exists() && findOnnx(voiceDir(id)) != null) { call.resolve(); return@execute }
            try {
                // بدون کپی موقت: جریان asset مستقیم به bz2 و tar می‌رود (نصف فضا و زمان)؛ پیشرفت از روی بایت‌های خوانده‌شده
                // اگر صدا همراه APK بود از assets؛ وگرنه (حالت پیش‌فرض) بعد از نصب از اینترنت دانلود می‌شود
                val src: java.io.InputStream
                val total0: Long
                val fromNet = id !in bundledIds()
                val emit = { l: Long, t: Long -> notifyListeners("progress", JSObject().put("loaded", l).put("total", t)) }
                // فضای خالی: فایل فشرده + استخراج (حدود ۳ برابر حجم صدا)
                if (fromNet && root().usableSpace < 300_000_000L) throw IllegalStateException("فضای خالی گوشی کافی نیست (حداقل ۳۰۰ مگابایت)")
                if (!fromNet) {
                    total0 = try { context.assets.openFd("voices/$id.tar.bz2").use { it.length } } catch (e: Throwable) { 70_000_000L }
                    src = context.assets.open("voices/$id.tar.bz2")
                } else {
                    fetchResumable("$VOICE_BASE/vits-piper-$id.tar.bz2", part, emit)
                    netDone = true
                    if (part.length() < 5_000_000L) throw IllegalStateException("فایل دانلودشده ناقص یا نامعتبر است؛ دوباره امتحان کنید")
                    total0 = part.length()
                    src = java.io.FileInputStream(part)
                }
                if (ttsId == id) releaseEngine(id)
                val dst = voiceDir(id)
                dst.deleteRecursively(); dst.mkdirs()
                var loaded = 0L; var lastEmit = 0L
                val counting = object : java.io.FilterInputStream(src) {
                    private fun tick(n: Long) {
                        loaded += n
                        val now = System.currentTimeMillis()
                        if (!fromNet && now - lastEmit > 250) { lastEmit = now; emit(loaded, Math.max(loaded + 1, total0)) }
                    }
                    override fun read(): Int { val b = super.read(); if (b >= 0) tick(1L); return b }
                    override fun read(b: ByteArray, off: Int, len: Int): Int { val n = super.read(b, off, len); if (n > 0) tick(n.toLong()); return n }
                }
                counting.use { extract(it, dst) }
                emit(loaded, loaded)
                if (findOnnx(dst) == null || !File(findRoot(dst), "tokens.txt").exists())
                    throw IllegalStateException("فایل‌های مدل کامل نیست")
                okMark(id).writeText("1")
                part.delete()
                call.resolve()
            } catch (e: Throwable) {
                voiceDir(id).deleteRecursively()
                if (netDone) part.delete()   // فایل کامل ولی خراب → دفعهٔ بعد از صفر؛ قطعی شبکه → فایل ناقص برای ادامه می‌ماند
                call.reject("نصب ناموفق: ${e.message}")
            }
        }
    }

    /** دانلود با ادامه از نقطهٔ قطع (Range) و تلاش مجدد خودکار؛ فایل ناقص در part می‌ماند */
    private fun fetchResumable(url: String, part: File, emit: (Long, Long) -> Unit) {
        var fails = 0
        var total = -1L
        var lastEmit = 0L
        var lastSize = -1L
        while (true) {
            var conn: java.net.HttpURLConnection? = null
            try {
                val have = if (part.exists()) part.length() else 0L
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn = c
                c.connectTimeout = 20_000; c.readTimeout = 30_000; c.instanceFollowRedirects = true
                if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                val code = c.responseCode
                var append = false
                when {
                    code == 206 -> {
                        append = true
                        total = c.getHeaderField("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
                            ?: (if (c.contentLengthLong > 0) have + c.contentLengthLong else -1L)
                    }
                    code == 200 -> { total = c.contentLengthLong; if (have > 0) part.delete() }
                    code == 416 -> { if (have > 0) return; throw IllegalStateException("دانلود ناموفق (کد 416)") }
                    code == 429 || code in 500..599 -> throw java.io.IOException("خطای سرور ($code)")
                    else -> throw IllegalStateException("دانلود ناموفق (کد $code) — اتصال اینترنت را بررسی کنید")
                }
                var got = if (append) have else 0L
                c.inputStream.use { ins ->
                    java.io.FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); got += n
                            val now = System.currentTimeMillis()
                            if (now - lastEmit > 250) { lastEmit = now; emit(got, Math.max(got + 1, if (total > 0) total else 70_000_000L)) }
                        }
                    }
                }
                if (total > 0 && got < total) throw java.io.IOException("اتصال قطع شد")
                emit(got, got)
                return
            } catch (e: java.io.IOException) {
                val size = if (part.exists()) part.length() else 0L
                if (size > lastSize) fails = 0 else fails++   // تا وقتی پیشرفت هست از نو شمارش می‌شود
                lastSize = size
                if (fails > 12) throw IllegalStateException("دانلود ناتمام ماند (${e.message}) — دوباره بزنید تا از همان‌جا ادامه یابد")
                try { Thread.sleep(Math.min(1500L * fails, 8000L)) } catch (ie: InterruptedException) { throw e }
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
        val sp = (call.getFloat("speed") ?: 1.0f).coerceIn(0.4f, 2.5f)   // <1 کندتر؛ >1 تندتر (سرعت کاربر داخل خود مدل)
        val gap = (call.getFloat("gap") ?: 0.12f).coerceIn(0f, 0.6f)      // ثانیه، سکوت بین جمله‌ها
        val partsArr = call.getArray("parts")
        val lvlClean = (call.getInt("clean") ?: 2).coerceIn(0, 3)       // حذف نویز و صداهای اضافه
        val lvlClarity = (call.getInt("clarity") ?: 2).coerceIn(0, 3)   // شفافیت و رسایی
        val keep = call.getBoolean("keep") ?: false                      // true: PCM در حافظهٔ بومی می‌ماند و فقط handle برمی‌گردد (پخش بومی)
        val fxOn = call.getBoolean("master") ?: false                    // زنجیرهٔ EQ + کمپرسور + لیمیتر (MasterFx)
        val fxPk = (call.getFloat("fxPk") ?: 2f).coerceIn(-6f, 8f)
        val fxHs = (call.getFloat("fxHs") ?: 0.8f).coerceIn(-6f, 8f)
        val fxLp = (call.getFloat("fxLp") ?: 10500f).coerceIn(4000f, 20000f)
        val strong = false   // پاک‌سازی عادی برای همهٔ صداها (حالت قوی مخصوص مانا بود که حذف شد)
        val ep = epoch.get()   // لحظهٔ رسیدن درخواست؛ اگر بعدش cancel بیاید این کار رها می‌شود
        cpu.execute {
            try {
                if (ep != epoch.get()) throw IllegalStateException("NATIVE_CANCELLED")
                if (!okMark(id).exists()) throw IllegalStateException("این صدا هنوز نصب نشده است؛ چند لحظه صبر کنید تا نصب تمام شود")
                val eng = engineFor(id, ns, nw)
                val sr = eng.sampleRate()
                // هر جمله جدا (برای کش)؛ بدون parts کل متن یک جمله حساب می‌شود
                val items = ArrayList<Pair<String, Float>>()
                val itemGaps = ArrayList<Float>()   // مکث (ثانیه) پس از هر جمله؛ بدون g همان gap سراسری
                if (partsArr != null && partsArr.length() > 0) {
                    for (i in 0 until partsArr.length()) {
                        val o = partsArr.getJSONObject(i)
                        items.add(Pair(o.optString("t", ""), o.optDouble("s", sp.toDouble()).toFloat().coerceIn(0.4f, 2.5f)))
                        itemGaps.add(o.optDouble("g", gap.toDouble()).toFloat().coerceIn(0f, 1.5f))
                    }
                } else { items.add(Pair(text, sp)); itemGaps.add(gap) }
                val pieces = ArrayList<FloatArray>()
                val pieceGaps = ArrayList<Int>()   // نمونه‌های سکوت پس از هر تکه
                val pieceItem = ArrayList<Int>()   // اندیس جملهٔ (part) هر تکه؛ جمله‌های خالی تکه ندارند
                var genMs = 0L
                var hit = 0
                for ((ix, itp) in items.withIndex()) {
                    val pt = itp.first; val ps = itp.second
                    if (pt.isBlank() || pt.none { it.isLetterOrDigit() }) continue
                    val psq = String.format(java.util.Locale.US, "%.2f", ps)
                    val key = hashKey("v1|$id|$ns|$nw|" + psq + "|" + pt)
                    // کش دولایه، هر دو جمله‌به‌جمله و مستقل از مرز تکه‌ها: ۱) خروجی پردازش‌شده (پرش از مدل و پاک‌سازی)  ۲) نسخهٔ خام (با تغییر سطح پاک‌سازی مدل دوباره اجرا نمی‌شود)
                    val procOn = lvlClean > 0 || lvlClarity > 0
                    val key2 = if (procOn) hashKey("v2|$id|$ns|$nw|" + psq + "|$lvlClean|$lvlClarity|$strong|" + pt) else key
                    val done = if (procOn) cacheGet(key2, sr) else null
                    if (done != null) {
                        hit++
                        pieces.add(done)
                    } else {
                        val cached = cacheGet(key, sr)
                        val s = if (cached != null) { hit++; cached } else {
                            val t0 = System.nanoTime()
                            val g0 = genPart(eng, pt, ps, ep)
                            val g = if (id.endsWith("-coqui")) trimEdges(g0, sr) else g0   // Coqui: سکوت ابتدا/انتهای هر جمله بلند است
                            genMs += (System.nanoTime() - t0) / 1_000_000L   // زمان خالص مدل (بدون صیقل و انتقال)
                            cachePut(key, sr, g)
                            g
                        }
                        val pr = SpeechClean.process(s, sr, lvlClean, lvlClarity, strong)   // روی نسخهٔ خام کش؛ خودِ کش دست‌نخورده می‌ماند
                        if (procOn && pr !== s) cachePut(key2, sr, pr)
                        pieces.add(pr)
                    }
                    pieceGaps.add((itemGaps[ix] * sr).toInt())
                    pieceItem.add(ix)
                }
                if (pieces.isEmpty()) throw IllegalStateException("متن قابل‌خواندن ندارد")
                var total = 0
                for (x in pieces) total += x.size
                for (j in 0 until pieces.size - 1) total += pieceGaps[j]   // مکث پس از جملهٔ j (نه ثابت؛ از نوع و طول همان جمله)
                val all = FloatArray(total)
                val starts = IntArray(pieces.size)   // نمونهٔ شروع هر تکه در بافر پیش از صیقل
                var pos = 0
                for ((k, x) in pieces.withIndex()) {
                    starts[k] = pos
                    System.arraycopy(x, 0, all, pos, x.size); pos += x.size
                    if (k < pieces.size - 1) pos += pieceGaps[k]   // سکوت (صفر)
                }
                val trim = IntArray(1)   // نمونه‌هایی که صیقل از ابتدای بافر بریده (مرزها باید همین‌قدر جابه‌جا شوند)
                val pol = polish(all, sr, trim)
                val fin = if (fxOn) MasterFx.process(pol, sr, fxPk, fxHs, fxLp, strong) else pol
                for (i in fin.indices) fin[i] *= OUT_TRIM   // r120: ۳dB headroom (پیک ≈ −۳ dBFS)؛ بعد از کش و مسترینگ، پس کش بی‌اثر می‌ماند
                // مرز واقعی شروع هر جمله (کسری از ۰ تا ۱ از کل خروجی)؛ برای هم‌گام‌سازی هایلایت به‌جای تخمین از نسبت نویسه‌ها (T-14)
                // playerPos بومی هم کسری از همین بافر است (مستقل از سرعت پخش)؛ در وب نسبت زمان به مدت صداست
                val marks: JSArray? = if (partsArr != null && partsArr.length() > 0 && fin.isNotEmpty()) {
                    val m = DoubleArray(items.size) { -1.0 }
                    for (k in pieces.indices) m[pieceItem[k]] = ((starts[k] - trim[0]).coerceAtLeast(0).toDouble() / fin.size).coerceIn(0.0, 1.0)
                    var nx = 1.0
                    for (ix in items.size - 1 downTo 0) { if (m[ix] < 0.0) m[ix] = nx else nx = m[ix] }   // جملهٔ بدون تکه: مرزش شروع جملهٔ بعدی
                    JSArray().also { a -> for (v in m) a.put(v) }
                } else null
                if (keep) {
                    val h = pcmPut(fin, sr)
                    val res = JSObject().put("handle", h).put("sampleRate", sr).put("durationMs", fin.size * 1000L / sr).put("genMs", genMs).put("hit", hit).put("parts", pieces.size)
                    if (marks != null) res.put("marks", marks)
                    call.resolve(res)
                } else {
                    val wav = toWav(fin, sr)
                    val res = JSObject().put("wav", Base64.encodeToString(wav, Base64.NO_WRAP)).put("sampleRate", sr).put("genMs", genMs).put("hit", hit).put("parts", pieces.size)
                    if (marks != null) res.put("marks", marks)
                    call.resolve(res)
                }
            } catch (e: Throwable) {
                call.reject(if (e.message == "NATIVE_CANCELLED") "NATIVE_CANCELLED" else "خطای موتور بومی: ${e.message}")
            }
        }
    }

    /** صیقل خروجی هر تکه: حذف سکوت اضافهٔ ابتدا/انتها، محوشدن کوتاه (جلوگیری از «تق» بین جمله‌ها)، یکنواخت‌کردن بلندی صدا با محدودکنندهٔ نرم */
    /** Coqui: هر جمله با سکوت طولانی ابتدا/انتها ساخته می‌شود (فاصلهٔ زیاد بین جمله‌ها)؛ اینجا به ۳۰ms ابتدا و ۶۰ms انتها کوتاه می‌شود. مکث بین جمله‌ها را خود برنامه می‌گذارد. */
    private fun trimEdges(f: FloatArray, sr: Int): FloatArray {
        if (f.size < sr / 4) return f
        var peak = 0f
        for (x in f) { val a = kotlin.math.abs(x); if (a > peak) peak = a }
        if (peak < 1e-3f) return f
        val thr = kotlin.math.max(peak * 0.012f, 0.0015f)   // آستانهٔ پایین تا واج‌های سایشی بریده نشوند
        var s = 0
        while (s < f.size && kotlin.math.abs(f[s]) < thr) s++
        var e = f.size - 1
        while (e > s && kotlin.math.abs(f[e]) < thr) e--
        if (e - s < sr / 10) return f
        s = kotlin.math.max(0, s - (sr * 0.03).toInt())
        e = kotlin.math.min(f.size - 1, e + (sr * 0.06).toInt())
        return f.copyOfRange(s, e + 1)
    }

    private val OUT_TRIM = 0.708f   // −3 dB

    private fun polish(f: FloatArray, sr: Int, trim: IntArray? = null): FloatArray {
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
        trim?.set(0, s)
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

    /* ---- AudioFocus: GAIN هنگام پخش؛ LOSS ⇒ توقف، LOSS_TRANSIENT ⇒ توقف + رویداد playerResume هنگام بازگشت، CAN_DUCK ⇒ بلندی ۳۰٪ ---- */
    private var afReq: android.media.AudioFocusRequest? = null
    @Volatile private var afOn = false
    @Volatile private var afLostT = false
    @Volatile private var afDuck = false
    @Volatile private var afAt = 0L   // لحظهٔ آخرین درخواست فوکوس؛ JS با آن تداخل هم‌فرایندی WebView را از برنامهٔ دیگر تشخیص می‌دهد
    private val afl = AudioManager.OnAudioFocusChangeListener { c ->
        try {
            val act = synchronized(plLock) { plActive }
            when (c) {
                AudioManager.AUDIOFOCUS_LOSS -> { afLostT = false; afAbandon(); if (act) notifyListeners("playerFocus", JSObject().put("kind", "loss").put("dt", android.os.SystemClock.elapsedRealtime() - afAt)) }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (act) { afLostT = true; notifyListeners("playerFocus", JSObject().put("kind", "loss_t").put("dt", android.os.SystemClock.elapsedRealtime() - afAt)) }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { afDuck = true; try { plTrack?.setVolume(0.3f) } catch (e: Throwable) {} }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    afDuck = false; try { plTrack?.setVolume(1f) } catch (e: Throwable) {}
                    if (afLostT) { afLostT = false; notifyListeners("playerResume", JSObject()) }
                }
            }
        } catch (e: Throwable) {}
    }
    private fun afRequest() {
        afLostT = false
        if (afOn) return
        afAt = android.os.SystemClock.elapsedRealtime()
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val r = if (android.os.Build.VERSION.SDK_INT >= 26) {
                val rq = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener(afl).build()
                afReq = rq; am.requestAudioFocus(rq)
            } else @Suppress("DEPRECATION") am.requestAudioFocus(afl, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            afOn = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED   // اگر رد شد (مثلاً تماس) پخش مسدود نمی‌شود؛ دفعهٔ بعد دوباره تلاش می‌شود
        } catch (e: Throwable) {}
    }
    private fun afAbandon() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (android.os.Build.VERSION.SDK_INT >= 26) afReq?.let { am.abandonAudioFocusRequest(it) }
            else @Suppress("DEPRECATION") am.abandonAudioFocus(afl)
        } catch (e: Throwable) {}
        afOn = false; afDuck = false
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
            if (afDuck) try { t.setVolume(0.3f) } catch (e: Throwable) {}
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
        afRequest()
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
        if (!afLostT) afAbandon()
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
        MediaBridge.cb = null; MediaBridge.active = false
        afLostT = false; afAbandon()
        PlayerService.stopSafe(context)
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
    fun process(src: FloatArray, sr: Int, clean: Int, clarity: Int, strong: Boolean = false): FloatArray {
        if ((clean <= 0 && clarity <= 0) || src.size < sr / 20) return src
        var x = src.copyOf()
        try {
            if (clean > 0) highpass(x, sr, 50.0)
            if (x.size >= sr / 4 && x.size <= sr * 45) {
                val cleanDb = when (clean) { 1 -> 8.0; 2 -> 12.0; 3 -> 18.0; else -> 0.0 }
                val beta = when (clean) { 1 -> 1.0; 3 -> 1.5; else -> 1.2 }
                val silDb = when (clean) { 1 -> 3.0; 2 -> 6.0; 3 -> 10.0; else -> 0.0 }
                val cl = when (clarity) { 1 -> 0.6; 2 -> 1.0; 3 -> 1.4; else -> 0.0 }
                x = enhance(x, sr, cleanDb, beta, silDb, cl, strong)
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

    private fun enhance(x: FloatArray, sr: Int, cleanDb0: Double, beta: Double, silDb0: Double, cl: Double, strong: Boolean): FloatArray {
        // مانا: عمق پاک‌سازی ۱۵٪ بیشتر از سایر صداها، ولی دیگر یک سطح بالاتر از انتخاب کاربر نمی‌رود (۱۸dB سقف)؛ شدت زیاد + بهرهٔ بی‌هموار = «نویز موزیکال»/غ‌غ‌غ
        var cleanDb = if (strong) minOf(18.0, cleanDb0 * 1.15) else cleanDb0
        var silDb = silDb0
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
        // فریم‌هایی که فقط صفرِ حاشیه‌اند (pad) در آمار نویز/گفتار حساب نمی‌شوند؛ وگرنه در جمله‌های کوتاه نویز کمتر از واقع برآورد می‌شد
        val order = (0 until frames).filter { it * hop + nn > pad && it * hop < pad + n }.sortedBy { e[it] }
        val nv = order.size
        if (nv < 6) return x
        val nb = minOf(nv, maxOf(3, (nv * 0.03).toInt()))
        var base = 0.0
        for (i in 0 until nb) base += e[order[i]]
        base /= nb
        val kMax = maxOf(3, (nv * 0.25).toInt())
        val sel = ArrayList<Int>()
        for (i in 0 until minOf(kMax, nv)) { val m = order[i]; if (e[m] <= base * 4.0) sel.add(m) }
        if (sel.size < 3) { sel.clear(); for (i in 0 until 3) sel.add(order[i]) }
        val raw = DoubleArray(bins)
        for (m in sel) for (k in 0 until bins) raw[k] += pw[m * bins + k]
        for (k in 0 until bins) raw[k] /= sel.size.toDouble()
        val kHi = minOf(nv, maxOf(3, (nv * 0.4).toInt()))
        var speech = 0.0
        for (i in 0 until kHi) speech += e[order[nv - 1 - i]]
        speech /= kHi
        var en = 0.0
        for (k in 1 until half) en += raw[k]
        if (speech < 1e-9) return x     // بی‌صدا
        // مرجع نویز قابل‌اعتماد نیست (سکوتی در جمله نیست): حذف نویز و دروازه اعمال نمی‌شود (جز مانا: اعتماد تدریجی)
        val snrDb = 10.0 * Math.log10(speech / maxOf(en, 1e-20))
        // مانا: خروجی خام خش بیشتری دارد و شرط سخت‌گیرانهٔ ۲۵dB باعث می‌شد حذف نویز اصلاً اجرا نشود؛ برای آن اعتماد تدریجی (۱۲ تا ۲۵dB) و شدت متناسب
        val relW = if (strong) ((snrDb - 12.0) / 13.0).coerceIn(0.0, 1.0) else if (snrDb >= 25.0) 1.0 else 0.0
        val reliable = relW > 0.0
        if (reliable && relW < 1.0) { cleanDb *= relW; silDb *= relW }
        if (!reliable && cl <= 0.0 && !(strong && cleanDb > 0.0)) return x
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

        // مانا: در فریم‌های واکه‌دار (غیرسایشی) بالای ~۴kHz عملاً فقط خش مدل است، نه گفتار؛ همان‌جا کمی کم می‌شود. فریم‌های سایشی (س، ش، ف) دست‌نخورده می‌مانند.
        val vcut = DoubleArray(frames)
        val vramp = DoubleArray(bins)
        val fz = DoubleArray(frames)      // صامت‌وار (سایشی) بودن هر فریم: ۰ واکه … ۱ سایشی
        val hfDb = if (strong && cleanDb0 > 0.0) minOf(4.0, cleanDb0 * 0.3) else 0.0     // سطح ۲: ۳٫۶dB، سطح ۳: ۴dB (قبلاً تا ۷dB؛ صدا را خفه می‌کرد)
        if (hfDb > 0.0 && k1 < k3 && k3 + 2 < k4) {
            val kv1 = Math.round(4000.0 / df).toInt()
            val kv2 = Math.round(7000.0 / df).toInt()
            for (k in 0 until bins) vramp[k] = ((k - kv1).toDouble() / maxOf(1, kv2 - kv1)).coerceIn(0.0, 1.0)
            for (m in 0 until frames) {
                var elo = 0.0; var ehi = 0.0
                val o = m * bins
                for (k in k1 until k4) { val p = pw[o + k]; if (k < k3) elo += p else ehi += p }
                fz[m] = (((ehi / (elo + 1e-20)) - 0.25) / 0.5).coerceIn(0.0, 1.0)
                vcut[m] = hfDb * (1.0 - fz[m]) * spS[m]
            }
            smoothZp(vcut, 0.25)
        }

        // گذر ۲الف: Wiener تصمیم‌محور → سطح بهرهٔ کل جمله (فریم×باند، لگاریتمی) → هموارسازی → گذر ۲ب: اعمال و بازسازی با هم‌پوشانی
        // علت «غ‌غ‌غ/خش آبکی»: بهرهٔ هر باند در هر فریم (۵٫۸ms) مستقل و فقط با فیلتر ۳تایی در فرکانس هموار می‌شد؛ باندهای منفرد که آستانه را رد می‌کردند
        // به شکل «جزیره‌های» کوتاه (نویز موزیکال) از پردازش رد می‌شدند و روی واکه‌ها مدولاسیون تند در همان ریتم فریم‌ها می‌ساختند.
        // اکنون بهره در لگاریتم هم در فرکانس (۵ باند) و هم در زمان (صفرفاز، رو به جلو و عقب) هموار می‌شود.
        val doWiener = cleanDb > 0.0 && reliable
        val gmin = if (cleanDb > 0.0) Math.pow(10.0, -cleanDb / 20.0) else 1.0
        val gsp = if (cleanDb > 0.0) Math.pow(10.0, -(0.5 * cleanDb) / 20.0) else 1.0   // کفِ بالاتر در فریم‌های گفتار: صامت‌های ضعیف خورده نشوند
        val gspFr = if (strong && cleanDb > 0.0) Math.pow(10.0, -(0.4 * cleanDb) / 20.0) else gsp   // مانا: در فریم‌های سایشی کف بالاتر؛ «س، ش، ف» ضعیف نخورند
        val sil = if (reliable && silDb > 0.0) Math.pow(10.0, -silDb / 20.0) else 1.0
        val alpha = 0.93
        val lg: FloatArray? = if (doWiener) FloatArray(frames * bins) else null   // ln(بهره) ≤ ۰؛ جملهٔ ۱۰ثانیه‌ای ≈ ۴٫۵MB
        if (lg != null) {
            val prevP = DoubleArray(bins)
            for (m in 0 until frames) {
                val gfl = gmin + ((gsp + (gspFr - gsp) * fz[m]) - gmin) * spS[m]
                val o = m * bins
                for (k in 0 until bins) {
                    val p = pw[o + k]
                    val gamma = p / noise[k]
                    val xi = alpha * (prevP[k] / noise[k]) + (1.0 - alpha) * maxOf(gamma - 1.0, 0.0)
                    var v = xi / (1.0 + xi)
                    if (v < gfl) v = gfl
                    if (v > 1.0) v = 1.0
                    prevP[k] = p * v * v
                    lg[o + k] = Math.log(v).toFloat()
                }
            }
            val row = FloatArray(bins)
            for (m in 0 until frames) {   // هموارسازی فرکانسی: میانگین ۵ باند
                val o = m * bins
                for (k in 0 until bins) {
                    val a = maxOf(0, k - 2); val b = minOf(bins - 1, k + 2)
                    var sm = 0f
                    for (j in a..b) sm += lg[o + j]
                    row[k] = sm / (b - a + 1)
                }
                System.arraycopy(row, 0, lg, o, bins)
            }
            val tc = 0.45f   // هموارسازی زمانی یک‌قطبی، رو به جلو و عقب (بدون تأخیر): حدود ۱۰ms؛ آغاز صامت‌ها محو نمی‌شود
            for (m in 1 until frames) { val o = m * bins; val q = o - bins; for (k in 0 until bins) lg[o + k] = lg[q + k] + tc * (lg[o + k] - lg[q + k]) }
            for (m in frames - 2 downTo 0) { val o = m * bins; val q = o + bins; for (k in 0 until bins) lg[o + k] = lg[q + k] + tc * (lg[o + k] - lg[q + k]) }
        }
        val out = DoubleArray(xp.size)
        val norm = DoubleArray(xp.size)
        for (m in 0 until frames) {
            val o = m * hop
            for (i in 0 until nn) { re[i] = xp[o + i] * win[i]; im[i] = 0.0 }
            ft.forward(re, im)
            val silF = 1.0 - (1.0 - sil) * (1.0 - spS[m])
            val riderLin = Math.pow(10.0, rD[m] / 20.0)
            val bcDb = bC[m]
            val vcutDb = vcut[m]
            val go = m * bins
            for (k in 0 until bins) {
                var v = 1.0
                if (lg != null) { v = Math.exp(lg[go + k].toDouble()); if (v > 1.0) v = 1.0 }
                var tot = v * silF * riderLin
                if (bcDb > 0.01 || bcDb < -0.01) { if (taper[k] > 0.0) tot *= Math.pow(10.0, bcDb * taper[k] / 20.0) }
                if (vcutDb > 0.01 && vramp[k] > 0.0) tot *= Math.pow(10.0, -vcutDb * vramp[k] / 20.0)
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

    /**
     * گیت/اکسپندر ملایم پس از کمپرسور: کمپرسور با جبران بلندی (~+۸٫۸dB) خش مکث‌های میان کلمه‌ها را نسبت به واکه‌ها بالا می‌برد.
     * آستانه نسبت به بلندی خود جمله (میانگین ۳۰٪ بلندترین بلوک‌های ۴ms) سنجیده می‌شود؛ کاهش بهره تا floorDb، با پیش‌نگری ۱۲ms و نگه‌داری ۸ms تا آغاز/پایان واژه‌ها بریده نشود.
     */
    private fun gate(x: DoubleArray, sr: Int, relDb: Double, floorDb: Double) {
        val n = x.size
        val blk = maxOf(8, (sr * 0.004).toInt())
        val nb = (n + blk - 1) / blk
        if (nb < 12) return
        val env = DoubleArray(nb)
        for (b in 0 until nb) {
            val a = b * blk; val e = minOf(n, a + blk)
            var s = 0.0
            for (i in a until e) s += x[i] * x[i]
            env[b] = Math.sqrt(s / (e - a))
        }
        val sorted = env.copyOf(); java.util.Arrays.sort(sorted)
        val top = maxOf(3, (nb * 0.3).toInt())
        var ref = 0.0
        for (i in 0 until top) ref += sorted[nb - 1 - i]
        ref /= top
        if (ref < 1e-4) return
        val th = ref * Math.pow(10.0, relDb / 20.0)
        val gmin = Math.pow(10.0, floorDb / 20.0)
        val tg = DoubleArray(nb) { if (env[it] >= th) 1.0 else maxOf(gmin, env[it] / th) }
        val g = DoubleArray(nb)
        for (b in 0 until nb) {
            var mx = 0.0
            for (j in (b - 2)..(b + 3)) if (j in 0 until nb && tg[j] > mx) mx = tg[j]   // نگه‌داری ۸ms + پیش‌نگری ۱۲ms
            g[b] = mx
        }
        for (b in 1 until nb) g[b] = g[b - 1] + 0.4 * (g[b] - g[b - 1])
        for (b in nb - 2 downTo 0) g[b] = g[b + 1] + 0.4 * (g[b] - g[b + 1])
        for (i in 0 until n) {
            val pos = (i - blk / 2.0) / blk
            val b0 = Math.floor(pos).toInt().coerceIn(0, nb - 1)
            val b1 = minOf(nb - 1, b0 + 1)
            val f = (pos - b0).coerceIn(0.0, 1.0)
            x[i] *= g[b0] + (g[b1] - g[b0]) * f
        }
    }

    private fun softClip(v: Double): Float {
        val a = Math.abs(v)
        if (a <= 0.98) return v.toFloat()
        val y = 0.98 + 0.02 * Math.tanh((a - 0.98) / 0.02)
        return (if (v < 0) -y else y).toFloat()
    }

    fun process(src: FloatArray, sr: Int, pk: Float, hs: Float, lp: Float, strong: Boolean = false): FloatArray {
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
        gate(x, sr, if (strong) -32.0 else -34.0, if (strong) -7.0 else -6.0)   // مانا: گیت ملایم‌تر؛ حذف نویز طیفی کار اصلی را می‌کند و گیت تند، صامت‌های ضعیف و پایان واژه را به‌صورت «غ‌غ‌غ» می‌برد
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

    /**
     * WSOLA با اتصال «توان‌ثابت».
     * علت صدای «غغغ/غرغر» هنگام کندکردن: قاب‌ها با پنجرهٔ Hann جمع می‌شدند؛ وقتی دو قاب هم‌فاز نیستند (حروف سایشی مثل س ش خ غ ه ف، یا آغاز/پایان واج)
     * مجموع توان در وسط هر هم‌پوشانی ~۳dB می‌افتد و صدا با ریتم قاب‌ها (~۵۰Hz) بالا و پایین می‌شود.
     * اینجا هر هم‌پوشانی صریح میان‌محو (crossfade) می‌شود و وزن‌ها با همبستگی دو تکه (rho) طوری انتخاب می‌شوند که توان ثابت بماند
     * (rho=۱ → میان‌محو خطی/Hann؛ rho=۰ → میان‌محو هم‌توان). محدودیت «فقط رو به جلو» هم برداشته شد: پنجرهٔ جست‌وجو را تنگ می‌کرد و
     * هم‌ترازی فاز را خراب می‌کرد (در سرعت‌های ≤۰٫۵ غرغر شدید روی واکه‌ها).
     */
    private fun wsola(x: FloatArray, sr: Int, rate: Double): FloatArray {
        val n = x.size
        var nn = Math.round(sr * (if (rate < 1.0) 0.040 else 0.032)).toInt(); nn += nn and 1
        val hs = nn shr 1
        val ha = hs * rate
        val d = Math.round(sr * 0.015).toInt()
        val p = d
        val frames = Math.ceil(n / ha).toInt() + 2
        val xp = FloatArray(n + nn + 2 * d + 8)
        System.arraycopy(x, 0, xp, p, n)
        val u = DoubleArray(hs) { 0.5 - 0.5 * Math.cos(Math.PI * (it + 0.5) / hs) }   // منحنی محوشدن ورودی (۰→۱)
        val out = FloatArray(frames * hs + nn + 8)
        fun sc(cand: Int, rf: Int, st: Int): Double {
            var s = 0.0; var e = 0.0
            var q = 0
            while (q < hs) { val a = xp[cand + p + q]; s += a * xp[rf + p + q]; e += a * a; q += st }
            return s / Math.sqrt(e + 1e-6)
        }
        for (j in 0 until hs) out[j] = xp[p + j]
        var prev = 0
        var last = 0
        for (m in 1 until frames) {
            val tp = Math.round(m * ha).toInt()
            if (tp >= n) break
            val ref = prev + hs                      // ادامهٔ طبیعی قاب قبلی (دُم قبلی)
            val lo = tp - d
            val hi = tp + d
            var best = tp
            var bs = sc(tp, ref, 4)
            var c = lo
            while (c <= hi) { val v = sc(c, ref, 4); if (v > bs) { bs = v; best = c }; c += 2 }
            var k = best
            bs = Double.NEGATIVE_INFINITY
            for (c2 in best - 2..best + 2) {
                if (c2 < lo || c2 > hi) continue
                val v = sc(c2, ref, 1)
                if (v > bs) { bs = v; k = c2 }
            }
            // همبستگی واقعی دُم قبلی و سرِ قاب جدید → وزن‌های توان‌ثابت
            var ab = 0.0; var ea = 0.0; var eb = 0.0
            for (q in 0 until hs) {
                val a = xp[ref + p + q].toDouble(); val b = xp[k + p + q].toDouble()
                ab += a * b; ea += a * a; eb += b * b
            }
            val rho = Math.min(1.0, Math.max(0.0, ab / Math.sqrt(ea * eb + 1e-12)))
            val o = m * hs
            for (q in 0 until hs) {
                val uq = u[q]
                val wo = -rho * uq + Math.sqrt(Math.max(rho * rho * uq * uq - uq * uq + 1.0, 0.0))
                out[o + q] = (xp[ref + p + q] * wo + xp[k + p + q] * uq).toFloat()
            }
            prev = k
            last = m
        }
        val oe = (last + 1) * hs
        for (j in 0 until hs) out[oe + j] = xp[prev + hs + p + j]   // دُم قاب آخر
        val len = Math.min(Math.round(n / rate).toInt(), out.size)
        val res = out.copyOf(len)
        val f = Math.min(len, Math.round(sr * 0.01).toInt())
        for (i in 0 until f) res[len - 1 - i] *= i.toFloat() / f
        return res
    }
}

/** وضعیت مشترک پلاگین ↔ سرویس (اعلان رسانه‌ای). واحد موقعیت/مدت = جمله. */
object MediaBridge {
    @Volatile var cb: ((String, Long) -> Unit)? = null
    @Volatile var active = false
    @Volatile var title = "خوانندهٔ صوتی PDF"
    @Volatile var sub = ""
    @Volatile var total = 0
    @Volatile var index = 0
    @Volatile var playing = false
    @Volatile var rate = 1f
    @Volatile var durMs = 0L
    @Volatile var posMs = 0L
    @Volatile var cover: android.graphics.Bitmap? = null
}

/** سرویس پیش‌زمینهٔ پخش (mediaPlayback) + MediaSession + اعلان MediaStyle (فقط کلاس‌های فریم‌ورک؛ بدون وابستگی جدید).
 *  پخش صوت را AudioTrack در پلاگین انجام می‌دهد؛ این سرویس فقط اعلان/کنترل از راه دور است. بدون mediaStart همان اعلان ساده‌ی قبلی. */
class PlayerService : android.app.Service() {
    companion object {
        const val A_SYNC = "pv.sync"; const val A_CMD = "pv.cmd"; const val NID = 7421; const val CH = "pdfvoice_play"
        /* r113: startForegroundService هایی که onStartCommand‌شان هنوز اجرا نشده؛ تا آن زمان stopService ممنوع (علت ForegroundServiceDidNotStartInTimeException) */
        val PENDING = java.util.concurrent.atomic.AtomicInteger(0)
        @Volatile var pendAt = 0L
        @Volatile var stopReq = false
        fun startFgs(ctx: Context, si: Intent) {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                PENDING.incrementAndGet(); pendAt = android.os.SystemClock.elapsedRealtime(); stopReq = false
                try { ctx.startForegroundService(si) } catch (e: Throwable) { PENDING.updateAndGet { if (it > 0) it - 1 else 0 }; throw e }
            } else ctx.startService(si)
        }
        fun stopSafe(ctx: Context) {
            if (PENDING.get() > 0 && android.os.SystemClock.elapsedRealtime() - pendAt < 10000L) { stopReq = true; return }
            PENDING.set(0); stopReq = false
            try { ctx.stopService(Intent(ctx, PlayerService::class.java)) } catch (e: Throwable) {}
        }
    }
    private var ms: android.media.session.MediaSession? = null
    private val h = android.os.Handler(android.os.Looper.getMainLooper())
    private var clicks = 0
    private val fire = Runnable {
        val c = clicks; clicks = 0
        MediaBridge.cb?.invoke(if (c >= 3) "prev" else if (c == 2) "next" else if (MediaBridge.playing) "pause" else "play", 0L)
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    private fun session(): android.media.session.MediaSession {
        ms?.let { return it }
        val s = android.media.session.MediaSession(this, "PdfVoice")
        if (android.os.Build.VERSION.SDK_INT < 26) try { @Suppress("DEPRECATION") s.setFlags(3) } catch (e: Throwable) {}
        s.setCallback(object : android.media.session.MediaSession.Callback() {
            override fun onPlay() { MediaBridge.cb?.invoke("play", 0L) }
            override fun onPause() { MediaBridge.cb?.invoke("pause", 0L) }
            override fun onSkipToNext() { MediaBridge.cb?.invoke("next", 0L) }
            override fun onSkipToPrevious() { MediaBridge.cb?.invoke("prev", 0L) }
            override fun onSeekTo(pos: Long) { MediaBridge.cb?.invoke("seek", pos) }
            override fun onStop() { MediaBridge.cb?.invoke("stop", 0L) }
            /* هدست: یک‌بار = پخش/توقف، دوبار = بعدی، سه‌بار = قبلی */
            override fun onMediaButtonEvent(i: Intent): Boolean {
                val ev = if (android.os.Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent::class.java)
                         else @Suppress("DEPRECATION") i.getParcelableExtra<android.view.KeyEvent>(Intent.EXTRA_KEY_EVENT)
                if (ev != null && (ev.keyCode == android.view.KeyEvent.KEYCODE_HEADSETHOOK || ev.keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
                    if (ev.action == android.view.KeyEvent.ACTION_DOWN && ev.repeatCount == 0) { clicks++; h.removeCallbacks(fire); h.postDelayed(fire, 380) }
                    return true
                }
                return super.onMediaButtonEvent(i)
            }
        })
        s.isActive = true
        ms = s
        return s
    }

    private fun pi(c: String, rc: Int): android.app.PendingIntent =
        android.app.PendingIntent.getService(this, rc, Intent(this, PlayerService::class.java).setAction(A_CMD).putExtra("c", c),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)

    @Suppress("DEPRECATION")
    private fun act(icon: Int, label: String, c: String, rc: Int) = android.app.Notification.Action.Builder(icon, label, pi(c, rc)).build()

    private fun build(): android.app.Notification {
        val st = MediaBridge
        val b = if (android.os.Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(this, CH) else android.app.Notification.Builder(this)
        b.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(st.title).setOnlyAlertOnce(true).setShowWhen(false)
            .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch != null) b.setContentIntent(android.app.PendingIntent.getActivity(this, 0, launch, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
        if (!st.active) return b.setContentText("در حال خواندن…").setOngoing(true).build()
        val s = session()
        s.setMetadata(android.media.MediaMetadata.Builder()
            .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, st.title)
            .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, st.sub)
            .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, "خوانندهٔ صوتی PDF")
            .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, if (st.durMs > 0) st.durMs else st.total.toLong() * 1000L)
            .also { mb -> st.cover?.let { mb.putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }.build())
        val A = android.media.session.PlaybackState.ACTION_PLAY or android.media.session.PlaybackState.ACTION_PAUSE or android.media.session.PlaybackState.ACTION_PLAY_PAUSE or
            android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT or android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            android.media.session.PlaybackState.ACTION_SEEK_TO or android.media.session.PlaybackState.ACTION_STOP
        /* r113: موقعیت/مدت بر حسب میلی‌ثانیهٔ تخمینی؛ سرعت = نرخ پخش تا نوار بین به‌روزرسانی‌ها خودش جلو برود، هنگام مکث ۰ */
        s.setPlaybackState(android.media.session.PlaybackState.Builder().setActions(A)
            .setState(if (st.playing) android.media.session.PlaybackState.STATE_PLAYING else android.media.session.PlaybackState.STATE_PAUSED,
                if (st.durMs > 0) st.posMs else st.index.toLong() * 1000L, if (st.playing) st.rate else 0f).build())
        val rt = String.format(java.util.Locale.US, "%.2f", st.rate).trimEnd('0').trimEnd('.')
        st.cover?.let { b.setLargeIcon(it) }
        b.setContentText(st.sub).setOngoing(st.playing).setDeleteIntent(pi("stop", 5))
            .addAction(act(android.R.drawable.ic_media_previous, "جملهٔ قبل", "prev", 1))
            .addAction(if (st.playing) act(android.R.drawable.ic_media_pause, "توقف", "pause", 2) else act(android.R.drawable.ic_media_play, "پخش", "play", 2))
            .addAction(act(android.R.drawable.ic_media_next, "جملهٔ بعد", "next", 3))
            .addAction(act(android.R.drawable.ic_menu_recent_history, "سرعت ${rt}×", "speed", 4))
            .addAction(act(android.R.drawable.ic_menu_close_clear_cancel, "توقف کامل", "stop", 5))
            .setStyle(android.app.Notification.MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(0, 1, 2))
        return b.build()
    }

    private fun end() {
        MediaBridge.active = false; MediaBridge.playing = false
        try { if (android.os.Build.VERSION.SDK_INT >= 24) stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true) } catch (e: Throwable) {}
        stopSelf()
    }

    /* r113: اعلان حداقلی و فوری برای startForeground؛ هیچ کار سنگینی (MediaSession و…) قبل از آن نیست */
    private fun quick(): android.app.Notification {
        val b = if (android.os.Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(this, CH) else android.app.Notification.Builder(this)
        return b.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(MediaBridge.title).setContentText("در حال خواندن…")
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            try { nm.createNotificationChannel(android.app.NotificationChannel(CH, "پخش صوتی", android.app.NotificationManager.IMPORTANCE_LOW).also { it.setShowBadge(false) }) } catch (e: Throwable) {}
        }
        val cmd = intent?.action == A_CMD
        var n0: android.app.Notification? = null
        if (!cmd) {   /* هر startForegroundService باید همین‌جا و بی‌قید‌وشرط startForeground بگیرد */
            try {
                n0 = quick()
                if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(NID, n0, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                else startForeground(NID, n0)
            } catch (e: Throwable) { n0 = null }
            PENDING.updateAndGet { if (it > 0) it - 1 else 0 }
            if (stopReq && PENDING.get() == 0) { stopReq = false; end(); return START_NOT_STICKY }   /* توقفِ به‌تعویق‌افتاده: حالا امن است */
        }
        if (cmd) {   /* دکمهٔ اعلان: فقط به JS منتقل می‌شود؛ وضعیت جدید را JS با mediaUpdate می‌فرستد */
            val cb = MediaBridge.cb
            if (cb == null) { end() } else cb.invoke(intent?.getStringExtra("c") ?: "", 0L)
            return START_NOT_STICKY
        }
        val n = try { build() } catch (e: Throwable) { n0 }
        if (n == null) { stopSelf(); return START_NOT_STICKY }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(NID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(NID, n)
            if (MediaBridge.active && !MediaBridge.playing) {   /* مکث: اعلان قابل سوایپ می‌شود، پیش‌زمینه آزاد */
                if (android.os.Build.VERSION.SDK_INT >= 24) stopForeground(android.app.Service.STOP_FOREGROUND_DETACH) else @Suppress("DEPRECATION") stopForeground(false)
                nm.notify(NID, n)
            }
        } catch (e: Throwable) { if (n0 == null) stopSelf() }
        return START_NOT_STICKY
    }

    /** حذف برنامه از لیست اخیر: اعلان شبح نماند */
    override fun onTaskRemoved(rootIntent: Intent?) {
        try { MediaBridge.cb?.invoke("stop", 0L) } catch (e: Throwable) {}
        end()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        h.removeCallbacks(fire)
        try { ms?.isActive = false; ms?.release() } catch (e: Throwable) {}
        ms = null
        super.onDestroy()
    }
}
