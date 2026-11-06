package com.pdfvoice.phon

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.Normalizer

/**
 * واج‌آرایی اختصاصی مانا (r187).
 *
 * مدل fa_IR-mana-medium را سازندگانش روی آوای «espeak + کسرهٔ اضافهٔ درج‌شده» فاین‌تیون کرده‌اند (مقالهٔ Fetrat و همکاران، ۲۰۲۵):
 * espeak ساده کسرهٔ اضافه را تقریباً نمی‌یابد (F1 ≈ ۲۰٪) ولی با تشخیص‌دهندهٔ ALBERT تقطیرشده ≈ ۹۰٪ می‌شود.
 * این شیء پیش از Phonemizer.phonemize فقط برای مانا اجرا می‌شود و جای کسرهٔ اضافه را با همان مدل (abreza/persian-ezafe-albert،
 * نسخهٔ ONNX کوانتیزه) پیدا می‌کند و با همان قرارداد متنی برنامه (کسره روی همخوان پایانی؛ «ۀ»؛ «یِ» پس از ا/و) روی متن می‌گذارد؛
 * خود espeak آن را «e» می‌خواند. هر خطا ← متن بدون تغییر (رفتار قبلی). فایل‌های مدل را build-mana-phon.sh هنگام بیلد داخل
 * assets/mana/ezafe می‌گذارد.
 *
 * محافظ: بعد از بارگذاری، یک جملهٔ مرجع اجرا می‌شود؛ اگر پاسخ مدل با انتظار نمی‌خواند (مثلاً توکنایزر ناهماهنگ)، ماژول
 * خودش را خاموش می‌کند و وضعیتش در status() گزارش می‌شود.
 */
object ManaPhon {
    const val VER = "mph1"
    private const val ASSET_DIR = "mana/ezafe"
    private const val THRESH = 0.6f          // احتمال کمینه برای درج کسره (کمی سخت‌گیرانه‌تر از argmax)
    private const val MAX_TOK = 120          // سقف توکن هر پنجره (مدل با ۱۲۸ آموزش دیده؛ جا برای [CLS]/[SEP])
    private const val SP = '\u2581'

    private val lock = Any()
    @Volatile private var state = 0          // ۰ هنوز بارگذاری نشده، ۱ آماده، ۲ فایل‌ها نیست، ۳ خطا/خودآزمون ناموفق
    @Volatile private var info = ""

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var inNames: Set<String> = emptySet()
    private var pieceId = HashMap<String, Int>()
    private var pieceScore = FloatArray(0)
    private var maxLen = 1
    private var unkId = 1
    private var unkScore = -10f
    private var clsId = 2
    private var sepId = 3

    /** برچسب کش: با تغییر آن، صدای ساخته‌شدهٔ قبلی مانا دوباره ساخته می‌شود */
    fun tag(on: Boolean): String = if (!on) "mp0" else VER + (if (state == 1) "e" else "")

    private fun assetsHave(ctx: Context): Boolean = try {
        val l = ctx.assets.list(ASSET_DIR)?.toList() ?: emptyList()
        l.contains("model_quantized.onnx") && l.contains("tokenizer.json")
    } catch (e: Throwable) { false }

    /** وضعیت برای نمایش در برنامه (بدون بارگذاری مدل) */
    fun status(ctx: Context): String = when (state) {
        1 -> "ok " + info
        2 -> "missing"
        3 -> "err: " + info
        else -> if (assetsHave(ctx)) "idle" else "missing"
    }

    /** مدل را (یک‌بار) بارگذاری می‌کند؛ true = آماده */
    fun ensure(ctx: Context): Boolean {
        if (state == 1) return true
        if (state == 2 || state == 3) return false
        synchronized(lock) {
            if (state == 1) return true
            if (state != 0) return false
            try {
                if (!assetsHave(ctx)) { state = 2; return false }
                load(ctx)
                selfTest()
            } catch (e: Throwable) {
                state = 3; info = (e.message ?: e.javaClass.simpleName).take(160)
                try { session?.close() } catch (x: Throwable) { }
                session = null
            }
            return state == 1
        }
    }

