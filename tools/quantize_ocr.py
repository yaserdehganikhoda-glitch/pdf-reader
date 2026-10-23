#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
quantize_ocr.py — INT8 (QDQ) برای مدل rec پدل‌اُسی‌آر (arabic_PP-OCRv5_mobile_rec.onnx)

  python tools/quantize_ocr.py --model rec.onnx --dict rec_dict.txt --out out/rec_int8.onnx \
         [--lex fa_lexicon.js] [--calib DIR_OF_LINE_CROPS] [--mode static|dynamic] [--act u8|s8]

کالیبراسیون (فقط static):
  1) --calib: پوشهٔ تصویر خط‌های واقعی (png/jpg؛ هر فایل = یک خط متن). بهترین حالت.
  2) در غیر این‌صورت خط‌های مصنوعی فارسی/لاتین از واژه‌های --lex با فونت‌های سیستم ساخته می‌شود.
پیش‌پردازش همان برنامه است: ارتفاع ۴۸، خاکستری تکرارشده در ۳ کانال، (v/127.5-1)، پدینگ صفر.

حافظه: کالیبراسیون histogram (percentile/entropy) در ORT خروجی میانیِ همهٔ نمونه‌ها را هم‌زمان در RAM نگه می‌دارد
(OOM ← «exit code 143» در CI). اینجا نمونه‌به‌نمونه و جریانی جمع می‌شود (patch_hist)؛ بدون نیاز به RAM زیاد.

