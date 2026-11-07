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
import com.k2fsa.sherpa.onnx.GenerationConfig
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
import com.pdfvoice.phon.ManaPhon
import com.pdfvoice.phon.Phonemizer

/**
 * Piper (VITS) فارسی به‌صورت بومی با sherpa-onnx.
 * متدها: ping, stored, bundled, download({id}) = نصب صدا از فایل همراه برنامه (بدون اینترنت), synthesize({id, text, noiseScale?, noiseScaleW?, speed?}), remove({id})
 * فهرست PDFهای دستگاه (صفحهٔ اصلی): listPdfs, pdfRequest
 * ذخیرهٔ متن: pickTextFolder, saveText({name,text,uri?})
 * ذخیرهٔ فایل دودویی (r165، صدای ضبط‌شده): saveBinaryOpen({name,uri?}) → {sid,name}، saveBinaryWrite({sid,data:base64})، saveBinaryClose({sid,abort?}) — تکه‌تکه، بدون نگه‌داشتن کل فایل در حافظه
 * پوشهٔ دلخواه کاربر: pickFolder, folder, exportVoices({ids?}), importVoices({ids?, overwrite?})
 * رویداد: "progress" {loaded, total}
 * r17: clean=۲ ← حذف‌نویز عصبی DPDFNet ۸۵٪ + کلاسیک، clean=۳ ← عصبی ۱۰۰٪ + کلاسیک (مدل: assets/denoise/dpdfnet_baseline.onnx؛ وضعیت: diag().nd)
 * r24 (رنگ هر جمله): با col=true، هر جمله (part) پیش از چسباندن و پیش از HD سنجیده و به میانهٔ ۱۴ جملهٔ اخیر همان گوینده نزدیک می‌شود؛ ۷ باند اصلی با پیک ملایم (حداکثر ±۲٫۵dB) و بالاها (۶٫۵–۱۰٫۵kHz، «خش/هوا») با شلف ۶٫۵kHz (کاهش تا ۶dB، افزایش تا ۴dB)؛ ColorSv.measure، PieceColor و MasterFx.hfShelf. بدون پارامتر تازه؛ نتیجهٔ کش دست‌نخورده می‌ماند
 * r23 (EQ پویای ناظر رنگ): synthesize({..., cbT?: [۷ عدد]}) → اگر cbT (میانهٔ شکل طیف ۷ باند، ۱۵ تکهٔ اخیر از سمت JS) برسد، هر باند با انحراف بیش از ۰٫۵dB با پیک ملایم (حداکثر ±۳dB) اصلاح می‌شود (به‌جای شلف شیب)؛ hs.bd شکل ۷ باند و hs.bg گین‌ها؛ ColorSv.shape/slope و MasterFx.bandEq
 * r19 (ناظر رنگ): synthesize({..., col?: Boolean, clT?: dB/oct}) → شیب طیف واک‌دار تکه (hs.ct) را می‌سنجد؛ اگر clT (میانهٔ ۱۵ تکهٔ اخیر از سمت JS) برسد و انحراف > ۰٫۳ dB/oct باشد، شلف بالای ملایم (حداکثر ±۲٫۵ dB، hs.cg) رنگ را به میانه نزدیک می‌کند؛ ColorSv و MasterFx.tiltShelf
 * کیفیت صدا: synthesize({..., clean?: 0..3, clarity?: 0..3}) → حذف نویز و ارتقای شفافیت/رسایی؛ ۰ خاموش، ۲ متعادل (پیش‌فرض)؛ جزئیات در SpeechClean (انتهای فایل)
 * مسترینگ: synthesize({..., master?: Boolean, fxPk?: dB, fxHs?: dB, fxLp?: Hz}) → EQ + کمپرسور + لیمیتر روی خروجی نهایی؛ جزئیات در MasterFx
 * اکولایزر کاربر (r14): synthesize({..., eq?: {pre: dB, lc: Hz, hc: Hz, q, limiter: 0|1, bands: [[Hz, dB] × ۱۰]}}) → تمام ۱۰ باند + تقویت کلی + قطع بم/زیر + محافظ برش؛ مستقل از master، پس از مسترینگ؛ MasterFx.eq
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
 *
 * r181: sherpa-onnx 2.x. espeak-ng از خود sherpa حذف شده؛ آوانگاری فارسی را piper-phonemize (+espeak-ng) از طریق Phonemizer انجام می‌دهد
 *   و خروجی (کدپوینت آواها برای هر جمله) به‌صورت GenerationConfig.phonemeCodepoints به sherpa داده می‌شود. کاملاً آفلاین: کتابخانه و دادهٔ espeak داخل APK هستند.
 */
@CapacitorPlugin(name = "PiperNative")
class PiperNativePlugin : Plugin() {

    private val io = Executors.newSingleThreadExecutor()     // دانلود/استخراج
    private val bio = Executors.newSingleThreadExecutor()    // r165: نوشتن فایل دودویی (جدا از io تا دانلود صدا ذخیره را معطل نکند)
    @Volatile private var underruns = 0                      // مجموع underrun های AudioTrack از شروع برنامه (نشانهٔ لکنت پخش)
    // r138 (ناظر لکنت): بافر AudioTrack با هر underrun ۱۰۰ms بزرگ‌تر می‌شود (سقف ۶۰۰ms)؛ ۳ دقیقه بدون لکنت ⇒ بازگشت به ۲۵۰ms.
    // هزینه: رویداد playerPos و هایلایت تا اندازهٔ بافر جلوتر از صدای شنیده‌شده‌اند (≤ ۳۵۰ms بیشتر از حالت عادی).
    private val BUF_MIN_MS = 250
    private val BUF_MAX_MS = 600
    private val IDLE_GRACE_MS = 2500L   // r166: وقتی ساخت صدا از پخش عقب می‌ماند، ترک (متوقف‌شده) تا این مدت زنده می‌ماند تا برای هر جمله دوباره ساخته نشود
    @Volatile private var bufMs = 250
    @Volatile private var bufGrow = 0
    @Volatile private var bufAt = 0L
    // r138 (ناظر کش): شمارندهٔ برخورد/خطا/حذف از شروع برنامه
    private val cHit = java.util.concurrent.atomic.AtomicLong(0)
    private val cMiss = java.util.concurrent.atomic.AtomicLong(0)
    private val cEvict = java.util.concurrent.atomic.AtomicLong(0)

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
        io.execute {   // صداهای Coqui حذف شده‌اند؛ مدل‌های نصب‌شدهٔ قبلی (صدها مگابایت) پاک می‌شوند
            try { for (cid in listOf("fa_IR-negin-coqui", "fa_IR-sahar-coqui")) { voiceDir(cid).deleteRecursively(); File(root(), "$cid.dl").delete(); File(root(), "$cid.tok.dl").delete() } } catch (e: Throwable) {}
        }
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
        call.resolve(JSObject().put("underruns", underruns).put("sampleRate", sampleRate).put("sdk", android.os.Build.VERSION.SDK_INT).put("bufMs", bufMs).put("bufGrow", bufGrow)
            .put("abi", android.os.Build.SUPPORTED_ABIS.joinToString(",")).put("model", android.os.Build.MODEL).put("nd", NeuralDenoise.status(context)).put("ndm", NeuralDenoise.available(context)))
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
        call.resolve(JSObject().put("ok", true).put("player", true).put("build", "kt-r24 · sherpa-" + ENGINE_VER + " · phon-" + phonTag()).put("media", true).put("ndm", NeuralDenoise.available(context)).put("mph", ManaPhon.status(context)))
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

    // ---------- r165: ذخیرهٔ فایل دودویی (صدای ضبط‌شدهٔ خوانش) تکه‌تکه ----------
    // saveBinaryOpen({name, uri?}) → {sid, name}: با uri (پوشهٔ SAF از pickTextFolder) بی‌صدا در همان پوشه؛ بدون uri پنجرهٔ «ذخیره به‌عنوان» (لغو ⇒ reject "CANCELLED")
    // saveBinaryWrite({sid, data}) : data = base64 یک تکه؛ به انتهای فایل افزوده می‌شود (JS تکه‌های ~۵۰۰ کیلوبایتی می‌فرستد)
    // saveBinaryClose({sid, abort?}) : بستن؛ abort=true فایل ناقص را حذف می‌کند
    private val bins = java.util.concurrent.ConcurrentHashMap<Int, java.io.OutputStream>()
    private val binUris = java.util.concurrent.ConcurrentHashMap<Int, Uri>()
    private val binSeq = AtomicInteger(0)
    private var pendBinName = ""