    // ---------------------------------------------------------------- بارگذاری
    private fun load(ctx: Context) {
        val am = ctx.assets
        // ۱) مدل: یک‌بار به filesDir کپی می‌شود (ORT از مسیر فایل می‌خواند؛ حافظهٔ کمتری می‌گیرد)
        val dir = File(ctx.filesDir, "mana-ezafe"); dir.mkdirs()
        val modelF = File(dir, "model_quantized.onnx"); val mark = File(dir, "model.ok")
        val ver = try { am.open("$ASSET_DIR/VERSION").bufferedReader().use { it.readText().trim() } } catch (e: Throwable) { "0" }
        if (!modelF.isFile || !mark.isFile || mark.readText() != ver) {
            mark.delete()
            am.open("$ASSET_DIR/model_quantized.onnx").use { i -> modelF.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
            mark.writeText(ver)
        }
        // ۲) توکنایزر (Unigram از tokenizer.json)
        val tj = am.open("$ASSET_DIR/tokenizer.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val m = JSONObject(tj).getJSONObject("model")
        if (m.optString("type") != "Unigram") throw IllegalStateException("tokenizer نوع Unigram نیست")
        val vocab = m.getJSONArray("vocab")
        val n = vocab.length()
        pieceScore = FloatArray(n); pieceId = HashMap(n * 2); maxLen = 1
        var minS = 0f
        for (i in 0 until n) {
            val e = vocab.getJSONArray(i)
            val p = e.getString(0); val s = e.getDouble(1).toFloat()
            pieceScore[i] = s
            if (!pieceId.containsKey(p)) pieceId[p] = i
            if (p.length > maxLen) maxLen = p.length
            if (s < minS) minS = s
        }
        unkId = m.optInt("unk_id", 1)
        unkScore = minS - 10f
        clsId = pieceId["[CLS]"] ?: 2
        sepId = pieceId["[SEP]"] ?: 3
        // ۳) نشست ONNX Runtime
        val e = OrtEnvironment.getEnvironment()
        val so = OrtSession.SessionOptions()
        so.setIntraOpNumThreads(2)
        val s = e.createSession(modelF.absolutePath, so)
        env = e; session = s; inNames = s.inputNames
    }

    // ---------------------------------------------------------------- توکنایزر
    /** نرمال‌ساز tokenizer.json: Replace(ZWNJ→«[ZWNJ]»)، Replace(``/'' → ")، NFKD، StripAccents (Precompiled تقریباً هویت است) */
    private fun norm(w: String): String {
        var s = w.replace("\u200c", "[ZWNJ]").replace("``", "\"").replace("''", "\"")
        s = Normalizer.normalize(s, Normalizer.Form.NFKD)
        val b = StringBuilder(s.length)
        for (c in s) if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) b.append(c)
        return b.toString()
    }

    /** هر واژه = یک تکهٔ Metaspace («▁» + واژه)؛ Viterbi روی نمرهٔ تکه‌ها (مثل HF Unigram) */
    private fun encodeWord(w: String): IntArray {
        val nw = norm(w)
        if (nw.isEmpty()) return IntArray(0)
        val t = SP + nw
        val n = t.length
        val best = FloatArray(n + 1) { -Float.MAX_VALUE }
        val prevLen = IntArray(n + 1); val prevId = IntArray(n + 1)
        best[0] = 0f
        for (i in 0 until n) {
            if (best[i] == -Float.MAX_VALUE) continue
            var one = false
            val lim = minOf(maxLen, n - i)
            for (l in 1..lim) {
                val id = pieceId[t.substring(i, i + l)] ?: continue
                if (l == 1) one = true
                val sc = best[i] + pieceScore[id]
                if (sc > best[i + l]) { best[i + l] = sc; prevLen[i + l] = l; prevId[i + l] = id }
            }
            if (!one) {
                val sc = best[i] + unkScore
                if (sc > best[i + 1]) { best[i + 1] = sc; prevLen[i + 1] = 1; prevId[i + 1] = unkId }
            }
        }
        if (best[n] == -Float.MAX_VALUE) return intArrayOf(unkId)
        val rev = ArrayList<Int>()
        var p = n
        while (p > 0) { rev.add(prevId[p]); p -= prevLen[p] }
        val out = ArrayList<Int>(rev.size)
        for (k in rev.indices.reversed()) {
            val id = rev[k]
            if (id == unkId && out.isNotEmpty() && out[out.size - 1] == unkId) continue   // fuse_unk
            out.add(id)
        }
        return out.toIntArray()
    }

    // ---------------------------------------------------------------- مدل
    /** احتمال کسرهٔ اضافه برای هر واژه (برچسب توکن اول واژه) */
    private fun classify(words: List<String>): FloatArray {
        val e = env ?: throw IllegalStateException("env")
        val s = session ?: throw IllegalStateException("session")
        val toks = words.map { encodeWord(it) }
        val probs = FloatArray(words.size)
        var i = 0
        while (i < words.size) {
            var j = i; var cnt = 0
            while (j < words.size && (j == i || cnt + toks[j].size <= MAX_TOK)) { cnt += toks[j].size; j++ }
            // پنجرهٔ [i, j)
            val ids = ArrayList<Int>(cnt + 2)
            val first = IntArray(j - i) { -1 }
            ids.add(clsId)
            for (k in i until j) {
                val tk = toks[k]
                if (tk.isEmpty()) continue
                if (ids.size + tk.size > MAX_TOK + 1) break   // واژهٔ بسیار بلند: بریده می‌شود
                first[k - i] = ids.size
                for (x in tk) ids.add(x)
            }
            ids.add(sepId)
            val L = ids.size
            val inputs = HashMap<String, OnnxTensor>()
            try {
                inputs["input_ids"] = OnnxTensor.createTensor(e, arrayOf(LongArray(L) { ids[it].toLong() }))
                if (inNames.contains("attention_mask")) inputs["attention_mask"] = OnnxTensor.createTensor(e, arrayOf(LongArray(L) { 1L }))
                if (inNames.contains("token_type_ids")) inputs["token_type_ids"] = OnnxTensor.createTensor(e, arrayOf(LongArray(L)))
                s.run(inputs).use { res ->
                    @Suppress("UNCHECKED_CAST")
                    val out = (res.get(0) as OnnxTensor).value as Array<Array<FloatArray>>
                    val lg = out[0]
                    for (k in i until j) {
                        val pos = first[k - i]
                        if (pos < 0 || pos >= lg.size) continue
                        val a = lg[pos]
                        if (a.size < 2) continue
                        val mx = maxOf(a[0], a[1])
                        val e0 = Math.exp((a[0] - mx).toDouble()); val e1 = Math.exp((a[1] - mx).toDouble())
                        probs[k] = (e1 / (e0 + e1)).toFloat()
                    }
                }
            } finally { for (t in inputs.values) try { t.close() } catch (x: Throwable) { } }
            i = j
        }
        return probs
    }

    /** جملهٔ مرجع (نمونهٔ کارت مدل): کسره روی «کتابخانه، مرکزی، دانشگاه، فضای» و نه روی بقیه */
    private fun selfTest() {
        val ws = "کتابخانه مرکزی دانشگاه شریف، فضای وسیع و چشم\u200cنوازی دارد.".split(" ")
        val p = classify(ws)
        val pos = intArrayOf(0, 1, 2, 4).count { p[it] > 0.5f }
        val neg = intArrayOf(3, 5, 6, 7, 8).count { p[it] <= 0.5f }
        info = "selftest pos=$pos/4 neg=$neg/5"
        if (pos >= 3 && neg >= 4) state = 1
        else { state = 3; info = "خودآزمون ناموفق؛ فعلاً بدون کسرهٔ اضافهٔ مدلی (" + info + ")" }
    }

    // ---------------------------------------------------------------- اعمال روی متن
    private val NEVER = HashSet<String>(listOf("و", "که", "را", "از", "در", "به", "با", "تا", "یا", "اگر", "چون", "اما", "ولی",
        "است", "بود", "شد", "هست", "نیست", "باشد", "شود", "می", "نمی", "هم", "نیز", "هر", "من", "تو", "او", "ما", "شما", "ایشان"))

    private fun isFaLetter(c: Char): Boolean = (c in '\u0621'..'\u063A') || (c in '\u0641'..'\u064A') || c == '\u067E' || c == '\u0686' ||
        c == '\u0698' || c == '\u06A9' || c == '\u06AF' || c == '\u06CC' || c == '\u06C0'

    /** نشانهٔ کسرهٔ اضافه بر پایهٔ پایان واژه (همان قرارداد متنی برنامه) یا null = دست نزن */
    private fun mark(w: String): String? {
        if (w.isEmpty() || !isFaLetter(w[w.length - 1])) return null          // پایان با نشانه/نیم‌فاصله/نقطه‌گذاری/عدد
        if (!isFaLetter(w[0]) && w.length < 2) return null
        val plain = norm(w).replace("[ZWNJ]", "")
        if (NEVER.contains(plain)) return null
        val last = w[w.length - 1]
        return when (last) {
            '\u06C0' -> null                                                  // «ۀ» از قبل کسرهٔ اضافه دارد
            '\u0647' -> {                                                     // «ه»
                val pv = if (w.length >= 2) w[w.length - 2] else ' '
                if (pv == '\u0627' || pv == '\u0648' || pv == '\u06CC' || pv == '\u0622') w + "\u0650"   // ماهِ، کوهِ (h ملفوظ)
                else w.substring(0, w.length - 1) + "\u06C0"                  // خانهٔ (h ناملفوظ)
            }
            '\u0627', '\u0648' -> w + "\u06CC\u0650"                          // دنیایِ، دانشجویِ
            else -> w + "\u0650"                                              // کتابِ، مرکزیِ
        }
    }

    /** متن جمله را با کسرهٔ اضافه‌های تشخیص‌داده‌شده برمی‌گرداند (خطا ← همان متن) */
    fun prepare(ctx: Context, text: String): String {
        if (!ensure(ctx)) return text
        return try {
            synchronized(lock) { apply(text) }
        } catch (e: Throwable) { text }
    }

    private fun apply(text: String): String {
        val ms = Regex("\\S+").findAll(text).toList()
        if (ms.size < 2) return text
        val words = ms.map { it.value }
        if (words.none { w -> w.any { isFaLetter(it) } }) return text
        val probs = classify(words)
        val sb = StringBuilder(text.length + 16)
        var at = 0
        for ((k, m) in ms.withIndex()) {
            sb.append(text, at, m.range.first)
            var w = m.value
            if (k < ms.size - 1 && probs[k] >= THRESH) {
                val nx = words[k + 1]
                val nextOk = nx.isNotEmpty() && (isFaLetter(nx[0]) || nx[0].isLetterOrDigit())   // پیش از نقطه‌گذاری کسره نمی‌آید
                if (nextOk) { val r = mark(w); if (r != null) w = r }
            }
            sb.append(w)
            at = m.range.last + 1
        }
        sb.append(text, at, text.length)
        return sb.toString()
    }
}