اعتبارسنجی داخلی: متن greedy-CTC مدل fp32 و int8 روی نمونه‌های جداگانه مقایسه می‌شود
(agree-CER). اگر از --max-agree (پیش‌فرض ۲٪) بیشتر شد کد خروج ۳ برمی‌گردد (فایل نوشته می‌شود
ولی commit/استفاده نشود). گیت اصلی همچنان در برنامه است: «گیت INT8» (ΔCER ≤ +0.3، ΔWER ≤ +1).
"""
import argparse, contextlib, gc, glob, io, shutil, hashlib, json, os, random, re, sys, tempfile, time

H = 48
PLW = re.compile(r'[\u0621-\u063A\u0641-\u064A\u067E\u0686\u0698\u06A9\u06AF\u06CC]{2,}')


def die(msg, code=1):
    print('ERROR:', msg, file=sys.stderr)
    sys.exit(code)


def load_dict(path):
    """همان P.ch برنامه: ['', ...خط‌ها..., ' ']"""
    t = open(path, encoding='utf-8').read().split('\n')
    if t and t[-1] == '':
        t.pop()
    return [''] + [x.rstrip('\r') for x in t] + [' ']


def load_words(path):
    if path and os.path.isfile(path):
        w = sorted(set(PLW.findall(open(path, encoding='utf-8', errors='ignore').read())))
        if len(w) > 500:
            return w
    return ('این آن که را با از در به هم تا بر یک دو سه کتاب خانه دانش آموز مدرسه شهر کشور مردم زمان کار راه '
            'زندگی تاریخ فرهنگ ادبیات علم جامعه سازمان اداره قانون ماده بند صفحه فصل بخش نتیجه روش مطالعه').split()


# ------------------------------------------------------------------ پیش‌پردازش
def prep(img, W):
    import numpy as np
    from PIL import Image
    w, h = img.size
    nw = max(8, min(W, int(round(H * w / max(1, h)))))
    a = np.asarray(img.convert('L').resize((nw, H), Image.BILINEAR), dtype=np.float32) / 127.5 - 1.0
    t = np.zeros((3, H, W), np.float32)
    t[:, :, :nw] = a
    return t


# ------------------------------------------------------------------ داده‌های مصنوعی
def find_fonts(extra):
    pats = ['*Naskh*Arabic*Regular*.ttf', '*Vazir*Regular*.ttf', '*Vazir*.ttf', '*NotoSansArabic*.ttf', '*Noto*Arabic*.ttf',
            '*Amiri*Regular*.ttf', '*Kufi*Regular*.ttf', '*DejaVuSans.ttf', '*DejaVuSans-Bold.ttf', '*Tahoma*.ttf', '*Arial*.ttf']
    roots = list(extra or []) + ['/usr/share/fonts', '/usr/local/share/fonts', os.path.expanduser('~/.fonts')]
    out = []
    for r in roots:
        for p in pats:
            out += glob.glob(os.path.join(r, '**', p), recursive=True)
    seen, res = set(), []
    for f in out:
        if f not in seen and 'Italic' not in f and 'Oblique' not in f:
            seen.add(f)
            res.append(f)
    return res


def synth(n, words, fonts, seed):
    import numpy as np
    from PIL import Image, ImageDraw, ImageFilter, ImageFont, features
    rnd = random.Random(seed)
    nr = np.random.RandomState(seed)
    raqm = features.check('raqm')
    try:
        import arabic_reshaper
        from bidi.algorithm import get_display
    except Exception:
        arabic_reshaper = None
    if not raqm and not arabic_reshaper:
        print('WARN: نه raqm و نه arabic-reshaper در دسترس است؛ حروف فارسی جدا رسم می‌شود (کالیبراسیون ضعیف‌تر).', file=sys.stderr)
    lat = ['Page', 'Table', 'Figure', 'ISBN', 'PDF', 'OCR', 'USB', 'Intel', 'Windows', 'Chapter', 'vol', 'No']
    dig = '۰۱۲۳۴۵۶۷۸۹'
    out = []
    for _ in range(n):
        k = rnd.randint(2, 9)
        toks = [rnd.choice(words) for _ in range(k)]
        for i in range(len(toks)):
            r = rnd.random()
            if r < 0.07:
                toks[i] = ''.join(rnd.choice(dig) for _ in range(rnd.randint(1, 4)))
            elif r < 0.10:
                toks[i] = rnd.choice(lat)
            elif r < 0.14:
                toks[i] = toks[i] + rnd.choice(['،', '.', ':', '؛', ')', '»'])
        text = ' '.join(toks)
        fp = rnd.choice(fonts)
        size = rnd.randint(26, 46)
        try:
            font = ImageFont.truetype(fp, size, layout_engine=ImageFont.Layout.RAQM) if raqm else ImageFont.truetype(fp, size)
        except Exception:
            font = ImageFont.truetype(fp, size)
        kw = {}
        if raqm:
            kw = {'direction': 'rtl', 'language': 'fa'}
        elif arabic_reshaper:
            text = get_display(arabic_reshaper.reshape(text))
        d0 = ImageDraw.Draw(Image.new('L', (8, 8)))
        try:
            bb = d0.textbbox((0, 0), text, font=font, **kw)
        except Exception:
            kw = {}
            bb = d0.textbbox((0, 0), text, font=font)
        pad = rnd.randint(4, 12)
        w, h = bb[2] - bb[0] + 2 * pad, bb[3] - bb[1] + 2 * pad
        if w < 16 or h < 8:
            continue
        bg, fg = rnd.randint(190, 255), rnd.randint(0, 100)
        im = Image.new('L', (w, h), bg)
        ImageDraw.Draw(im).text((pad - bb[0], pad - bb[1]), text, font=font, fill=fg, **kw)
        if rnd.random() < 0.5:
            im = im.rotate(rnd.uniform(-1.5, 1.5), resample=Image.BILINEAR, expand=True, fillcolor=bg)
        r = rnd.choice([0, 0, 0.4, 0.8, 1.2])
        if r:
            im = im.filter(ImageFilter.GaussianBlur(r))
        a = np.asarray(im, np.float32)
        a = bg + (a - bg) * rnd.uniform(0.55, 1.0)
        a = a + nr.normal(0, rnd.uniform(0, 6), a.shape)
        a = np.clip(a, 0, 255)
        if rnd.random() < 0.08:
            a = 255 - a
        out.append((Image.fromarray(a.astype('uint8')), text))
    return out


def load_crops(folder, n):
    from PIL import Image
    fs = sorted(sum([glob.glob(os.path.join(folder, '*.' + e)) for e in ('png', 'jpg', 'jpeg', 'webp')], []))
    random.Random(1).shuffle(fs)
    return [(Image.open(f).convert('L'), '') for f in fs[:n]]


# ------------------------------------------------------------------ CTC
def decode(y, chars):
    idx = y.argmax(-1)
    s, prev = [], 0
    for i in idx:
        i = int(i)
        if i and i != prev:
            s.append(chars[i] if i < len(chars) else '')
        prev = i
    return ''.join(s)


def edit(a, b):
    m, n = len(a), len(b)
    if not m:
        return n
    p = list(range(n + 1))
    for i in range(1, m + 1):
        q = [i] + [0] * n
        for j in range(1, n + 1):
            q[j] = min(p[j] + 1, q[j - 1] + 1, p[j - 1] + (a[i - 1] != b[j - 1]))
        p = q
    return p[n]


# ------------------------------------------------------------------ کالیبراسیون کم‌مصرف (جریانی)
def patch_hist():
    """HistogramCalibrater.collect_data را با نسخهٔ جریانی عوض می‌کند: هر نمونه بلافاصله در هیستوگرام ادغام و
    آزاد می‌شود (حافظه ≈ یک نمونه، و عرض‌های متفاوت ragged-خطا نمی‌دهند). ناموفق ← False."""
    try:
        from onnxruntime.quantization import calibrate as C
        import numpy as np
        HC, HCol = C.HistogramCalibrater, C.HistogramCollector
        orig_merge = HCol.merge_histogram

        def merge(self, old, data, nmin, nmax, nthr):
            # رشد بیش‌ازحد محدوده (نمونهٔ اول تقریباً صفر، بعدی بزرگ) ← میلیاردها bin ← OOM؛ اینجا بازبینی با تعداد bin ثابت
            oh, oe, omin, omax, othr = old
            if othr > 0 and nthr > othr * 4096:
                nb = len(oh)
                h, e = np.histogram(data, nb, range=(-nthr, nthr))
                ctr = (oe[:-1] + oe[1:]) / 2
                h = h + np.rint(np.histogram(ctr, nb, range=(-nthr, nthr), weights=oh)[0]).astype(h.dtype)
                return (h, e, min(omin, nmin), max(omax, nmax), nthr)
            return orig_merge(self, old, data, nmin, nmax, nthr)

        HCol.merge_histogram = merge

        def collect_data(self, data_reader):
            ins = {x.name for x in self.infer_session.get_inputs()}
            outs = [x.name for x in self.infer_session.get_outputs()]
            want = set(self.tensors_to_calibrate)
            if not self.collector:
                self.collector = HCol(method=self.method, symmetric=self.symmetric, num_bins=self.num_bins,
                                      num_quantized_bins=self.num_quantized_bins, percentile=self.percentile,
                                      scenario=self.scenario)
            n = 0
            while True:
                feed = data_reader.get_next()
                if not feed:
                    break
                res = self.infer_session.run(None, feed)
                d = {k: (v.copy() if k in ins else v) for k, v in zip(outs, res) if k in want}
                with contextlib.redirect_stdout(io.StringIO()):
                    self.collector.collect(d)
                del res, d, feed
                n += 1
                if n % 16 == 0:
                    gc.collect()
                    print('  کالیبراسیون: %d نمونه' % n, flush=True)
            if n == 0:
                raise ValueError('No data is collected.')
            self.intermediate_outputs = []

        HC.collect_data = collect_data
        return True
    except Exception as e:
        print('WARN: patch جریانی ناموفق (%s) ← MinMax' % str(e)[:120], file=sys.stderr)
        return False


# ------------------------------------------------------------------ کوانتیزه
def ensure_names(src, dst):
    """نام‌گذاری گره‌ها + تبدیل Constantهای tensor به initializer (تا وزن‌ها واقعاً کوانتیزه شوند)."""
    import onnx
    m = onnx.load(src)
    have = {i.name for i in m.graph.initializer}
    keep, nconst = [], 0
    for n in m.graph.node:
        if n.op_type == 'Constant' and len(n.output) == 1 and n.output[0] not in have:
            at = [x for x in n.attribute if x.name == 'value' and x.HasField('t')]
            if at:
                t = onnx.TensorProto()
                t.CopyFrom(at[0].t)
                t.name = n.output[0]
                m.graph.initializer.append(t)
                have.add(t.name)
                nconst += 1
                continue
        keep.append(n)
    if nconst:
        del m.graph.node[:]
        m.graph.node.extend(keep)
        print('Constant→initializer: %d' % nconst)
    for i, n in enumerate(m.graph.node):
        if not n.name:
            n.name = '%s_%d' % (n.op_type, i)
    onnx.save(m, dst)
    return m


def pick_excludes(m, keep_first, quant_attn):
    ex = []
    nodes = list(m.graph.node)
    inits = {i.name for i in m.graph.initializer}
    core = [n for n in nodes if n.op_type in ('MatMul', 'Gemm', 'Conv')]
    if core:
        ex.append(core[-1].name)          # لایهٔ خروجی (سر CTC)
    if keep_first:
        for n in nodes:
            if n.op_type == 'Conv':
                ex.append(n.name)         # Conv اولِ سه‌کاناله
                break
    if not quant_attn:                    # MatMulهای attention (هر دو ورودی فعال‌سازی) ← fp32؛ بیشترین منبع افت دقت
        for n in nodes:
            if n.op_type == 'MatMul' and not any(x in inits for x in n.input):
                ex.append(n.name)
    return list(dict.fromkeys(ex))


AUTO = [('static', 'u8', 'percentile'), ('static', 's8', 'percentile'), ('static', 'u8', 'entropy'),
        ('static', 'u8', 'minmax'), ('dynamic', 'u8', 'percentile')]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--model', required=True)
    ap.add_argument('--dict', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--lex', default='')
    ap.add_argument('--calib', default='')
    ap.add_argument('--fonts', action='append', default=[])
    ap.add_argument('--mode', choices=['static', 'dynamic'], default='static')
    ap.add_argument('--act', choices=['u8', 's8'], default='u8', help='نوع فعال‌سازی QDQ (وزن‌ها همیشه s8 و per-channel)')
    ap.add_argument('--calib-method', choices=['percentile', 'entropy', 'minmax'], default='percentile')
    ap.add_argument('--auto', action='store_true', help='چند پیکربندی را پشت‌سرهم امتحان و بهترین را نگه می‌دارد')
    ap.add_argument('--quant-attn', action='store_true', help='MatMulهای attention (بدون وزن) هم کوانتیزه شوند (پیش‌فرض: fp32)')
    ap.add_argument('--n', type=int, default=240)
    ap.add_argument('--nval', type=int, default=120)
    ap.add_argument('--max-agree', type=float, default=2.0)
    ap.add_argument('--quantize-first-conv', action='store_true')
    ap.add_argument('--report', default='')
    a = ap.parse_args()
    try:
        sys.stdout.reconfigure(line_buffering=True)
    except Exception:
        pass

    for mod in ('numpy', 'PIL', 'onnx', 'onnxruntime'):
        try:
            __import__(mod)
        except Exception as e:
            die('ماژول %s نصب نیست (pip install onnx onnxruntime numpy pillow sympy): %s' % (mod, e))
    import numpy as np
    import onnx
    import onnxruntime as ort
    ort.set_default_logger_severity(3)
    from onnxruntime.quantization import (CalibrationDataReader, CalibrationMethod, QuantFormat, QuantType,
                                          quantize_dynamic, quantize_static)

    chars = load_dict(a.dict)
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    tmp = tempfile.mkdtemp(prefix='q8_')
    src = os.path.join(tmp, 'pre.onnx')

    # --- پیش‌پردازش ORT (shape-inference + fusion مثل BN→Conv)؛ نیازمند sympy
    base = a.model
    bp = os.path.join(tmp, 'base.onnx')
    try:
        from onnxruntime.quantization.shape_inference import quant_pre_process
        for kw in ({}, {'skip_symbolic_shape': True}):
            try:
                quant_pre_process(a.model, bp, skip_optimization=False, **kw)
                base = bp
                break
            except Exception as e:
                print('WARN: quant_pre_process%s ناموفق (%s)' % (' (بدون symbolic)' if kw else '', str(e)[:120]))
    except Exception as e:
        print('WARN: quant_pre_process در دسترس نیست (%s)' % str(e)[:120])
    if base == a.model:
        print('WARN: مدل اصلی بدون پیش‌پردازش استفاده می‌شود (pip install sympy).')
    m = ensure_names(base, src)
    ini = {x.name for x in m.graph.initializer}
    inp = [i for i in m.graph.input if i.name not in ini][0].name
    print('input=%s | initializers=%d | exclude(پایه)=%s' % (inp, len(ini), pick_excludes(m, not a.quantize_first_conv, True)))

    # --- نمونه‌ها
    words = load_words(a.lex)
    if a.calib:
        cal = load_crops(a.calib, a.n)
        print('کالیبراسیون واقعی: %d تصویر' % len(cal))
    else:
        fonts = find_fonts(a.fonts)
        if not fonts:
            die('فونتی پیدا نشد؛ --fonts DIR یا --calib DIR بدهید (در CI: fonts-noto-core).')
        cal = synth(a.n, words, fonts, 11)
        print('کالیبراسیون مصنوعی: %d خط | فونت‌ها: %d | واژه‌ها: %d' % (len(cal), len(fonts), len(words)))
        if len(words) < 500:
            print('WARN: فهرست واژه کوچک است (fa_lexicon.js پیدا نشد)؛ کالیبراسیون ضعیف‌تر.')
    val = synth(a.nval, words, find_fonts(a.fonts) or [], 777) if not a.calib else cal[-min(len(cal), a.nval):]
    if not cal or not val:
        die('نمونهٔ کالیبراسیون/اعتبارسنجی ساخته نشد.')
    Ws = [192, 320, 512]

    class Reader(CalibrationDataReader):
        """تنبل: هر نمونه هنگام نیاز پیش‌پردازش و بعد آزاد می‌شود."""
        def __init__(self):
            rnd = random.Random(5)
            self.it = ((im, rnd.choice(Ws)) for im, _ in cal)

        def get_next(self):
            r = next(self.it, None)
            return None if r is None else {inp: prep(r[0], r[1])[None]}

    # --- مرجع fp32 (یک‌بار)
    so = ort.SessionOptions()
    so.log_severity_level = 3
    sf = ort.InferenceSession(a.model, so, providers=['CPUExecutionProvider'])
    nm = sf.get_inputs()[0].name
    rv = random.Random(9)
    vx, tf = [], 0.0
    for im, _ in val:
        x = prep(im, rv.choice(Ws))[None]
        t1 = time.time(); yf = sf.run(None, {nm: x})[0][0]; tf += time.time() - t1
        vx.append((x, decode(yf, chars)))
    del sf
    sz0 = os.path.getsize(a.model)

    def run(k, mode, act, cm):
        outp = os.path.join(tmp, 't%d.onnx' % k)
        ex = pick_excludes(m, not a.quantize_first_conv, a.quant_attn)
        t0 = time.time()
        if mode == 'dynamic':
            quantize_dynamic(src, outp, weight_type=QuantType.QInt8, op_types_to_quantize=['MatMul', 'Gemm'],
                             nodes_to_exclude=ex)
        else:
            at = QuantType.QUInt8 if act == 'u8' else QuantType.QInt8
            if cm != 'minmax' and not patch_hist():
                cm = 'minmax'
            cmm = {'percentile': CalibrationMethod.Percentile, 'entropy': CalibrationMethod.Entropy,
                   'minmax': CalibrationMethod.MinMax}[cm]
            xo = {'ActivationSymmetric': False, 'WeightSymmetric': True}
            if cm == 'minmax':
                xo['CalibMaxIntermediateOutputs'] = 8     # سقف RAM: هر ۸ نمونه min/max ادغام و آزاد می‌شود
            kw = dict(quant_format=QuantFormat.QDQ, activation_type=at, weight_type=QuantType.QInt8,
                      op_types_to_quantize=['Conv', 'MatMul', 'Gemm'], nodes_to_exclude=ex,
                      calibrate_method=cmm, reduce_range=False, extra_options=xo)
            print('کالیبراسیون: %s | %d نمونه' % (cm, len(cal)), flush=True)
            try:
                quantize_static(src, outp, Reader(), per_channel=True, **kw)
            except MemoryError:
                raise
            except Exception as e:
                print('WARN: per-channel ناموفق (%s) ← per-tensor' % str(e)[:140])
                gc.collect()
                quantize_static(src, outp, Reader(), per_channel=False, **kw)
        dt = time.time() - t0
        mq = onnx.load(outp)
        n8 = sum(1 for i in mq.graph.initializer if i.data_type in (2, 3))
        nqdq = sum(1 for n in mq.graph.node if n.op_type in ('QuantizeLinear', 'DequantizeLinear', 'DynamicQuantizeLinear'))
        del mq
        sq = ort.InferenceSession(outp, so, providers=['CPUExecutionProvider'])
        nq = sq.get_inputs()[0].name
        tot = ed = 0
        tq = 0.0
        for x, ref in vx:
            t1 = time.time(); yq = sq.run(None, {nq: x})[0][0]; tq += time.time() - t1
            tot += max(1, len(ref))
            ed += edit(ref, decode(yq, chars))
        del sq
        gc.collect()
        sz1 = os.path.getsize(outp)
        r = {'mode': mode, 'act': act, 'calib': cm if mode == 'static' else '-', 'attn_fp32': not a.quant_attn,
             'agree_cer_pct': round(100.0 * ed / max(1, tot), 3), 'n_val': len(vx), 'size_fp32': sz0, 'size_int8': sz1,
             'ratio': round(sz1 / sz0, 3), 'int8_initializers': n8, 'qdq_nodes': nqdq, 'quant_s': round(dt, 1),
             'cpu_x86_ms_fp32': round(1000 * tf / len(vx), 1), 'cpu_x86_ms_int8': round(1000 * tq / len(vx), 1),
             'exclude': ex, 'path': outp}
        print('→ %s/%s/%s: agree-CER %.2f%% | حجم ×%.2f | وزن int8: %d | Q/DQ: %d | %.0fs'
              % (mode, act, r['calib'], r['agree_cer_pct'], r['ratio'], n8, nqdq, dt), flush=True)
        if mode == 'static' and r['ratio'] > 0.7:
            print('WARN: حجم تقریباً کم نشد ← وزن‌ها کوانتیزه نشده‌اند (sympy/pre-process یا Constant؟).')
        return r

    cfgs = AUTO if a.auto else [(a.mode, a.act, a.calib_method)]
    trials = []
    for k, (mo, ac, cm) in enumerate(cfgs):
        try:
            trials.append(run(k, mo, ac, cm))
        except MemoryError:
            raise
        except Exception as e:
            print('WARN: پیکربندی %s/%s/%s شکست خورد: %s' % (mo, ac, cm, str(e)[:160]))
            continue
        if trials[-1]['agree_cer_pct'] <= a.max_agree:
            break
    if not trials:
        die('هیچ پیکربندی‌ای اجرا نشد.')
    best = min(trials, key=lambda r: r['agree_cer_pct'])
    shutil.copyfile(best.pop('path'), a.out)
    for r in trials:
        r.pop('path', None)
    sha = hashlib.sha256(open(a.out, 'rb').read()).hexdigest()
    open(a.out + '.sha256', 'w').write(sha + '  ' + os.path.basename(a.out) + '\n')
    rep = dict(best, sha256=sha, trials=[{k: r[k] for k in ('mode', 'act', 'calib', 'agree_cer_pct', 'ratio')} for r in trials])
    print(json.dumps(rep, ensure_ascii=False, indent=1))
    if a.report:
        open(a.report, 'w').write(json.dumps(rep, ensure_ascii=False, indent=1))
    print('توجه: زمان‌های x86 معیار سرعت گوشی نیستند؛ سرعت واقعی را «گیت INT8» روی دستگاه می‌سنجد.')
    if best['agree_cer_pct'] > a.max_agree:
        die('agree-CER %.2f%% > %.2f%% — این مدل را استفاده نکنید (calib واقعی بدهید: --calib DIR، یا --auto).'
            % (best['agree_cer_pct'], a.max_agree), 3)


if __name__ == '__main__':
    main()