    private fun binMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "m4a" -> "audio/mp4"
        "wav" -> "audio/x-wav"
        "mp3" -> "audio/mpeg"
        else -> "application/octet-stream"
    }

    private fun binStart(u: Uri, name: String): JSObject {
        if (bins.size >= 4) throw IllegalStateException("تعداد فایل‌های بازِ ذخیره زیاد است")
        val os = context.contentResolver.openOutputStream(u, "wt") ?: throw IllegalStateException("نوشتن در فایل ممکن نشد")
        val sid = binSeq.incrementAndGet()
        bins[sid] = BufferedOutputStream(os, 1 shl 16)
        binUris[sid] = u
        return JSObject().put("sid", sid).put("name", name)
    }

    private fun binDrop(sid: Int, abort: Boolean) {
        val os = bins.remove(sid)
        val u = binUris.remove(sid)
        try { os?.close() } finally {
            if (abort && u != null) { try { DocumentFile.fromSingleUri(context, u)?.delete() } catch (e: Exception) {} }
        }
    }

    @PluginMethod
    fun saveBinaryOpen(call: PluginCall) {
        val name = (call.getString("name") ?: "audio.m4a").map { c -> if (c in "/\\:*?\"<>|") '_' else c }.joinToString("")
        val uri = call.getString("uri") ?: ""
        val mime = binMime(name)
        if (uri.isNotEmpty()) {
            bio.execute {
                try {
                    val u = Uri.parse(uri)
                    val ok = context.contentResolver.persistedUriPermissions.any { it.uri == u && it.isWritePermission }
                    if (!ok) { call.reject("دسترسی به پوشه از بین رفته؛ مسیر را دوباره انتخاب کنید"); return@execute }
                    val dir = DocumentFile.fromTreeUri(context, u)
                    if (dir == null || !dir.canWrite()) { call.reject("پوشه قابل نوشتن نیست؛ مسیر را دوباره انتخاب کنید"); return@execute }
                    val f = dir.findFile(name) ?: dir.createFile(mime, name)
                    if (f == null) { call.reject("ساخت فایل ناموفق بود"); return@execute }
                    call.resolve(binStart(f.uri, f.name ?: name))
                } catch (e: Exception) { call.reject("ذخیره ناموفق بود: " + (e.message ?: "")) }
            }
            return
        }
        pendBinName = name
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mime).putExtra(Intent.EXTRA_TITLE, name)
        startActivityForResult(call, i, "binSaveResult")
    }

    @ActivityCallback
    private fun binSaveResult(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        val u = result.data?.data
        val nm = pendBinName; pendBinName = ""
        if (result.resultCode != Activity.RESULT_OK || u == null) { call.reject("CANCELLED"); return }
        bio.execute {
            try { call.resolve(binStart(u, DocumentFile.fromSingleUri(context, u)?.name ?: nm)) }
            catch (e: Exception) { call.reject("ذخیره ناموفق بود: " + (e.message ?: "")) }
        }
    }

    @PluginMethod
    fun saveBinaryWrite(call: PluginCall) {
        val sid = call.getInt("sid") ?: -1
        val data = call.getString("data") ?: ""
        bio.execute {
            try {
                val os = bins[sid] ?: throw IllegalStateException("جریان ذخیره بسته یا نامعتبر است")
                val b = Base64.decode(data, Base64.DEFAULT)
                os.write(b)
                call.resolve(JSObject().put("n", b.size))
            } catch (e: Exception) {
                try { binDrop(sid, true) } catch (x: Exception) {}
                call.reject("نوشتن ناموفق بود: " + (e.message ?: ""))
            }
        }
    }

    @PluginMethod
    fun saveBinaryClose(call: PluginCall) {
        val sid = call.getInt("sid") ?: -1
        val abort = call.getBoolean("abort") ?: false
        bio.execute {
            try { binDrop(sid, abort); call.resolve(JSObject().put("ok", true)) }
            catch (e: Exception) { call.reject("بستن فایل ناموفق بود: " + (e.message ?: "")) }
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

    private fun sumFile(id: String) = File(voiceDir(id), ".sum")
    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        java.io.FileInputStream(f).use { ins ->
            val b = ByteArray(1 shl 16)
            while (true) { val n = ins.read(b); if (n < 0) break; md.update(b, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** r138 (ناظر یکپارچگی مدل): verify({id}) ⇒ {state: ok|baseline|bad|broken|missing, why?, size, ms}.
     *  مرجع = SHA-256 فایل onnx در اولین بررسی (<voice>/.sum = «اندازه|هش»، همراه خروجی exportVoices می‌رود)؛ هیچ هش از پیش‌تعریف‌شده‌ای وجود ندارد.
     *  فقط گزارش می‌دهد؛ هیچ فایلی حذف یا بازسازی نمی‌شود. روی رشتهٔ io اجرا می‌شود. */
    @PluginMethod
    fun verify(call: PluginCall) {
        val id = call.getString("id") ?: return call.reject("id")
        io.execute {
            try {
                val t0 = System.nanoTime()
                fun ms() = (System.nanoTime() - t0) / 1_000_000L
                if (!okMark(id).exists()) { call.resolve(JSObject().put("state", "missing")); return@execute }
                val d = voiceDir(id)
                val o = findOnnx(d)
                if (o == null || !File(findRoot(d), "tokens.txt").isFile) {
                    call.resolve(JSObject().put("state", "broken").put("why", if (o == null) "فایل onnx نیست" else "tokens.txt نیست")); return@execute
                }
                val size = o.length()
                val sf = sumFile(id)
                val p = if (sf.isFile) sf.readText().trim().split("|") else emptyList()
                if (p.size != 2) {   // مرجع نیست یا ناخوانا ⇒ مرجع تازه
                    val h0 = sha256(o)
                    sf.writeText("$size|$h0")
                    call.resolve(JSObject().put("state", "baseline").put("size", size).put("sum", h0.take(12)).put("ms", ms())); return@execute
                }
                if (p[0].toLongOrNull() != size) {
                    call.resolve(JSObject().put("state", "bad").put("why", "اندازهٔ فایل با مرجع نمی‌خواند").put("size", size).put("ms", ms())); return@execute
                }
                val h1 = sha256(o)
                val ok = h1 == p[1]
                call.resolve(JSObject().put("state", if (ok) "ok" else "bad").put("why", if (ok) "" else "هش فایل با مرجع نمی‌خواند").put("size", size).put("sum", h1.take(12)).put("ms", ms()))
            } catch (e: Throwable) { call.reject("بررسی ناموفق: ${e.message}") }
        }
    }

    /** r144 (بازسازی ایمن): repair({id, on:true}) ⇒ موتور آزاد و نشانهٔ .ok برداشته می‌شود تا download دوباره اجرا شود (داده و .sum دست‌نخورده)؛
     *  repair({id, on:false}) ⇒ اگر دانلود شکست خورد و مدل قبلی هست، .ok برمی‌گردد؛ اگر دانلود موفق بوده .ok خودش هست و کاری نمی‌کند. */
    @PluginMethod
    fun repair(call: PluginCall) {
        val id = call.getString("id") ?: return call.reject("id")
        io.execute {
            try {
                if (call.getBoolean("on") == true) { if (ttsId == id) releaseEngine(id); okMark(id).delete() }
                else if (!okMark(id).exists() && findOnnx(voiceDir(id)) != null) okMark(id).writeText("1")
                call.resolve()
            } catch (e: Throwable) { call.reject("repair: ${e.message}") }
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

    private val ESPEAK_DONOR = "fa_IR-gyro-medium"   // فقط برای کپی espeak-ng-data (مانا و gyro)

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
                if (espeakSrc == null && !phonAsset()) {   // r181: با دادهٔ espeak داخل APK دیگر دانلود صدای gyro فقط برای espeak لازم نیست
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
                if (espeakSrc == null && !phonAsset()) throw IllegalStateException("espeak-ng-data پیدا نشد")
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
                espeakSrc?.copyRecursively(File(dst, "espeak-ng-data"), overwrite = true)
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
            throw IllegalStateException("این مدل برای sherpa-onnx آماده نشده (متادیتا ندارد)؛ مدل دیگری برای آن لازم است")
        val ownEspeak = File(r, "espeak-ng-data")
        val espeak: File? = if (ownEspeak.isDirectory) ownEspeak else sharedEspeak(id)
        // مدل‌های حرفی (characters) آوانگار نمی‌خواهند؛ بقیه (piper/coqui) متنشان باید قبل از sherpa آوانگاری شود (sherpa ≥ 2.0 خودش نمی‌کند)
        val needPhon = (flags and 2) == 0 || (flags and 4) != 0
        if (needPhon) ensurePhonemizer(espeak)
        val vits = OfflineTtsVitsModelConfig(
            model = onnx.absolutePath,
            tokens = File(r, "tokens.txt").absolutePath,
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
        ttsPhon = needPhon; ttsVoice = if (needPhon) onnxVoice(onnx) else "fa"
        ttsMana = needPhon && id.contains("mana", ignoreCase = true)
        return t
    }

    // ---------- r181: آوانگار بیرونی (piper-phonemize + espeak-ng) ----------
    private val ENGINE_VER = "__SHERPA_VER__"   // inject-native.sh جایگزین می‌کند
    private val DSP_VER = 1   // با هر تغییر الگوریتم SpeechClean/peakGuard/ND بالا ببر؛ وارد کلید کش پردازش‌شده می‌شود
    /** آیا دادهٔ espeak هم‌نسخهٔ کتابخانه داخل APK هست (پس برای نصب صدا لازم نیست espeak از جای دیگر بیاید) */
    private fun phonAsset(): Boolean = try { (context.assets.list("phonemizer/espeak-ng-data")?.size ?: 0) > 0 } catch (e: Throwable) { false }
    private val phonLock = Any()
    @Volatile private var ttsPhon = false       // آیا متن این موتور باید قبل از sherpa آوانگاری شود
    @Volatile private var ttsVoice = "fa"       // نام صدای espeak (از متادیتای مدل؛ پیش‌فرض fa)
    @Volatile private var ttsMana = false       // r187: صدای مانا؟ (واج‌آرایی اختصاصی: کسرهٔ اضافه با ALBERT پیش از espeak)
    private val voiceCache = HashMap<String, String>()

    private fun phonTag(): String = try {
        val v = Phonemizer.version()
        if (v.isNotEmpty()) v else "ERR:" + (Phonemizer.loadError() ?: "no-version")
    } catch (e: Throwable) { "ERR" }

    private fun copyAssetDir(am: android.content.res.AssetManager, src: String, dst: File) {
        val names = am.list(src) ?: emptyArray()
        if (names.isEmpty()) {
            dst.parentFile?.mkdirs()
            try { am.open(src).use { i -> FileOutputStream(dst).use { o -> i.copyTo(o, 64 * 1024) } } }
            catch (e: java.io.FileNotFoundException) { dst.mkdirs() }   // پوشهٔ خالی
        } else {
            dst.mkdirs()
            for (n in names) copyAssetDir(am, "$src/$n", File(dst, n))
        }
    }

    /**
     * espeak-ng-data را در مسیر ثابتِ filesDir آماده و Phonemizer را راه‌اندازی می‌کند.
     * منبع: ۱) دادهٔ هم‌نسخهٔ خود کتابخانه (داخل APK؛ کاملاً آفلاین)  ۲) espeak-ng-data یکی از صداهای نصب‌شده.
     */
    private fun ensurePhonemizer(voiceEspeak: File?) {
        synchronized(phonLock) {
            val shared = File(context.filesDir, "espeak-ng-data-shared")
            val mark = File(context.filesDir, "espeak-ng-data-shared.ok")
            val hasAsset = phonAsset()
            val want = if (hasAsset) "asset|$ENGINE_VER" else "voice"
            val have = if (mark.exists() && File(shared, "phondata").isFile) mark.readText() else ""
            if (have != want && !(want == "voice" && have.isNotEmpty())) {
                mark.delete(); shared.deleteRecursively()
                if (hasAsset) copyAssetDir(context.assets, "phonemizer/espeak-ng-data", shared)
                else if (voiceEspeak != null) voiceEspeak.copyRecursively(shared, overwrite = true)
                else throw IllegalStateException("این صدا به دادهٔ espeak نیاز دارد؛ اول یکی از صداهای دیگر (مثلاً gyro) را دانلود کنید")
                if (!File(shared, "phondata").isFile) { shared.deleteRecursively(); throw IllegalStateException("دادهٔ espeak ناقص است (phondata نیست)") }
                mark.writeText(want)
            }
            try { Phonemizer.init(shared.absolutePath) }
            catch (e: Throwable) { mark.delete(); throw e }
        }
    }

    /** نام صدای espeak از متادیتای ONNX (کلید voice)؛ اگر نبود fa */
    private fun onnxVoice(onnx: File): String {
        val key = onnx.absolutePath + "|" + onnx.length()
        voiceCache[key]?.let { return it }
        var v = "fa"
        try {
            val len = onnx.length(); val from = maxOf(0L, len - 4_000_000L); val n = (len - from).toInt()
            val buf = ByteArray(n)
            java.io.RandomAccessFile(onnx, "r").use { it.seek(from); it.readFully(buf) }
            // StringStringEntryProto: 0x0A len "voice" 0x12 len value
            val pat = byteArrayOf(0x0A, 0x05, 0x76, 0x6F, 0x69, 0x63, 0x65, 0x12)
            val at = indexOf(buf, n, pat)
            if (at >= 0 && at + pat.size < n) {
                val l = buf[at + pat.size].toInt() and 0xFF; val s = at + pat.size + 1
                if (l in 1..24 && s + l <= n) {
                    val x = String(buf, s, l, Charsets.ISO_8859_1)
                    if (Regex("[A-Za-z][A-Za-z0-9_-]*").matches(x)) v = x
                }
            }
        } catch (e: Throwable) { }
        voiceCache[key] = v
        return v
    }

    /** GenerationConfig.phonemeCodepoints (آرایهٔ کدپوینت آواها برای هر جمله) را با بازتاب می‌گذارد؛ اگر نوع فیلد غیرمنتظره بود خطای روشن می‌دهد */
    private fun setPhonemeCodepoints(cfg: GenerationConfig, ph: Array<IntArray>) {
        val f = try { GenerationConfig::class.java.getDeclaredField("phonemeCodepoints") }
                catch (e: NoSuchFieldException) { throw IllegalStateException("sherpa-onnx این نسخه phonemeCodepoints ندارد (espeak/آوانگار بیرونی ممکن نیست)") }
        f.isAccessible = true
        if (f.type != Array<IntArray>::class.java)
            throw IllegalStateException("نوع phonemeCodepoints در این نسخهٔ sherpa-onnx پشتیبانی نمی‌شود: ${f.type.name}")
        f.set(cfg, ph)
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
            val oldLen = if (dst.isFile) dst.length() else 0L
            if (!tmp.renameTo(dst)) { tmp.delete(); return }
            synchronized(cacheLock) { if (cacheBytes >= 0) cacheBytes += dst.length() - oldLen; trimCache() }
        } catch (e: Throwable) { /* نبود جا یا خطای دیسک نباید خواندن را متوقف کند */ }
    }

    /** باید زیر cacheLock صدا زده شود: وقتی از سقف گذشت، قدیمی‌ترین فایل‌ها تا ۸۵٪ سقف حذف می‌شوند */
    private fun trimCache() {
        if (cacheBytes < 0) {
            val now = System.currentTimeMillis()
            cacheRoot().listFiles()?.forEach { if (it.name.endsWith(".pcm.tmp") && now - it.lastModified() > 10 * 60 * 1000L) it.delete() }   // یتیم‌های نوشتن نیمه‌کاره
            cacheBytes = cacheFiles().sumOf { it.length() }
        }
        val max = cacheMax()
        if (cacheBytes <= max) return
        var total = cacheBytes
        val goal = (max * 0.85).toLong()
        for (f in cacheFiles().sortedBy { it.lastModified() }) {
            if (total <= goal) break
            val l = f.length()
            if (f.delete()) { total -= l; cEvict.incrementAndGet() }
        }
        cacheBytes = total
    }

    /** ساخت یک جمله با تولید جریانی (callback): با لغو، همان لحظه‌ای که تکهٔ جاری برسد ساخت قطع می‌شود */
    private fun genPart(eng: OfflineTts, text: String, sp: Float, ep: Int, manaPh: Boolean): FloatArray {
        val acc = ArrayList<FloatArray>()
        val cb: (FloatArray) -> Int = { s ->
            acc.add(s.copyOf())
            if (ep != epoch.get()) 0 else 1      // ۰ = توقف
        }
        val a = if (ttsPhon) {
            // r181: آوانگاری بیرونی؛ جمله‌های خالی حذف می‌شوند (برای هر جمله یک آرایهٔ کدپوینت)
            // r187: مانا ← واج‌آرایی اختصاصی (کسرهٔ اضافهٔ مدلی روی متن)؛ هر خطا ← همان متن
            val src = if (ttsMana && manaPh) ManaPhon.prepare(context, text) else text
            val ph = Phonemizer.phonemize(src, ttsVoice).filter { it.isNotEmpty() }.toTypedArray()
            if (ph.isEmpty()) throw IllegalStateException("آوانگاری espeak برای این متن چیزی نداد")
            if (ep != epoch.get()) throw IllegalStateException("NATIVE_CANCELLED")
            val gc = GenerationConfig(silenceScale = 0.2f, speed = sp, sid = 0)
            setPhonemeCodepoints(gc, ph)
            eng.generateWithConfigAndCallback("", gc, cb)
        } else {
            eng.generateWithCallback(text = text, sid = 0, speed = sp, callback = cb)
        }
        if (ep != epoch.get()) throw IllegalStateException("NATIVE_CANCELLED")
        if (a.samples.isNotEmpty()) return peakGuard(a.samples)
        var n = 0
        for (x in acc) n += x.size
        val out = FloatArray(n)
        var p = 0
        for (x in acc) { System.arraycopy(x, 0, out, p, x.size); p += x.size }
        return peakGuard(out)
    }

    /** خروجی خام VITS گاهی از ±۱ بیرون می‌زند؛ کش ۱۶بیتی آن را سخت می‌بُرد (اعوجاج)، و نوبت اول (float) با دفعات بعد (بریده) فرق می‌کرد.
     *  قبل از هر استفاده/کش، فقط در صورت لزوم کل جمله با یک ضریب کم می‌شود (نه بریدن)؛ بلندی نهایی را بعداً صیقل یکسان می‌کند. */
    private fun peakGuard(f: FloatArray): FloatArray {
        var pk = 0f
        for (x in f) { val a = kotlin.math.abs(x); if (a > pk) pk = a }
        if (pk <= 0.99f) return f
        val g = 0.99f / pk
        for (i in f.indices) f[i] *= g
        return f
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
                call.resolve(JSObject().put("bytes", total).put("files", fs.size).put("max", cacheMax()).put("hits", cHit.get()).put("misses", cMiss.get()).put("evicted", cEvict.get()))
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
        val nw = (call.getFloat("noiseScaleW") ?: 0.8f).coerceIn(0f, 1.2f)
        val sp = (call.getFloat("speed") ?: 1.0f).coerceIn(0.4f, 2.5f)   // <1 کندتر؛ >1 تندتر (سرعت کاربر داخل خود مدل)
        val gap = (call.getFloat("gap") ?: 0.12f).coerceIn(0f, 0.6f)      // ثانیه، سکوت بین جمله‌ها
        val partsArr = call.getArray("parts")
        val lvlClean = (call.getInt("clean") ?: 2).coerceIn(0, 3)       // حذف نویز و صداهای اضافه
        val ndSel = call.getString("nd") ?: "baseline"                  // مدل DPDFNet: baseline | off (مدل‌های سنگین‌تر حذف شدند)
        val lvlClarity = (call.getInt("clarity") ?: 2).coerceIn(0, 3)   // شفافیت و رسایی
        val lvlPause = (call.getInt("pause") ?: 2).coerceIn(0, 3)       // حذف وزوز مکث‌ها (فقط مانا): ۰ خاموش … ۳ قوی
        val keep = call.getBoolean("keep") ?: false                      // true: PCM در حافظهٔ بومی می‌ماند و فقط handle برمی‌گردد (پخش بومی)
        val fxOn = call.getBoolean("master") ?: false                    // زنجیرهٔ EQ + کمپرسور + لیمیتر (MasterFx)
        val fxPk = (call.getFloat("fxPk") ?: 2f).coerceIn(-6f, 8f)
        val fxHs = (call.getFloat("fxHs") ?: 0.8f).coerceIn(-6f, 8f)
        val fxLp = (call.getFloat("fxLp") ?: 10500f).coerceIn(4000f, 20000f)
        val eqCfg = MasterFx.EqCfg.from(call.getObject("eq"))   // r14: اکولایزر کامل ۱۰ باندی (null = خاموش یا تخت)
        val pzOn = call.getBoolean("pz") ?: false   // r145 (ناظر مکث): نرمال‌سازی سکوت مرز جمله‌ها و کوتاه‌کردن سکوت‌های داخلیِ طولانی
        val colOn = call.getBoolean("col") ?: false   // r19: ناظر رنگ (فقط مانا؛ JS تعیین می‌کند)
        val cbT: DoubleArray? = try { call.getArray("cbT")?.let { a -> if (a.length() == 7) DoubleArray(7) { a.getDouble(it) } else null } } catch (e: Throwable) { null }   // r23: هدف شکل طیف ۷ باند (dB نسبی، میانهٔ اخیر)؛ با آن EQ پویای باندی جای شلف شیب را می‌گیرد
        val clT: Double? = call.getDouble("clT")   // r19: هدف شیب طیف (dB/oct، میانهٔ اخیر)؛ بدون آن فقط اندازه‌گیری می‌شود
        val lvT: Double? = call.getDouble("lvT")   // r141: هدف بلندی (dBFS، میانهٔ اخیر همان گوینده)؛ بدون آن تراز نمی‌شود
        val hdAmt = ((call.getFloat("hdAmt") ?: 65f).coerceIn(0f, 100f) / 100f).toDouble()   // شدت بازسازی بالاهای HD
        val hdOn = call.getBoolean("hd") ?: false   // HD: بالانمونه‌برداری ۲× + بازسازی بالاها (۴۴٫۱kHz برای مدل‌های ۲۲٫۰۵kHz)
        val probe = call.getBoolean("probe") ?: false   // r15: اندازه‌گیری DSP (خام در برابر خروجی)؛ کش پردازش‌شده را دور می‌زند تا نسخهٔ خام همیشه در دسترس باشد؛ صدای خروجی تغییری نمی‌کند
        val manaPh = call.getBoolean("manaPh") ?: true   // r187: واج‌آرایی اختصاصی مانا (کسرهٔ اضافه)؛ فقط برای صدای مانا اثر دارد
        val strong = id.contains("mana", ignoreCase = true)   // مانا: خروجی خام SNR پایین دارد؛ بدون این حالت شرط ۲۵dB باعث می‌شد حذف نویز اصلاً اجرا نشود (اعتماد تدریجی ۱۲–۲۵dB + کاهش خش بالای ۴kHz در واکه‌ها). کلید کش شامل strong است
        // r176: gyro — کف نویز خام ≈ ‎−۱۷dB (SNR مرجع < ۲۵dB) بود و شرط سخت‌گیرانهٔ ۲۵dB حذف نویز را اصلاً اجرا نمی‌کرد؛ اعتماد تدریجی (۸ تا ۲۰dB) مثل مانا، ولی بدون سایر ویژگی‌های اختصاصی مانا (گیت/کاهش بالای ۴kHz/۱٫۱۵×)
        val grad = true   // r178: همهٔ صداها (gyro، ganji، …): SNR مرجع < ۲۵dB قبلاً حذف نویز را کاملاً خاموش می‌کرد؛ برای SNR ≥ ۲۰dB رفتار پیشین است
        val ep = epoch.get()   // لحظهٔ رسیدن درخواست؛ اگر بعدش cancel بیاید این کار رها می‌شود
        cpu.execute {
            try {
                val tot0 = System.nanoTime()
                if (ep != epoch.get()) throw IllegalStateException("NATIVE_CANCELLED")
                if (!okMark(id).exists()) throw IllegalStateException("این صدا هنوز نصب نشده است؛ چند لحظه صبر کنید تا نصب تمام شود")
                val eng = engineFor(id, ns, nw)
                val sr = eng.sampleRate()
                if (ttsMana && manaPh) ManaPhon.ensure(context)   // یک‌بار؛ برچسب کش پایین باید ثابت بماند
                val mTag = if (ttsMana) "|" + ManaPhon.tag(manaPh) else ""   // r187: کش صدای مانا برای هر حالت واج‌آرایی جدا
                val eTag = ENGINE_VER + (if (ttsPhon) "|" + phonTag() else "")   // P07-05: نسخهٔ موتور/آوانگار در کلید؛ ارتقا ⇒ کش کهنه سرو نمی‌شود
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
                // r17: حذف‌نویز عصبی (DPDFNet 48kHz) پیش از فیلتر کلاسیک: سطح ۲ ← ۸۵٪، سطح ۳ ← کامل؛ سطح ۰/۱ بی‌اثر. اگر مدل/کتابخانه نبود ndMix=0 و فقط فیلتر کلاسیک اجرا می‌شود.
                val ndWant = if (ndSel == "off") 0f else if (lvlClean >= 3) 1.0f else if (lvlClean == 2) 0.85f else 0f
                val ndMix = if (ndWant > 0f && NeuralDenoise.ready(context, ndSel)) ndWant else 0f
                val ndName = NeuralDenoise.norm(ndSel)
                val ndTag = if (ndMix > 0f) "|nd2_" + (ndMix * 100f).toInt() + (if (ndName == "baseline") "" else "_$ndName") else ""
                val pieces = ArrayList<FloatArray>()
                val rawKeep: ArrayList<FloatArray>? = if (probe) ArrayList() else null   // r15: نسخهٔ خام هر جمله (فقط در probe)
                val pieceGaps = ArrayList<Int>()   // نمونه‌های سکوت پس از هر تکه
                val pieceItem = ArrayList<Int>()   // اندیس جملهٔ (part) هر تکه؛ جمله‌های خالی تکه ندارند
                var genMs = 0L
                var ndMs = 0L; var scMs = 0L
                var hit = 0
                for ((ix, itp) in items.withIndex()) {
                    val pt = itp.first; val ps = itp.second
                    if (pt.isBlank() || pt.none { it.isLetterOrDigit() }) continue
                    val psq = String.format(java.util.Locale.US, "%.2f", ps)
                    val key = hashKey("v2|$eTag|$id|$ns|$nw|" + psq + "|" + pt + mTag)
                    // کش دولایه، هر دو جمله‌به‌جمله و مستقل از مرز تکه‌ها: ۱) خروجی پردازش‌شده (پرش از مدل و پاک‌سازی)  ۲) نسخهٔ خام (با تغییر سطح پاک‌سازی مدل دوباره اجرا نمی‌شود)
                    val procOn = lvlClean > 0 || lvlClarity > 0
                    fun k2(tag: String) = hashKey("v6|$DSP_VER|$eTag|$id|$ns|$nw|" + psq + "|$lvlClean|$lvlClarity|$strong|$grad$tag$mTag|" + pt)
                    val key2 = if (procOn) k2(ndTag) else key
                    val done = if (procOn && !probe) cacheGet(key2, sr) else null
                    if (done != null) {
                        hit++
                        pieces.add(done)
                    } else {
                        val cached = cacheGet(key, sr)
                        val s = if (cached != null) { hit++; cached } else {
                            val t0 = System.nanoTime()
                            val g0 = genPart(eng, pt, ps, ep, manaPh)
                            val g = g0
                            genMs += (System.nanoTime() - t0) / 1_000_000L   // زمان خالص مدل (بدون صیقل و انتقال)
                            cachePut(key, sr, g)
                            g
                        }
                        rawKeep?.add(s)
                        val nd0 = System.nanoTime()
                        val sn = if (ndMix > 0f) NeuralDenoise.run(s, sr, ndMix) else s   // r17: عصبی ← سپس کلاسیک
                        ndMs += (System.nanoTime() - nd0) / 1_000_000L
                        val ndApplied = ndMix > 0f && sn !== s   // P07-06: run در خطا/ایمنی همان ورودی را برمی‌گرداند
                        val sc0 = System.nanoTime()
                        val pr = SpeechClean.process(sn, sr, lvlClean, lvlClarity, strong, grad)   // روی نسخهٔ خام کش؛ خودِ کش دست‌نخورده می‌ماند
                        scMs += (System.nanoTime() - sc0) / 1_000_000L
                        if (procOn && pr !== s) cachePut(if (ndMix > 0f && !ndApplied) k2("") else key2, sr, pr)   // ND اعمال نشده ⇒ کلید «فقط کلاسیک»
                        pieces.add(pr)
                    }
                    pieceGaps.add((itemGaps[ix] * sr).toInt())
                    pieceItem.add(ix)
                }
                if (pieces.isEmpty()) throw IllegalStateException("متن قابل‌خواندن ندارد")
                // r145 (ناظر مکث): مکث بین دو جمله = سکوت ذاتیِ مدل (نامعلوم) + gap برنامه‌ریزی‌شده. اینجا سکوت ذاتی هر مرز به همان پشتوانهٔ لبه‌ای صیقل (۴۵ms ابتدا / ۷۰ms انتها) بریده می‌شود
                // و gap طوری تنظیم می‌شود که مکث کل = gap برنامه‌ریزی‌شده + ۱۱۵ms (دقیقاً مثل مرز تکه‌ها که polish لبه‌ها را می‌برد و gapMs می‌آید). سکوت‌های داخلی > ۹۰۰ms به ۹۰۰ms کوتاه می‌شوند. کش دست‌نخورده می‌ماند.
                var pzN = 0; var pzHid = 0L; var pzCut = 0L; var pzInt = 0; var pzIntCut = 0L
                if (pzOn && pieces.size > 1) {
                    val cL = (sr * 0.045).toInt(); val cT = (sr * 0.07).toInt(); val cap = (sr * 0.9).toInt()
                    val kept = IntArray(pieces.size * 2)   // [سکوتِ نگه‌داشتهٔ ابتدا، انتها] هر تکه
                    for (k in pieces.indices) {
                        val x = pieces[k]
                        if (x.size < sr / 4) continue
                        var pk = 0f
                        for (v in x) { val a = kotlin.math.abs(v); if (a > pk) pk = a }
                        if (pk < 1e-3f) continue
                        val thr = kotlin.math.max(pk * 0.012f, 0.0015f)
                        var s0 = 0; while (s0 < x.size && kotlin.math.abs(x[s0]) < thr) s0++
                        var e0 = x.size - 1; while (e0 > s0 && kotlin.math.abs(x[e0]) < thr) e0--
                        if (e0 - s0 < sr / 10) continue
                        val doLead = k > 0; val doTail = k < pieces.size - 1
                        val a0 = if (doLead) kotlin.math.max(0, s0 - cL) else 0
                        val b0 = if (doTail) kotlin.math.min(x.size - 1, e0 + cT) else x.size - 1
                        if (doLead) { kept[2 * k] = s0 - a0; pzHid += s0; pzCut += a0 }
                        if (doTail) { kept[2 * k + 1] = b0 - e0; pzHid += (x.size - 1 - e0); pzCut += (x.size - 1 - b0) }
                        // سکوت‌های داخلی طولانی: وسط آن‌ها حذف می‌شود (نمونه‌های حذف‌شده زیر آستانه‌اند)
                        val keepR = ArrayList<IntArray>()
                        var segStart = a0; var i = a0
                        while (i <= b0) {
                            if (kotlin.math.abs(x[i]) < thr) {
                                var j = i; while (j <= b0 && kotlin.math.abs(x[j]) < thr) j++
                                val len = j - i
                                if (len > cap) { keepR.add(intArrayOf(segStart, i + cap / 2)); segStart = j - cap / 2; pzInt++; pzIntCut += (len - cap).toLong() }
                                i = j
                            } else i++
                        }
                        keepR.add(intArrayOf(segStart, b0 + 1))
                        var n2 = 0; for (r in keepR) n2 += r[1] - r[0]
                        val y = FloatArray(n2); var o = 0
                        for (r in keepR) { System.arraycopy(x, r[0], y, o, r[1] - r[0]); o += r[1] - r[0] }
                        // r146: محو کوتاه روی لبه‌های بریده‌شده (فقط درون سکوتِ نگه‌داشته؛ هرگز روی آغاز/پایان گفتار) ⇒ بدون تق
                        val fl = kotlin.math.min(kept[2 * k], (sr * 0.008).toInt()); val ft = kotlin.math.min(kept[2 * k + 1], (sr * 0.015).toInt())
                        for (q in 0 until fl) y[q] *= q / fl.toFloat()
                        for (q in 0 until ft) y[y.size - 1 - q] *= q / ft.toFloat()
                        pieces[k] = y
                    }
                    for (k in 0 until pieces.size - 1) {
                        pieceGaps[k] = kotlin.math.max(0, pieceGaps[k] + cL + cT - (kept[2 * k + 1] + kept[2 * (k + 1)]))
                        pzN++
                    }
                }
                // r24 (رنگ هر جمله): هر جمله پیش از چسباندن و پیش از HD به میانهٔ اخیر نزدیک می‌شود (۷ باند + خش/هوای ۶٫۵–۱۰٫۵kHz)؛ فقط با col (مانا)؛ خطا ⇒ بدون اصلاح
                if (colOn && sr >= 20000 && pieces.isNotEmpty()) { try { PieceColor.match(id, pieces, sr) } catch (e: Throwable) { } }
                cHit.addAndGet(hit.toLong()); cMiss.addAndGet((pieces.size - hit).toLong())
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
                val rawHs = rawHealth(all)   // r13: بریدگی و مقدار نامعتبر پیش از صیقل/لیمیتر (ناظر سلامت صدا)
                val trim = IntArray(1)   // نمونه‌هایی که صیقل از ابتدای بافر بریده (مرزها باید همین‌قدر جابه‌جا شوند)
                val pol = polish(all, sr, trim)
                val polH = if (hdOn && sr <= 24000) MasterFx.hdUp(pol, sr, hdAmt) else pol
                val sro = if (polH !== pol) sr * 2 else sr   // نرخ نمونهٔ خروجی (HD: ۲×)
                val fx0 = System.nanoTime()
                val fin0 = if (fxOn) MasterFx.process(polH, sro, fxPk, fxHs, if (sro != sr) Math.max(fxLp, 18000f) else fxLp, strong, lvlPause) else polH
                val fin = if (eqCfg != null) MasterFx.eq(fin0, sro, eqCfg) else fin0   // r14: اکولایزر کاربر بعد از مسترینگ
                val fxMs = (System.nanoTime() - fx0) / 1_000_000L
                for (i in fin.indices) fin[i] *= OUT_TRIM   // r120: ۳dB headroom (پیک ≈ −۳ dBFS)؛ بعد از کش و مسترینگ، پس کش بی‌اثر می‌ماند
                // مرز واقعی شروع هر جمله (کسری از ۰ تا ۱ از کل خروجی)؛ برای هم‌گام‌سازی هایلایت به‌جای تخمین از نسبت نویسه‌ها (T-14)
                // playerPos بومی هم کسری از همین بافر است (مستقل از سرعت پخش)؛ در وب نسبت زمان به مدت صداست
                val marks: JSArray? = if (partsArr != null && partsArr.length() > 0 && fin.isNotEmpty()) {
                    val m = DoubleArray(items.size) { -1.0 }
                    for (k in pieces.indices) m[pieceItem[k]] = ((starts[k] - trim[0]).coerceAtLeast(0).toDouble() * sro / sr / fin.size).coerceIn(0.0, 1.0)
                    var nx = 1.0
                    for (ix in items.size - 1 downTo 0) { if (m[ix] < 0.0) m[ix] = nx else nx = m[ix] }   // جملهٔ بدون تکه: مرزش شروع جملهٔ بعدی
                    JSArray().also { a -> for (v in m) a.put(v) }
                } else null
                // r19 (ناظر رنگ): شیب طیف واک‌دار پیش از اصلاح (ct) برای تاریخچهٔ JS؛ اگر هدف رسیده و انحراف > ۰٫۳ dB/oct باشد، شلف بالای ۱٫۸kHz ملایم (نصف انحراف، حداکثر ±۲٫۵dB). پیش از تراز بلندی ⇒ تراز اثر شلف را هم حساب می‌کند؛ پس از کش ⇒ کش دست‌نخورده
                var ctV = Double.NaN
                var cgV = 0.0
                var bdV: DoubleArray? = null
                var bgV: DoubleArray? = null
                if (colOn && fin.isNotEmpty()) {
                    val shp = ColorSv.shape(fin, sro)
                    if (shp != null) {
                        bdV = shp
                        ctV = ColorSv.slope(shp)
                        if (cbT != null) {
                            // r23: EQ پویای باندی — انحراف هر باند از میانهٔ اخیر (آستانه ۰٫۵dB) با پیک ملایم (۰٫۷×انحراف اضافه، حداکثر ±۳dB) برگردانده می‌شود؛ پیش از تراز بلندی و پس از کش
                            val gs = DoubleArray(shp.size)
                            var anyG = false
                            for (b in shp.indices) {
                                val d = shp[b] - cbT[b]
                                if (d.isNaN() || d.isInfinite()) continue
                                val g = (-Math.signum(d) * (Math.abs(d) - 0.5) * 0.7).coerceIn(-3.0, 3.0)
                                if (Math.abs(d) > 0.5 && Math.abs(g) >= 0.1) { gs[b] = g; anyG = true }
                            }
                            if (anyG) {
                                val eqd = MasterFx.bandEq(fin, sro, gs)
                                System.arraycopy(eqd, 0, fin, 0, fin.size)
                                bgV = gs
                                var bi = 0
                                for (b in gs.indices) if (Math.abs(gs[b]) > Math.abs(gs[bi])) bi = b
                                cgV = gs[bi]
                            }
                        } else if (clT != null && !ctV.isNaN() && !clT.isNaN() && !clT.isInfinite()) {
                            val d = ctV - clT
                            val ex = Math.abs(d) - 0.3
                            if (ex > 0.0) {
                                val g = (-Math.signum(d) * ex * 1.7).coerceIn(-2.5, 2.5)
                                if (Math.abs(g) >= 0.1) {
                                    val sh = MasterFx.tiltShelf(fin, sro, g)
                                    System.arraycopy(sh, 0, fin, 0, fin.size)
                                    cgV = g
                                }
                            }
                        }
                    }
                }
                // r141 (تراز بلندی): تکه‌ای که ≥ ۲dB از هدف دور است تا ±۴dB به هدف می‌رود؛ افزایش با سقف پیک OUT_TRIM (بریدگی ندارد). پس از کش و بعد از OUT_TRIM ⇒ کش دست‌نخورده
                val lvA0 = activeDb(fin)
                var lvG = 1.0
                if (lvT != null && lvA0 > -90.0 && Math.abs(lvT - lvA0) >= 2.0) {
                    var g = Math.pow(10.0, (lvT - lvA0).coerceIn(-4.0, 4.0) / 20.0)
                    if (g > 1.0) { var pk = 0f; for (x in fin) { val a = kotlin.math.abs(x); if (a > pk) pk = a }; if (pk > 0f) g = Math.min(g, OUT_TRIM.toDouble() / pk) }
                    if (Math.abs(g - 1.0) > 0.03) { val gf = g.toFloat(); for (i in fin.indices) fin[i] *= gf; lvG = g }
                }
                val hs = finHealth(fin, rawHs, lvA0, lvG, sro).also { o ->
                    if (!ctV.isNaN() && !ctV.isInfinite()) o.put("ct", Math.round(ctV * 100.0) / 100.0)
                    if (cgV != 0.0) o.put("cg", Math.round(cgV * 100.0) / 100.0)
                    bdV?.let { b -> o.put("bd", JSArray().also { a -> for (v in b) a.put(Math.round(v * 100.0) / 100.0) }) }
                    bgV?.let { b -> o.put("bg", JSArray().also { a -> for (v in b) a.put(Math.round(v * 100.0) / 100.0) }) }
                }
                val dsp: JSObject? = if (rawKeep != null && rawKeep.isNotEmpty()) {   // r15: آمار خام↔خروجی برای گزارش ناظر
                    var rn = 0; for (x in rawKeep) rn += x.size
                    val rw = FloatArray(rn); var rp = 0
                    for (x in rawKeep) { System.arraycopy(x, 0, rw, rp, x.size); rp += x.size }
                    JSObject().put("raw", dspStats(rw, sr)).put("out", dspStats(fin, sro))
                } else null
                if (keep) {
                    val h = pcmPut(fin, sro)
                    val res = JSObject().put("hs", hs).also { if (dsp != null) it.put("dsp", dsp) }.put("handle", h).put("sampleRate", sro).put("durationMs", fin.size * 1000L / sro).put("genMs", genMs).put("hit", hit).put("parts", pieces.size).put("ndMs", ndMs).put("scMs", scMs).put("fxMs", fxMs).put("totMs", (System.nanoTime() - tot0) / 1_000_000L).put("sr", sr).put("ttsPhon", ttsPhon).put("ttsKey", ttsKey ?: "")
                    if (marks != null) res.put("marks", marks)
                    if (pzOn) res.put("pz", JSObject().put("n", pzN).put("hid", pzHid * 1000L / sr).put("cut", pzCut * 1000L / sr).put("ni", pzInt).put("nic", pzIntCut * 1000L / sr))
                    call.resolve(res)
                } else {
                    val wav = toWav(fin, sro)
                    val res = JSObject().put("hs", hs).also { if (dsp != null) it.put("dsp", dsp) }.put("wav", Base64.encodeToString(wav, Base64.NO_WRAP)).put("sampleRate", sro).put("genMs", genMs).put("hit", hit).put("parts", pieces.size).put("ndMs", ndMs).put("scMs", scMs).put("fxMs", fxMs).put("totMs", (System.nanoTime() - tot0) / 1_000_000L).put("sr", sr).put("ttsPhon", ttsPhon).put("ttsKey", ttsKey ?: "")
                    if (marks != null) res.put("marks", marks)
                    if (pzOn) res.put("pz", JSObject().put("n", pzN).put("hid", pzHid * 1000L / sr).put("cut", pzCut * 1000L / sr).put("ni", pzInt).put("nic", pzIntCut * 1000L / sr))
                    call.resolve(res)
                }
            } catch (e: Throwable) {
                call.reject(if (e.message == "NATIVE_CANCELLED") "NATIVE_CANCELLED" else "خطای موتور بومی: ${e.message}")
            }
        }
    }

    /** r15: آمار اندازه‌گیری DSP (فقط با probe). قاب‌های ۲۰ms که تا ۴۰dB زیر بلندترین قاب‌اند «فعال» حساب می‌شوند.
     *  rng = صدک ۹۵ منهای ۱۰ بلندی قاب‌ها (dB)، sd = انحراف معیار آن، rms = RMS فعال (dBFS)، hf = نسبت انرژی بالای ۴kHz به کل (dB).
     *  rng/sd/hf به بهرهٔ کلی حساس نیستند؛ پس خام و خروجی مستقیماً قابل مقایسه‌اند. */
    private fun dspStats(f: FloatArray, sr: Int): JSObject {
        val o = JSObject()
        val fl = (sr * 0.02).toInt().coerceAtLeast(64)
        val nf = f.size / fl
        if (nf < 10) return o
        val fc = Math.min(4000.0, sr * 0.45)
        val w = 2.0 * Math.PI * fc / sr; val cw = Math.cos(w); val al = Math.sin(w) / (2.0 * 0.70710678)
        val a0 = 1.0 + al; val b0 = (1.0 + cw) / 2.0 / a0; val b1 = -(1.0 + cw) / a0
        val a1 = -2.0 * cw / a0; val a2 = (1.0 - al) / a0
        val et = DoubleArray(nf); val eh = DoubleArray(nf)
        var z1 = 0.0; var z2 = 0.0
        for (m in 0 until nf) {
            var s = 0.0; var h = 0.0
            for (i in m * fl until m * fl + fl) {
                val v = f[i].toDouble()
                val y = b0 * v + z1
                z1 = b1 * v - a1 * y + z2
                z2 = b0 * v - a2 * y
                s += v * v; h += y * y
            }
            et[m] = s; eh[m] = h
        }
        var mx = 0.0
        for (m in 0 until nf) if (et[m] > mx) mx = et[m]
        if (mx < 1e-9) return o
        val thr = mx * 1e-4
        val db = ArrayList<Double>()
        var sT = 0.0; var sH = 0.0
        for (m in 0 until nf) if (et[m] >= thr) { db.add(10.0 * Math.log10(et[m] / fl)); sT += et[m]; sH += eh[m] }
        val k = db.size
        if (k < 8) return o
        db.sort()
        fun pc(p: Double): Double = db[Math.min(k - 1, Math.round(p * (k - 1)).toInt())]
        var mean = 0.0; for (x in db) mean += x
        mean /= k
        var vr = 0.0; for (x in db) vr += (x - mean) * (x - mean)
        vr /= k
        fun r1(x: Double): Double = Math.round(x * 10.0) / 10.0
        o.put("rng", r1(pc(0.95) - pc(0.10))).put("sd", r1(Math.sqrt(vr)))
        o.put("rms", r1(10.0 * Math.log10(sT / (k.toDouble() * fl) + 1e-20))).put("hf", r1(10.0 * Math.log10(sH / (sT + 1e-20) + 1e-20))).put("n", k)
        return o
    }

    /** r13 (ناظر سلامت صدا): [کسر نمونه‌های با |x| ≥ ۰٫۹۸۵، تعداد NaN/Inf] روی خروجی خام پیش از صیقل */
    private fun rawHealth(a: FloatArray): DoubleArray {
        var cl = 0; var bad = 0
        for (x in a) { if (x.isNaN() || x.isInfinite()) bad++ else if (kotlin.math.abs(x) >= 0.985f) cl++ }
        return doubleArrayOf(cl.toDouble() / a.size.coerceAtLeast(1), bad.toDouble())
    }
    /** r13: آمار نهایی {pk قله، rms، dc میانگین، cl بریدگی خام، nan نامعتبر} برای JS؛ مقدارهای غیرمحدود هرگز در JSON نمی‌روند */
    /** r143: کف نویز زنده — همان تعریف nsMetrics در JS (فریم ۲۰ms، نمونه‌ها به int16 گرد می‌شوند؛ فریم مرده r ≤ 1e-9؛ ۱۵٪ کم‌انرژی‌ترین فریم‌ها نسبت به ۴۰٪ پرانرژی‌ترین‌ها)؛ NaN = داده کافی نیست */
    private fun liveFloor(f: FloatArray, sr: Int): Double {
        // r180/kt-r16: همان تعریف nsMetrics جدید — فریم‌های مردهٔ میان گفتار (گیت/حذف نویز) کفِ r=1e-10 دارند و حذف نمی‌شوند؛
        // پلاتوی کف (۱۵٪ در برابر ۳٪ کم‌انرژی‌ترین فریم‌ها ≤ ۳dB) نباشد سکوت مرجعی در جمله نیست ⇒ NaN (عدد نویز نیست)
        val n = Math.round(sr * 0.02f)
        if (n < 8) return Double.NaN
        val cnt = f.size / n
        val r = DoubleArray(cnt)
        var f1 = -1; var f2 = -1
        for (i in 0 until cnt) {
            var e = 0.0
            for (j in i * n until i * n + n) { val q = Math.round(f[j].coerceIn(-1f, 1f) * 32767f) / 32768.0; e += q * q }
            r[i] = e / n
            if (r[i] > 1e-9) { if (f1 < 0) f1 = i; f2 = i }
        }
        if (f1 < 0 || f2 - f1 + 1 < 20) return Double.NaN
        val idx = ArrayList<Int>()
        for (i in f1..f2) idx.add(i)
        idx.sortBy { r[it] }
        val qn = Math.max(3, (idx.size * 0.15).toInt()); val qs = Math.max(5, (idx.size * 0.4).toInt())
        val rm = 1e-10
        var eN = 0.0; for (i in 0 until qn) eN += Math.max(r[idx[i]], rm)
        var eS = 0.0; for (i in idx.size - qs until idx.size) eS += r[idx[i]]
        eN /= qn; eS /= qs
        val p3 = Math.max(r[idx[(idx.size * 0.03).toInt()]], rm); val p15 = Math.max(r[idx[qn - 1]], rm)
        if (10.0 * kotlin.math.log10(p15 / p3) > 3.0) return Double.NaN
        return 10.0 * kotlin.math.log10(eN / eS + 1e-10)
    }
    private fun activeDb(f: FloatArray): Double {
        var q = 0.0; var n = 0
        for (x in f) { if (kotlin.math.abs(x) > 0.01f) { q += x.toDouble() * x; n++ } }
        return if (n > 0) 10.0 * kotlin.math.log10(q / n + 1e-12) else -99.0
    }
    private fun finHealth(f: FloatArray, raw: DoubleArray, a0: Double = -99.0, g: Double = 1.0, sr: Int = 22050): JSObject {
        var pk = 0f; var sum = 0.0; var sq = 0.0; var bad = raw[1].toInt(); var aq = 0.0; var an = 0
        for (x in f) { if (x.isNaN() || x.isInfinite()) { bad++; continue }; val a = kotlin.math.abs(x); if (a > pk) pk = a; sum += x; sq += x.toDouble() * x; if (a > 0.01f) { aq += x.toDouble() * x; an++ } }
        val n = f.size.coerceAtLeast(1)
        // ar: RMS (dBFS) فقط روی نمونه‌های فعال (|x| > ۰٫۰۱ ≈ −۴۰ dBFS) ⇒ مستقل از سهم مکث؛ −۹۹ = بدون نمونهٔ فعال (ناظر یکنواختی بلندی)
        return JSObject().put("pk", pk.toDouble()).put("rms", kotlin.math.sqrt(sq / n)).put("dc", sum / n).put("cl", raw[0]).put("nan", bad)
            .put("ar", if (an > 0) 10.0 * kotlin.math.log10(aq / an + 1e-12) else -99.0).put("a0", a0).put("g", g).also { o -> val fl = liveFloor(f, sr); if (!fl.isNaN() && !fl.isInfinite()) o.put("fl", fl) }
    }

    private val OUT_TRIM = 0.462f   // r179: −6.7 dB؛ بلندی کل ≈ −۲۳٫۸ LUFS مطابق نمونهٔ فرید (پیش‌تر −۳dB ≈ −۲۰٫۱ LUFS)

    /** صیقل خروجی هر تکه: حذف سکوت اضافهٔ ابتدا/انتها، محوشدن کوتاه (جلوگیری از «تق» بین جمله‌ها)، یکنواخت‌کردن بلندی صدا با محدودکنندهٔ نرم */
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
        val base = Math.max(if (min > 0) min else 0, sr / 4 * 2)   // ≈ ۲۵۰ms: هم روان، هم رویدادها نزدیک به صدای واقعی
        if (bufMs > BUF_MIN_MS && android.os.SystemClock.elapsedRealtime() - bufAt > 180_000L) bufMs = BUF_MIN_MS
        return if (android.os.Build.VERSION.SDK_INT >= 23) {
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setBufferSizeInBytes(Math.max(base, sr * BUF_MAX_MS / 1000 * 2)).setTransferMode(AudioTrack.MODE_STREAM).build()   // ظرفیت = سقف رشد؛ اندازهٔ فعال پایین‌تر
            try { t.setBufferSizeInFrames(Math.max(base / 2, sr * bufMs / 1000)) } catch (e: Throwable) {}
            t
        } else {
            AudioTrack(AudioManager.STREAM_MUSIC, sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, base, AudioTrack.MODE_STREAM)
        }
    }

    /** r138: پس از هر underrun بافر فعال ۱۰۰ms بزرگ‌تر می‌شود (تا سقف ظرفیت ترک)؛ فقط API 24+ (underrunCount) */
    private fun growBuf(t: AudioTrack, sr: Int) {
        bufAt = android.os.SystemClock.elapsedRealtime()
        if (bufMs >= BUF_MAX_MS) return
        bufMs = Math.min(BUF_MAX_MS, bufMs + 100); bufGrow++
        try { t.setBufferSizeInFrames(Math.min(t.bufferCapacityInFrames, sr * bufMs / 1000)) } catch (e: Throwable) {}
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
            var sr = first.pcm.sr
            var t = makeTrack(sr)
            track = t
            synchronized(plLock) { if (gen == plGen) plTrack = t }
            if (afDuck) try { t.setVolume(0.3f) } catch (e: Throwable) {}
            if (gen != plGen) return
            t.play()
            var base = t.playbackHeadPosition
            var written = 0L
            var lastU = 0
            var step = Math.max(256, sr / 50)
            var zeros = ShortArray(step)
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
                        if (android.os.Build.VERSION.SDK_INT >= 24) { val u = t.underrunCount; if (u > lastU) { underruns += u - lastU; lastU = u; growBuf(t, sr) } }
                    }
                }
                pend?.cancel(true)
                if (android.os.Build.VERSION.SDK_INT >= 24) { val u = t.underrunCount; underruns += u - lastU; lastU = u }
                var nx: PlItem? = takeNext()
                var dHead = t.playbackHeadPosition
                var dAt = android.os.SystemClock.elapsedRealtime()
                while (nx == null) {   // تا خالی‌شدن بافر منتظر آیتم بعدی می‌مانیم
                    if (gen != plGen) return
                    val hp = t.playbackHeadPosition
                    if (hp - base >= written) break
                    val tn = android.os.SystemClock.elapsedRealtime()
                    // r166: ترکِ در حال پخش که ۳ثانیه جلو نرود (قطع مسیر صدا/گیر HAL) ⇒ خطا تا JS بلافاصله از همان جمله بازیابی کند (به‌جای ۸ثانیه سکوت)
                    if (hp != dHead) { dHead = hp; dAt = tn }
                    else if (tn - dAt > 3000 && t.playState == AudioTrack.PLAYSTATE_PLAYING) throw IllegalStateException("stall")
                    Thread.sleep(8)
                    nx = takeNext()
                }
                if (nx == null) {
                    // r166: بافر خالی شد ولی آیتم بعدی هنوز ساخته نشده: ترک را مکث می‌کنیم و جلسه را باز نگه می‌داریم (playerQueue همچنان queued=true می‌گیرد)
                    try { t.pause() } catch (e: Throwable) {}
                    val graceEnd = android.os.SystemClock.elapsedRealtime() + IDLE_GRACE_MS
                    while (nx == null && android.os.SystemClock.elapsedRealtime() < graceEnd) {
                        if (gen != plGen) return
                        Thread.sleep(10)
                        nx = takeNext()
                    }
                    if (nx == null) {
                        if (closeIfIdle(gen)) { emitPl("playerEnd", item.h); return }
                        nx = takeNext()
                    }
                    if (nx != null) { try { t.play() } catch (e: Throwable) {} }
                }
                val nn = nx ?: return
                if (nn.pcm.sr != sr) {   // P07-09: نرخ نمونه عوض شد (مثلاً HD) ⇒ پس از تخلیهٔ بافر، ترک تازه با نرخ جدید
                    var dh = t.playbackHeadPosition; var da = android.os.SystemClock.elapsedRealtime()
                    while (t.playbackHeadPosition - base < written) {
                        if (gen != plGen) return
                        val tn = android.os.SystemClock.elapsedRealtime()
                        val hp = t.playbackHeadPosition
                        if (hp != dh) { dh = hp; da = tn }
                        else if (tn - da > 3000 && t.playState == AudioTrack.PLAYSTATE_PLAYING) throw IllegalStateException("stall")
                        Thread.sleep(8)
                    }
                    val oldT = t
                    sr = nn.pcm.sr
                    t = makeTrack(sr)
                    track = t
                    synchronized(plLock) { if (gen == plGen) plTrack = t }
                    try { oldT.pause(); oldT.flush(); oldT.release() } catch (e: Throwable) {}
                    if (afDuck) try { t.setVolume(0.3f) } catch (e: Throwable) {}
                    if (gen != plGen) return
                    t.play()
                    base = t.playbackHeadPosition; written = 0L; lastU = 0
                    step = Math.max(256, sr / 50); zeros = ShortArray(step)
                }
                item = nn
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
        cpu.execute { try { tts?.release() } catch (e: Throwable) {}; tts = null; ttsId = null; ttsKey = null; try { NeuralDenoise.release() } catch (e: Throwable) {} }
        try { bins.keys.toList().forEach { try { binDrop(it, false) } catch (e: Exception) {} } } catch (e: Throwable) {}
        cpu.shutdown(); io.shutdown(); bio.shutdown()
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
    fun process(src: FloatArray, sr: Int, clean: Int, clarity: Int, strong: Boolean = false, grad: Boolean = strong): FloatArray {
        if ((clean <= 0 && clarity <= 0) || src.size < sr / 20) return src
        var x = src.copyOf()
        try {
            if (clean > 0) highpass(x, sr, 50.0)
            if (x.size >= sr / 4 && x.size <= sr * 45) {
                val cleanDb = when (clean) { 1 -> 8.0; 2 -> 12.0; 3 -> 18.0; else -> 0.0 }
                val beta = when (clean) { 1 -> 1.0; 3 -> 1.5; else -> 1.2 }
                val silDb = when (clean) { 1 -> 3.0; 2 -> 6.0; 3 -> 10.0; else -> 0.0 }
                val cl = when (clarity) { 1 -> 0.6; 2 -> 1.0; 3 -> 1.4; else -> 0.0 }
                x = enhance(x, sr, cleanDb, beta, silDb, cl, strong, grad)
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

    private fun enhance(x: FloatArray, sr: Int, cleanDb0: Double, beta: Double, silDb0: Double, cl: Double, strong: Boolean, grad: Boolean = strong): FloatArray {
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
        // مانا: خروجی خام خش بیشتری دارد و شرط سخت‌گیرانهٔ ۲۵dB باعث می‌شد حذف نویز اصلاً اجرا نشود؛ برای آن اعتماد تدریجی (۸ تا ۲۰dB) و شدت متناسب (آزمون عددی روی گفتار مصنوعی: ۴ تا ۶dB کاهش بیشتر نویز در ۱۰ تا ۱۴dB SNR، بدون افت SDR)
        val relW = if (grad) ((snrDb - 8.0) / 12.0).coerceIn(0.0, 1.0) else if (snrDb >= 25.0) 1.0 else 0.0
        val reliable = relW > 0.0
        if (reliable && relW < 1.0) { cleanDb *= relW; silDb *= relW }
        if (reliable && !strong && snrDb < 25.0) { cleanDb *= 0.7; silDb *= 0.7 }   // r178: صداهایی که پیش‌تر اصلاً حذف نویز نمی‌گرفتند (SNR < ۲۵dB) با ۷۰٪ شدت شروع می‌کنند؛ در صورت تمیز بودن بالاتر برده می‌شود
        if (!reliable && cl <= 0.0 && !(grad && cleanDb > 0.0)) return x
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
/** r19 (ناظر رنگ): شیب طیف فریم‌های واک‌دار (dB/اکتاو) روی ۷ باند ۱۵۰–۶۵۰۰Hz؛ همان تعریف «آزمون رنگ صدا» (mnFeat) در index.html.
 *  فریم فعال: انرژی ۸۰–۸۰۰۰Hz بالاتر از ۲۵− dB زیر صدک ۹۰؛ واک‌دار: انرژی ۱۰۰–۹۰۰Hz دست‌کم دو برابر ۲–۸kHz. NaN = فریم واک‌دار کافی نیست. */
private object ColorSv {
    private val EDGE = doubleArrayOf(150.0, 400.0, 800.0, 1500.0, 2500.0, 3500.0, 5000.0, 6500.0)

    private fun fft(re: DoubleArray, im: DoubleArray, cosT: DoubleArray, sinT: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var k = n shr 1
            while ((j and k) != 0) { j = j xor k; k = k shr 1 }
            j = j xor k
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var m = 2
        while (m <= n) {
            val h = m shr 1
            val st = n / m
            var i = 0
            while (i < n) {
                for (k in 0 until h) {
                    val a = i + k
                    val b = a + h
                    val wr = cosT[k * st]
                    val wi = sinT[k * st]
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                }
                i += m
            }
            m = m shl 1
        }
    }

    /** r23: شکل طیف نسبی ۷ باند (dB، میانگین صفر) فریم‌های واک‌دار؛ null = فریم واک‌دار کافی نیست. */
    fun shape(x: FloatArray, sr: Int): DoubleArray? {
        val m = measure(x, sr) ?: return null
        return m.copyOf(EDGE.size - 1)
    }

    /** r24: ۷ عدد شکل طیف (مثل shape) + عدد هشتم = سطح باند ۶٫۵–۱۰٫۵kHz («خش/هوا») نسبت به میانگین ۷ باند (dB)؛ minV = دست‌کم فریم واک‌دار لازم. */
    fun measure(x: FloatArray, sr: Int, minV: Int = 12): DoubleArray? {
        var n = 1
        while (n < sr * 0.046) n = n shl 1
        val h = n shr 1
        val nfAll = (x.size - n) / h + 1
        if (nfAll < 16) return null
        val step = Math.max(1, nfAll / 400)   // سقف ≈۴۰۰ فریم؛ هزینهٔ اندازه‌گیری برای تکهٔ بلند محدود می‌ماند
        val cosT = DoubleArray(h) { Math.cos(2.0 * Math.PI * it / n) }
        val sinT = DoubleArray(h) { -Math.sin(2.0 * Math.PI * it / n) }
        val win = DoubleArray(n) { 0.5 - 0.5 * Math.cos(2.0 * Math.PI * it / n) }
        fun bin(hz: Double): Int = Math.round(hz * n / sr).toInt().coerceIn(1, h)
        val nb = EDGE.size - 1
        val kl = IntArray(nb) { bin(EDGE[it]) }
        val kh = IntArray(nb) { Math.max(kl[it] + 1, bin(EDGE[it + 1])) }
        val ke0 = bin(80.0); val ke1 = bin(8000.0)
        val kv0 = bin(100.0); val kv1 = bin(900.0)
        val kh0 = bin(2000.0); val kh1 = bin(8000.0)
        val khl = bin(6500.0); val khh = Math.max(khl + 1, bin(Math.min(10500.0, sr * 0.5 * 0.95)))   // r24: باند خش/هوا
        val cnt = (nfAll + step - 1) / step
        val fe = DoubleArray(cnt)
        val el = DoubleArray(cnt)
        val eh = DoubleArray(cnt)
        val bands = Array(cnt) { DoubleArray(nb + 1) }
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val pw = DoubleArray(h + 1)
        var q = 0
        var f = 0
        while (f < nfAll && q < cnt) {
            val o = f * h
            for (i in 0 until n) { re[i] = x[o + i] * win[i]; im[i] = 0.0 }
            fft(re, im, cosT, sinT)
            for (k in 0..h) pw[k] = re[k] * re[k] + im[k] * im[k]
            var e = 0.0; var l = 0.0; var hh = 0.0
            for (k in ke0..ke1) e += pw[k]
            for (k in kv0..kv1) l += pw[k]
            for (k in kh0..kh1) hh += pw[k]
            fe[q] = e; el[q] = l; eh[q] = hh
            for (i in 0 until nb) { var b = 0.0; for (k in kl[i] until kh[i]) b += pw[k]; bands[q][i] = b }
            run { var hb = 0.0; for (k in khl until khh) hb += pw[k]; bands[q][nb] = hb }
            q++
            f += step
        }
        val srt = fe.copyOf(q)
        srt.sort()
        val thr = srt[((q - 1) * 0.9).toInt()] * 0.00316   // فریم فعال: ≤ ۲۵− dB زیر صدک ۹۰
        val bm = DoubleArray(nb + 1)
        var nv = 0
        for (i in 0 until q) {
            if (fe[i] <= thr) continue
            if (el[i] < 2.0 * eh[i]) continue   // واک‌دار
            nv++
            for (b in 0..nb) bm[b] += bands[i][b]
        }
        if (nv < minV) return null
        var ref = 0.0
        for (b in 0..nb) bm[b] = 10.0 * Math.log10(bm[b] / nv + 1e-20)
        for (b in 0 until nb) ref += bm[b]
        ref /= nb
        return DoubleArray(nb + 1) { bm[it] - ref }
    }

    /** شیب طیف (dB/اکتاو) از شکل ۷ باندی. */
    fun slope(rel: DoubleArray): Double {
        val nb = rel.size
        var tx = 0.0; var ty = 0.0; var txx = 0.0; var txy = 0.0
        for (b in 0 until nb) {
            val c = Math.log(Math.sqrt(EDGE[b] * EDGE[b + 1])) / Math.log(2.0)
            val r = rel[b]
            tx += c; ty += r; txx += c * c; txy += c * r
        }
        val sl = (nb * txy - tx * ty) / (nb * txx - tx * tx)
        return if (sl.isNaN() || sl.isInfinite()) Double.NaN else sl
    }

    fun tilt(x: FloatArray, sr: Int): Double { val s = shape(x, sr) ?: return Double.NaN; return slope(s) }
}

/**
 * r24 (رنگ هر جمله): ناظر رنگ r19/r23 کل تکه را یکجا می‌سنجد، ولی جمله‌های مانا جدا ساخته می‌شوند و رنگشان از جمله‌ای به جمله‌ای می‌پرد، به‌ویژه بالاها:
 * در WAV واقعی (۶ جمله) باند ۶٫۵–۱۰٫۵kHz تا ≈۱۱dB (σ≈۳٫۹dB) فرق داشت و «هوای» HD هم از همین باند ساخته می‌شود؛ ۷ باند اصلی فقط σ≈۱٫۱dB.
 * اینجا هر جمله پیش از چسباندن (و پیش از HD) سنجیده و به میانهٔ ۱۴ جملهٔ اخیر همان گوینده (به‌علاوهٔ جمله‌های همین درخواست) نزدیک می‌شود.
 * باندهای اصلی: آستانه ۰٫۸dB، ۰٫۷× انحراف، حداکثر ±۲٫۵dB. بالاها: آستانه ۰٫۵dB، ۱٫۰× انحراف، کاهش تا ۶dB و افزایش تا ۴dB (شلف ۶٫۵kHz؛ افزایش کمتر تا خشِ جمله‌های تیره زیاد نشود).
 * جمله‌ای با فریم واک‌دار کم (< ۲۵) نه اصلاح می‌شود نه در تاریخچه می‌رود. خروجی کش دست‌نخورده می‌ماند (اصلاح پس از کش است).
 */
private object PieceColor {
    private const val KEEP = 14
    private val hist = HashMap<String, ArrayList<DoubleArray>>()

    private fun median(v: DoubleArray): Double {
        v.sort()
        val n = v.size
        return if (n % 2 == 1) v[n / 2] else 0.5 * (v[n / 2 - 1] + v[n / 2])
    }

    fun match(id: String, pieces: ArrayList<FloatArray>, sr: Int) {
        val ms = ArrayList<DoubleArray?>()
        for (x in pieces) ms.add(ColorSv.measure(x, sr, 25))
        var tg: DoubleArray? = null
        synchronized(this) {
            val h = hist.getOrPut(id) { ArrayList() }
            val all = ArrayList<DoubleArray>(h)
            for (m in ms) if (m != null) all.add(m)
            if (all.size >= 3) {
                val t = DoubleArray(8)
                for (b in 0 until 8) t[b] = median(DoubleArray(all.size) { all[it][b] })
                tg = t
            }
            for (m in ms) if (m != null) { h.add(m); while (h.size > KEEP) h.removeAt(0) }
        }
        val t = tg ?: return
        for (k in pieces.indices) {
            val m = ms[k] ?: continue
            var x = pieces[k]
            val gs = DoubleArray(7)
            var any = false
            for (b in 0 until 7) {
                val d = m[b] - t[b]
                if (d.isNaN() || d.isInfinite()) continue
                if (Math.abs(d) > 0.8) {
                    val g = (-Math.signum(d) * (Math.abs(d) - 0.8) * 0.7).coerceIn(-2.5, 2.5)
                    if (Math.abs(g) >= 0.1) { gs[b] = g; any = true }
                }
            }
            if (any) x = MasterFx.bandEq(x, sr, gs)
            val dh = m[7] - t[7]
            if (!dh.isNaN() && !dh.isInfinite() && Math.abs(dh) > 0.5) {
                val g = (-Math.signum(dh) * (Math.abs(dh) - 0.5)).coerceIn(-6.0, 4.0)
                if (Math.abs(g) >= 0.1) x = MasterFx.hfShelf(x, sr, g)
            }
            if (x !== pieces[k]) pieces[k] = x
        }
    }
}

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
    private fun comp(x: DoubleArray, sr: Int, thr: Double, knee: Double, ratio: Double, att: Double, rel: Double, look: Double, makeup: Boolean = true) {
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
        val mk = if (makeup) Math.pow(Math.pow(10.0, -gainDb(0.0) / 20.0), 0.6) else 1.0
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

    /** مکث‌بُر: در ضبط واقعی، لابه‌لای مکث‌ها (۱٫۴ثانیه) خطوط هارمونیک ثابت حدود −۵۰ تا −۵۶ dBFS بود که هر ~۰٫۳ثانیه فرکانسشان پله‌ای عوض می‌شد
     *  (وزوز «گیژ»)؛ دُم/لِید ساکت خروجی مدل. حذف‌کنندهٔ طیفی نویز ایستا را فرض می‌کند و خط‌های پله‌ای را نمی‌گیرد.
     *  اینجا فقط بازه‌های پیوستهٔ ≥ minMs که انرژی‌شان ≥ |relDb| زیر سطح گفتار است تا floorDb کم می‌شوند (با محوشدن نرم). گفتار و مکث‌های کوتاه بین واژه‌ها دست‌نخورده‌اند. */
    private fun pauseMute(x: DoubleArray, sr: Int, relDb: Double, minMs: Double, floorDb: Double, fadeMs: Double) {
        val n = x.size
        val blk = maxOf(8, (sr * 0.004).toInt())
        val nb = (n + blk - 1) / blk
        if (nb < 24) return
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
        val blkMs = blk * 1000.0 / sr
        val minB = Math.ceil(minMs / blkMs).toInt()
        val gmin = Math.pow(10.0, floorDb / 20.0)
        val g = DoubleArray(nb) { 1.0 }
        var b = 0
        var any = false
        while (b < nb) {
            if (env[b] < th) {
                var e = b
                while (e < nb && env[e] < th) e++
                if (e - b >= minB) { for (j in b until e) g[j] = gmin; any = true }
                b = e
            } else b++
        }
        if (!any) return
        val c = 1.0 - Math.exp(-4.0 / (fadeMs / blkMs))
        for (i in 1 until nb) g[i] = g[i - 1] + c * (g[i] - g[i - 1])
        for (i in nb - 2 downTo 0) g[i] = g[i + 1] + c * (g[i] - g[i + 1])
        for (i in 0 until n) {
            val pos = (i - blk / 2.0) / blk
            val b0 = Math.floor(pos).toInt().coerceIn(0, nb - 1)
            val b1 = minOf(nb - 1, b0 + 1)
            val f = (pos - b0).coerceIn(0.0, 1.0)
            x[i] *= minOf(1.0, g[b0] + (g[b1] - g[b0]) * f)
        }
    }

    private fun softClip(v: Double): Float {
        val a = Math.abs(v)
        if (a <= 0.98) return v.toFloat()
        val y = 0.98 + 0.02 * Math.tanh((a - 0.98) / 0.02)
        return (if (v < 0) -y else y).toFloat()
    }


    /** دی‌اسر (کاهش سین و خش): باند ۵–۱۰٫۵kHz فقط وقتی نسبت به کل سیگنال غالب شود (سین/شین) کم می‌شود؛ amt ۰…۱ */
    private fun deEss(x: DoubleArray, sr: Int, amt: Double) {
        val n = x.size
        val sb = x.copyOf()
        highpass(5000.0, 0.8, sr).run(sb); lowpass(Math.min(10500.0, sr * 0.45), 0.8, sr).run(sb)
        val ca = Math.exp(-1.0 / (0.001 * sr)); val cr = Math.exp(-1.0 / (0.03 * sr)); val cb = Math.exp(-1.0 / (0.02 * sr))
        val thr = 0.5 - 0.2 * amt; val minG = 1.0 - 0.8 * amt
        var es = 0.0; var eb = 0.0; var gc = 1.0
        for (i in 0 until n) {
            val a = Math.abs(sb[i]); val b = Math.abs(x[i])
            es = if (a > es) ca * es + (1 - ca) * a else cb * es + (1 - cb) * a
            eb = if (b > eb) ca * eb + (1 - ca) * b else cb * eb + (1 - cb) * b
            val r = es / (eb + 1e-5)
            val tg = if (r > thr && eb > 0.004) Math.max(minG, thr / r) else 1.0
            gc = if (tg < gc) ca * gc + (1 - ca) * tg else cr * gc + (1 - cr) * tg
            x[i] -= sb[i] * (1.0 - gc)
        }
    }

    private fun bessel0(x: Double): Double { var s = 1.0; var t = 1.0; val q = x * x / 4.0; var k = 1; while (k < 40) { t *= q / (k.toDouble() * k); s += t; if (t < 1e-12 * s) break; k++ }; return s }

    /**
     * HD (نسخهٔ ۳): بالانمونه‌برداری ۲× با فیلتر نیم‌باند Kaiser (۶۴ ضریب، β=۹؛ بدون تغییر در طیف اصلی) + «هوای» بالا بدون غیرخطی‌سازی:
     * انتقال فرکانسی باند ۵٫۵–۱۰٫۵kHz به ۱۱–۱۶kHz (جهت و شکل طیف حفظ می‌شود) با شیب نزولی؛ هیچ هارمونیک/اعوجاجی ساخته نمی‌شود و بالای ۱۶kHz چیزی نمی‌ماند.
     * گیت نرم روی بلندی باند ۵٫۵–۱۰٫۵kHz: مکث‌ها و واکه‌های بی‌بالا بی‌صدا می‌مانند (نویز اضافه نمی‌شود). amt ۰…۱ شدت بازسازی
     */
    fun hdUp(src: FloatArray, sr: Int, amt: Double = 0.5): FloatArray {
        val n = src.size
        if (n < 64) return src
        val K = 32
        val h = DoubleArray(2 * K); var hs = 0.0
        val b0 = bessel0(9.0)
        for (j in 0 until 2 * K) {
            val t = (j - K + 1) - 0.5
            val r = t / K
            h[j] = Math.sin(Math.PI * t) / (Math.PI * t) * bessel0(9.0 * Math.sqrt(Math.max(0.0, 1.0 - r * r))) / b0
            hs += h[j]
        }
        for (j in h.indices) h[j] /= hs
        val x = DoubleArray(2 * n)
        for (i in 0 until n) {
            x[2 * i] = src[i].toDouble()
            var acc = 0.0
            for (j in 0 until 2 * K) { val idx = i + j - K + 1; if (idx in 0 until n) acc += src[idx] * h[j] }
            x[2 * i + 1] = acc
        }
        if (amt > 0.0) {
            val sr2 = sr * 2
            // باند منبع = نیمهٔ بالایی پهنای باند مدل (۲۲٫۰۵kHz ⇒ ۵٫۵–۱۰٫۵kHz؛ مدل ۱۶kHz ⇒ ۴–۷٫۶kHz)
            val nyq = sr / 2.0; val fLo = nyq * 0.5; val fHi = nyq * 0.95
            val b = x.copyOf()
            highpass(fLo, 0.8, sr2).run(b); highpass(fLo, 0.8, sr2).run(b)
            lowpass(fHi, 0.8, sr2).run(b); lowpass(fHi, 0.8, sr2).run(b)
            // انتقال فرکانسی +۵٫۵kHz (مدولاسیون با کسینوس + بالاگذر ۳ مرحله‌ای) ⇒ باند ۱۱–۱۶kHz با همان جهت و شکل طیف؛ شیب نزولی نرم
            val ext = DoubleArray(2 * n)
            val wm = 2.0 * Math.PI * fLo / sr2
            for (i in ext.indices) ext[i] = 2.0 * b[i] * Math.cos(wm * i)
            for (k in 0 until 3) highpass(nyq, 0.8, sr2).run(ext)
            lowpass(nyq * 1.47, 0.8, sr2).run(ext); lowpass(nyq * 1.47, 0.8, sr2).run(ext)
            lowpass(nyq * 1.13, 0.6, sr2).run(ext)
            val ca = Math.exp(-1.0 / (0.002 * sr2)); val cr = Math.exp(-1.0 / (0.025 * sr2))
            val gain = 0.35 * amt; val mid = 0.3 * amt
            var eb = 0.0; var eh = 0.0
            for (i in x.indices) {
                val a0 = Math.abs(b[i])
                eb = if (a0 > eb) ca * eb + (1 - ca) * a0 else cr * eb + (1 - cr) * a0
                val t = ((eb - 0.0015) / 0.004).coerceIn(0.0, 1.0)
                val gt = t * t * (3.0 - 2.0 * t)   // گیت نرم: مکث و واکه‌های بی‌بالا بی‌صدا می‌مانند
                var hf = ext[i] * gain * gt
                val ah = Math.abs(hf)
                eh = if (ah > eh) ca * eh + (1 - ca) * ah else cr * eh + (1 - cr) * ah
                if (eh > 0.5 * eb) hf *= 0.5 * eb / eh   // سقف نسبی: بالاها هرگز بلندتر از نصف باند منبع نمی‌شوند (بدون تیزی ناگهانی)
                x[i] += Math.tanh(hf * 4.0) / 4.0 + b[i] * mid * gt   // + کمی حضور باند ۵٫۵–۱۰٫۵kHz فقط هنگام وجود محتوا
            }
        }
        return FloatArray(x.size) { softClip(x[it]) }
    }

    /** تنظیمات اکولایزر کاربر (کلید eq از index.html؛ همان ساختار EQM.payload). null ⇒ خاموش یا کاملاً تخت */
    class EqCfg(val pre: Double, val lc: Double, val hc: Double, val q: Double, val lim: Boolean, val fc: DoubleArray, val g: DoubleArray, val ds: Double = 0.0, val cp: Double = 0.0) {
        companion object {
            fun from(o: org.json.JSONObject?): EqCfg? {
                if (o == null) return null
                val arr = o.optJSONArray("bands") ?: return null
                val n = Math.min(arr.length(), 24)
                val fc = DoubleArray(n); val g = DoubleArray(n)
                for (i in 0 until n) {
                    val b = arr.optJSONArray(i) ?: return null
                    fc[i] = b.optDouble(0, 0.0); g[i] = b.optDouble(1, 0.0).coerceIn(-12.0, 12.0)
                    if (fc[i].isNaN() || g[i].isNaN()) return null
                }
                val lv = o.opt("limiter")
                val lim = when (lv) { is Boolean -> lv; is Number -> lv.toDouble() != 0.0; else -> true }
                val c = EqCfg(o.optDouble("pre", 0.0).coerceIn(-12.0, 12.0), o.optDouble("lc", 0.0).coerceIn(0.0, 500.0),
                    o.optDouble("hc", 0.0).coerceIn(0.0, 20000.0), o.optDouble("q", 1.1).coerceIn(0.3, 4.0), lim, fc, g,
                    o.optDouble("ds", 0.0).coerceIn(0.0, 100.0), o.optDouble("cp", 0.0).coerceIn(0.0, 100.0))
                val flat = Math.abs(c.pre) < 0.01 && c.lc <= 0.0 && c.hc <= 0.0 && c.ds < 1.0 && c.cp < 1.0 && c.g.all { Math.abs(it) < 0.01 }
                return if (flat) null else c
            }
        }
    }

    /**
     * اکولایزر کاربر؛ همان گرافِ مرورگر (EQM.build): بهرهٔ کلی → بالاگذر → ۱۰ باند (شلف پایین، ۸ پیک با Q، شلف بالا) → پایین‌گذر → محافظ برش (−۲dB، ۲۰:۱).
     * Q بالاگذر/پایین‌گذر در WebAudio بر حسب dB است (۰٫۷dB ≈ ۱٫۰۸۵ خطی، مثل lowpass مسترینگ). محافظ برش بدون جبران بلندی (تغییر ناخواستهٔ سطح هنگام روشن/خاموش‌کردن نباشد).
     * باند/قطعی که از ۰٫۴۵×نرخ نمونه بالاتر باشد (مثل شلف ۱۲kHz در صدای ۲۲٫۰۵kHz) روی همان سقف طراحی می‌شود؛ پایین‌گذر بالای سقف رد می‌شود.
     */
    /** r19 (ناظر رنگ): شلف بالای ۱٫۸kHz روی کل تکه؛ db منفی = تیره‌تر (شیب طیف تندتر)، مثبت = روشن‌تر. */
    private val CB_FC = doubleArrayOf(245.0, 566.0, 1095.0, 1936.0, 2958.0, 4183.0, 5701.0)   // مراکز هندسی ۷ باند ColorSv

    /** r23 (EQ پویای ناظر رنگ): پیک ملایم (Q=۱٫۶) روی مرکز هر باند ۷گانهٔ ColorSv؛ gains[b] برحسب dB (۰ = بدون فیلتر). */
    fun bandEq(src: FloatArray, sr: Int, gains: DoubleArray): FloatArray {
        if (src.isEmpty()) return src
        val x = DoubleArray(src.size) { src[it].toDouble() }
        for (b in gains.indices) {
            if (b >= CB_FC.size || Math.abs(gains[b]) < 0.05) continue
            peaking(CB_FC[b], 1.6, gains[b], sr).run(x)
        }
        return FloatArray(x.size) { x[it].toFloat() }
    }

    /** r24: شلف بالای ۶٫۵kHz (سطح «خش/هوا»)؛ db منفی = بالاها کمتر. */
    fun hfShelf(src: FloatArray, sr: Int, db: Double): FloatArray {
        if (src.isEmpty() || Math.abs(db) < 0.05) return src
        val x = DoubleArray(src.size) { src[it].toDouble() }
        highShelf(6500.0, db, sr).run(x)
        return FloatArray(x.size) { x[it].toFloat() }
    }

    fun tiltShelf(src: FloatArray, sr: Int, db: Double): FloatArray {
        if (src.isEmpty() || Math.abs(db) < 0.05) return src
        val x = DoubleArray(src.size) { src[it].toDouble() }
        highShelf(1800.0, db, sr).run(x)
        return FloatArray(x.size) { x[it].toFloat() }
    }

    fun eq(src: FloatArray, sr: Int, c: EqCfg): FloatArray {
        if (src.isEmpty()) return src
        val x = DoubleArray(src.size) { src[it].toDouble() }
        if (c.pre != 0.0) { val gp = Math.pow(10.0, c.pre / 20.0); for (i in x.indices) x[i] *= gp }
        if (c.lc > 0.0) highpass(c.lc, 1.085, sr).run(x)
        val last = c.fc.size - 1
        for (i in c.fc.indices) {
            val f = c.fc[i]; val gd = c.g[i]
            if (f <= 0.0 || Math.abs(gd) < 0.01) continue
            if (f > sr * 0.45) continue   // باند بالاتر از پهنای باند صدا (مثلاً ۱۶kHz در ۲۲٫۰۵kHz) اثری ندارد؛ با HD فعال می‌شود
            val f0 = f
            (if (i == 0) lowShelf(f0, gd, sr) else if (i == last) highShelf(f0, gd, sr) else peaking(f0, c.q, gd, sr)).run(x)
        }
        if (c.ds >= 1.0) deEss(x, sr, c.ds / 100.0)
        if (c.cp >= 1.0) comp(x, sr, -14.0 - 0.2 * c.cp, 12.0, 1.4 + 0.036 * c.cp, 0.010, 0.20, 0.004)
        if (c.hc > 0.0 && c.hc < sr * 0.45) lowpass(c.hc, 1.085, sr).run(x)
        if (c.lim) comp(x, sr, -2.0, 0.0, 20.0, 0.003, 0.12, 0.003, false)
        return FloatArray(x.size) { softClip(x[it]) }
    }

    fun process(src: FloatArray, sr: Int, pk: Float, hs: Float, lp: Float, strong: Boolean = false, pauseLvl: Int = 2): FloatArray {
        if (src.isEmpty()) return src
        val x = DoubleArray(src.size) { src[it].toDouble() }
        highpass(75.0, 1.122, sr).run(x)
        lowShelf(180.0, 1.3, sr).run(x)
        peaking(2800.0, 0.9, pk.toDouble(), sr).run(x)
        peaking(7000.0, 1.3, -1.8, sr).run(x)
        // r189 (عیب‌یابی خروجی مانا، ۱۷۰ ثانیه): طیف بلندمدت گفتار بین ۴٫۵ تا ۹kHz موج‌دار بود — سوراخ ≈ −۸dB در ۵٫۵–۶٫۵kHz، قله ≈ +۶dB در ۷–۷٫۵kHz و قلهٔ +۵dB در ۴٫۵–۵kHz (نسبت به ۵–۵٫۸kHz)؛
        // این سه فیلتر نرم، موج را پر/هموار می‌کنند (ایمن: حداکثر +۴dB؛ فقط مانا). اگر خش بالا زیاد شد، ۶۰۰۰←+۲٫۵ یا حذف کنید
        if (strong) { peaking(6000.0, 2.2, 4.0, sr).run(x); peaking(7250.0, 2.5, -3.0, sr).run(x); peaking(4750.0, 2.5, -2.0, sr).run(x) }
        highShelf(6000.0, hs.toDouble(), sr).run(x)
        lowpass(lp.toDouble(), 1.085, sr).run(x)
        comp(x, sr, -22.0, 24.0, 3.0, 0.005, 0.15, 0.006)
        for (i in x.indices) x[i] *= 1.1
        gate(x, sr, if (strong) -32.0 else -34.0, if (strong) -7.0 else -6.0)   // مانا: گیت ملایم‌تر؛ حذف نویز طیفی کار اصلی را می‌کند و گیت تند، صامت‌های ضعیف و پایان واژه را به‌صورت «غ‌غ‌غ» می‌برد
        if (strong && pauseLvl > 0) pauseMute(x, sr, when (pauseLvl) { 1 -> -35.0; 2 -> -30.0; else -> -25.0 }, 100.0, -42.0, 18.0)   // مانا: حذف وزوز هارمونیک مکث‌ها (بالا)
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
        val warm = !cmd && MediaBridge.active && ms != null   /* اعلان کامل از قبل هست: اعلان ساده را دوباره نگذار (چشمک‌زدن) */
        if (!cmd) {   /* هر startForegroundService باید همین‌جا و بی‌قید‌وشرط startForeground بگیرد */
            try {
                n0 = if (warm) { try { build() } catch (e: Throwable) { quick() } } else quick()
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
        val n = if (warm && n0 != null) n0 else try { build() } catch (e: Throwable) { n0 }
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

/**
 * r17: حذف‌نویز عصبی DPDFNet (مدل سبک dpdfnet_baseline.onnx ‏۱۶kHz از کتابخانهٔ همین sherpa-onnx)؛ کاملاً آفلاین: مدل داخل APK در assets/denoise/ است
 * و یک‌بار به filesDir/denoise/ کپی می‌شود. پیش از فیلتر کلاسیک (SpeechClean) روی خروجی خام هر جمله اجرا می‌شود:
 *   خروجی خام (۲۲٫۰۵kHz) → کم‌نمونه‌برداری به ۱۶kHz → DPDFNet (با attenuationLimitDb) → برگشت → هم‌ترازی تأخیر → ترکیب (سطح ۲: ۸۵٪ عصبی، سطح ۳: ۱۰۰٪).
 * هر خطا (نبودن مدل/کلاس، خروجی نامعتبر، پاک‌شدن گفتار) ⇒ همان ورودی برمی‌گردد و فقط فیلتر کلاسیک اجرا می‌شود؛ پخش هرگز قطع نمی‌شود.
 * کلاس‌های کاتلین sherpa با بازتاب ساخته می‌شوند تا اگر نام/فیلدی در نسخهٔ sherpa فرق داشت بیلد نشکند؛ وضعیت در ping/diag زیر کلید nd گزارش می‌شود.
 * فقط روی رشتهٔ ساخت صدا (cpu) صدا زده می‌شود.
 */
private object NeuralDenoise {
    private const val PKG = "com.k2fsa.sherpa.onnx."
    /** مدل‌های DPDFNet قابل انتخاب (کلید رابط ← فایل داخل assets/denoise/) */
    private val MODELS = linkedMapOf("baseline" to "dpdfnet_baseline.onnx")   // فقط مدل پایهٔ سبک؛ بقیه حذف شدند (کلیدهای قدیمی ← baseline)
    @Volatile private var cur = "baseline"
    fun norm(m: String?): String = if (m != null && MODELS.containsKey(m)) m else "baseline"
    /** کلیدهای مدل‌هایی که واقعاً داخل APK هستند، با کاما جدا */
    fun available(ctx: Context): String {
        val l = try { ctx.assets.list("denoise")?.toSet() ?: emptySet() } catch (x: Throwable) { emptySet<String>() }
        return MODELS.filter { l.contains(it.value) }.keys.joinToString(",")
    }
    @Volatile private var eng: Any? = null
    private var runM: java.lang.reflect.Method? = null
    private var engSr = 16000
    @Volatile private var err: String? = null
    @Volatile private var tried = false
    private const val ATT_DB = 15f
    private var fails = 0
    private var slow = 0
    @Volatile private var lastMs = 0L

    private fun why(e: Throwable): String {
        val c = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
        return (c.javaClass.simpleName + ": " + (c.message ?: "")).take(160)
    }

    private fun setF(o: Any, name: String, v: Any?, must: Boolean = true) {
        try { val f = o.javaClass.getDeclaredField(name); f.isAccessible = true; f.set(o, v) }
        catch (e: Throwable) { if (must) throw IllegalStateException("فیلد $name در " + o.javaClass.simpleName + " نیست") }
    }

    private fun newObj(n: String): Any {
        val c = Class.forName(PKG + n)
        val k = c.getDeclaredConstructor(); k.isAccessible = true
        return k.newInstance()
    }

    /** وضعیت برای diag/ping: ok | idle (هنوز بارگذاری نشده) | no-model | off:<علت> */
    fun status(ctx: Context): String {
        if (eng != null) return "ok " + cur + " " + (engSr / 1000) + "k" + (if (lastMs > 0) " " + lastMs + "ms" else "")
        val e = err
        if (e != null) return "off:$cur: $e"
        val has = try { ctx.assets.list("denoise")?.contains(MODELS[cur] ?: "") == true } catch (x: Throwable) { false }
        return if (has) "idle" else "no-model"
    }

    @Synchronized
    fun ready(ctx: Context, model: String): Boolean {
        val m = norm(model)
        if (m != cur) { release(); cur = m; tried = false; err = null; fails = 0; slow = 0; lastMs = 0L }   // تغییر مدل: بارگذاری دوباره و پاک‌شدن وضعیت خطا
        if (eng != null) return true
        if (tried) return false
        tried = true
        return try { init(ctx); err = null; true } catch (e: Throwable) { eng = null; runM = null; err = why(e); false }
    }

    private fun init(ctx: Context) {
        val dir = File(ctx.filesDir, "denoise"); dir.mkdirs()
        val NAME = MODELS[cur] ?: MODELS["baseline"]!!
        val f = File(dir, NAME)
        if (!f.isFile || f.length() < 1_000_000L) {
            val has = try { ctx.assets.list("denoise")?.contains(NAME) == true } catch (x: Throwable) { false }
            if (!has) throw IllegalStateException("مدل در APK نیست (assets/denoise/$NAME)")
            val tmp = File(dir, "$NAME.tmp")
            ctx.assets.open("denoise/$NAME").use { i -> FileOutputStream(tmp).use { o -> i.copyTo(o, 64 * 1024) } }
            if (tmp.length() < 1_000_000L || !tmp.renameTo(f)) { tmp.delete(); throw IllegalStateException("کپی مدل ناقص بود") }
        }
        val dp = newObj("OfflineSpeechDenoiserDpdfNetModelConfig"); setF(dp, "model", f.absolutePath)
        setF(dp, "attenuationLimitDb", ATT_DB, false)   // بخشی از طیف نویزی را برمی‌گرداند تا انتهای جمله‌ها و مکث‌ها «بریده» نشود
        val mc = newObj("OfflineSpeechDenoiserModelConfig")
        setF(mc, "dpdfnet", dp)
        setF(mc, "numThreads", 1, false)
        setF(mc, "debug", false, false)
        setF(mc, "provider", "cpu", false)
        val cfg = newObj("OfflineSpeechDenoiserConfig"); setF(cfg, "model", mc)
        val cd = Class.forName(PKG + "OfflineSpeechDenoiser")
        val am = android.content.res.AssetManager::class.java
        val ct = cd.constructors.firstOrNull { !it.isSynthetic && it.parameterTypes.size == 2 && it.parameterTypes[0] == am && it.parameterTypes[1] == cfg.javaClass }
            ?: cd.constructors.firstOrNull { !it.isSynthetic && it.parameterTypes.size == 1 && it.parameterTypes[0] == cfg.javaClass }
            ?: throw IllegalStateException("سازندهٔ OfflineSpeechDenoiser پیدا نشد")
        val o = if (ct.parameterTypes.size == 2) ct.newInstance(null, cfg) else ct.newInstance(cfg)
        val rm = cd.methods.firstOrNull { it.name == "run" && it.parameterTypes.size == 2 && it.parameterTypes[0] == FloatArray::class.java }
            ?: throw IllegalStateException("متد run پیدا نشد")
        var sr = 16000
        for (nm in arrayOf("sampleRate", "getSampleRate")) {
            try { val v = cd.getMethod(nm).invoke(o); if (v is Int && v in 8000..96000) { sr = v; break } } catch (e: Throwable) {}
        }
        engSr = sr; runM = rm; eng = o
    }

    fun release() {
        try { eng?.let { it.javaClass.getMethod("release").invoke(it) } } catch (e: Throwable) {}
        eng = null; runM = null
    }

    private fun samplesOf(r: Any): FloatArray? {
        try { val f = r.javaClass.getDeclaredField("samples"); f.isAccessible = true; (f.get(r) as? FloatArray)?.let { return it } } catch (e: Throwable) {}
        try { return r.javaClass.getMethod("getSamples").invoke(r) as? FloatArray } catch (e: Throwable) {}
        return null
    }

    private fun rateOf(r: Any): Int? {
        try { val f = r.javaClass.getDeclaredField("sampleRate"); f.isAccessible = true; (f.get(r) as? Int)?.let { return it } } catch (e: Throwable) {}
        try { return r.javaClass.getMethod("getSampleRate").invoke(r) as? Int } catch (e: Throwable) {}
        return null
    }

    /** ورودی خام جمله → خروجی با نویز کمتر (هم‌طول و هم‌نرخ با ورودی)؛ mix سهم عصبی ۰…۱. هر مشکلی ⇒ خود x
     *  مدل ۱۶kHz فقط باند پایین (تا ۸kHz) را پردازش می‌کند؛ باند بالای ورودی (۸kHz…) دست‌نخورده می‌ماند و به فیلتر کلاسیک می‌رسد. */
    @Synchronized
    fun run(x: FloatArray, sr: Int, mix: Float): FloatArray {
        val o = eng ?: return x
        val m = runM ?: return x
        if (x.size < sr / 5 || mix <= 0f) return x
        try {
            val t0 = System.nanoTime()
            val dn = engSr
            val up = resample(x, sr, dn)
            val inp = up.copyOf(up.size + dn / 5)          // ۲۰۰ms سکوت انتهایی: دُم جمله پیش از بستن خروجی مدل بریده نشود
            val r = m.invoke(o, inp, dn) ?: return x
            val ys = samplesOf(r) ?: return x
            val ysr = rateOf(r) ?: dn
            if (ys.size < 64) return x
            val w = if (ysr == sr) ys else resample(ys, ysr, sr)
            // جداسازی باند: dryLow = باند پایین ورودی (هم‌پهنای مدل)، high = بقیه
            val dryLow: FloatArray
            val high: FloatArray?
            if (ysr < sr) {
                val dl = resample(up, dn, sr)
                dryLow = FloatArray(x.size) { if (it < dl.size) dl[it] else 0f }
                high = FloatArray(x.size) { x[it] - dryLow[it] }
            } else { dryLow = x; high = null }
            val lag = bestLag(dryLow, w, sr)
            val wa = FloatArray(x.size)
            var eI = 0.0; var eO = 0.0
            for (i in x.indices) {
                val j = i + lag
                val v = if (j >= 0 && j < w.size) w[j] else dryLow[i]   // بیرون از خروجی مدل: همان ورودی (نه صفر)
                if (v.isNaN() || v.isInfinite()) throw IllegalStateException("خروجی نامعتبر (NaN)")
                wa[i] = v
                eI += dryLow[i].toDouble() * dryLow[i]; eO += v.toDouble() * v
            }
            // ایمنی: اگر مدل گفتار را پاک کرده یا بلندی را منفجر کرده، دست‌نخورده برگرد
            if (eI > 1e-9 && (eO < eI * 0.05 || eO > eI * 8.0)) return x
            val g = frameMix(dryLow, wa, sr, mix.coerceIn(0f, 1f))
            val hop = maxOf(1, sr / 100); val nf = g.size
            val out = FloatArray(x.size)
            for (i in x.indices) {
                val fi = minOf(nf - 1, i / hop)
                val u = (i - fi * hop).toFloat() / hop
                val a = g[fi] * (1f - u) + g[minOf(nf - 1, fi + 1)] * u
                out[i] = (dryLow[i] * (1f - a) + wa[i] * a + (if (high != null) high[i] else 0f)).coerceIn(-1f, 1f)
            }
            fails = 0
            val ms = (System.nanoTime() - t0) / 1_000_000L
            lastMs = ms
            // نگهبان گرما/کندی: اگر مدل بیش از نیمِ زمان واقعیِ صدا خرج کند، سه بار پیاپی ⇒ غیرفعال (ساخت صدا از پخش عقب نماند)
            if (ms > 0.5 * x.size * 1000.0 / sr) slow++ else slow = 0
            if (slow >= 3) { err = "کند: " + ms + "ms برای " + (x.size * 1000L / sr) + "ms صدا"; eng = null; runM = null }
            return out
        } catch (e: Throwable) {
            err = why(e); fails++
            if (fails >= 3) { eng = null; runM = null }   // سه شکست پیاپی: دیگر تلاش نمی‌شود (تا اجرای بعدی برنامه)
            return x
        }
    }

    /** سهم عصبی هر فریم ۱۰ms: جایی که مدل از فریمِ «گفتارمانند» (انرژی ≥ ۲٫۵× کف نویز) بیش از ~۶dB کم کرده، سهم عصبی کم می‌شود
     *  (انتهای جمله و مکث‌ها بریده نمی‌شوند). کاهش سهم فوری، برگشت آرام. */
    private fun frameMix(d: FloatArray, w: FloatArray, sr: Int, a: Float): FloatArray {
        val hop = maxOf(1, sr / 100); val nf = (d.size + hop - 1) / hop
        val rd = FloatArray(nf); val rw = FloatArray(nf)
        for (f in 0 until nf) {
            val s0 = f * hop; val s1 = minOf(d.size, s0 + hop)
            var ed = 0.0; var ew = 0.0
            for (i in s0 until s1) { ed += d[i].toDouble() * d[i]; ew += w[i].toDouble() * w[i] }
            val n = maxOf(1, s1 - s0)
            rd[f] = Math.sqrt(ed / n).toFloat(); rw[f] = Math.sqrt(ew / n).toFloat()
        }
        val sorted = rd.copyOf(); sorted.sort()
        val floor = maxOf(1e-5f, sorted[(nf * 0.15).toInt().coerceIn(0, nf - 1)])
        val g = FloatArray(nf)
        var prev = a
        for (f in 0 until nf) {
            var t = a
            if (rd[f] > 2.5f * floor) {
                val r = rw[f] / (rd[f] + 1e-9f)
                val p = ((0.5f - r) / 0.3f).coerceIn(0f, 1f)
                t = a * (1f - p)
            }
            prev = if (t < prev) t else prev + 0.3f * (t - prev)
            g[f] = prev
        }
        return g
    }

    /** تأخیر (نمونه) که wet[i+lag] بهترین هم‌ترازی با dry[i] را دارد؛ جست‌وجوی درشت روی نسخهٔ ۴ برابر کم‌نمونه، سپس ریز؛ تا ±۳۰ms */
    private fun bestLag(dry: FloatArray, wet: FloatArray, sr: Int): Int {
        val n = minOf(dry.size, wet.size, sr * 8)
        if (n < sr / 4) return 0
        val d = 4
        val dn = n / d
        val dx = FloatArray(dn); val dw = FloatArray(dn)
        for (i in 0 until dn) { var a = 0f; var b = 0f; for (k in 0 until d) { a += dry[i * d + k]; b += wet[i * d + k] }; dx[i] = a; dw[i] = b }
        val maxL = (sr * 0.03).toInt() / d
        var best = 0; var bs = Double.NEGATIVE_INFINITY
        for (l in -maxL..maxL) {
            var s = 0.0; var e = 1e-9
            val i0 = maxOf(0, -l); val i1 = minOf(dn, dn - l)
            for (i in i0 until i1) { val v = dw[i + l]; s += dx[i] * v; e += v * v }
            val sc = s / Math.sqrt(e)
            if (sc > bs) { bs = sc; best = l }
        }
        val c = best * d
        var bl = c; var b2 = Double.NEGATIVE_INFINITY
        for (l in c - d..c + d) {
            var s = 0.0; var e = 1e-9
            val i0 = maxOf(0, -l); val i1 = minOf(n, n - l)
            for (i in i0 until i1) { val v = wet[i + l]; s += dry[i] * v; e += v * v }
            val sc = s / Math.sqrt(e)
            if (sc > b2) { b2 = sc; bl = l }
        }
        return bl
    }

    // ---- بازنمونه‌برداری چندفازی (سینک با پنجرهٔ Kaiser، β=۸) برای نسبت‌های گویا مثل ۲۲٫۰۵k ⇄ ۴۸k = ۳۲۰/۱۴۷ ----
    private val banks = HashMap<String, Array<FloatArray>>()

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    private fun bessel(x: Double): Double { var s = 1.0; var t = 1.0; val q = x * x / 4.0; var k = 1; while (k < 60) { t *= q / (k.toDouble() * k); s += t; if (t < 1e-12 * s) break; k++ }; return s }

    private fun bank(L: Int, M: Int, fc: Double, half: Int): Array<FloatArray> {
        val key = "$L/$M"
        banks[key]?.let { return it }
        val b0 = bessel(8.0)
        val tb = Array(L) { ph ->
            val h = DoubleArray(2 * half)
            var sum = 0.0
            for (j in 0 until 2 * half) {
                val dd = (j - half + 1) - ph.toDouble() / L
                val r = dd / half
                val w = if (Math.abs(r) >= 1.0) 0.0 else bessel(8.0 * Math.sqrt(1.0 - r * r)) / b0
                val a = Math.PI * fc * dd
                val sc = if (Math.abs(a) < 1e-9) 1.0 else Math.sin(a) / a
                h[j] = fc * sc * w; sum += h[j]
            }
            FloatArray(2 * half) { (h[it] / (if (Math.abs(sum) > 1e-9) sum else 1.0)).toFloat() }
        }
        banks[key] = tb
        return tb
    }

    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || x.isEmpty()) return x
        val g = gcd(from, to); val L = to / g; val M = from / g
        val fc = Math.min(1.0, L.toDouble() / M)
        val half = Math.ceil(16.0 / fc).toInt()
        val tb = bank(L, M, fc, half)
        val outN = ((x.size.toLong() * L) / M).toInt()
        val y = FloatArray(outN)
        val nt = 2 * half
        for (n in 0 until outN) {
            val num = n.toLong() * M
            val ix = (num / L).toInt(); val h = tb[(num % L).toInt()]
            val st = ix - half + 1
            var acc = 0.0
            if (st >= 0 && st + nt <= x.size) { for (j in 0 until nt) acc += x[st + j] * h[j] }
            else { for (j in 0 until nt) { val k = st + j; if (k >= 0 && k < x.size) acc += x[k] * h[j] } }
            y[n] = acc.toFloat()
        }
        return y
    }
}
