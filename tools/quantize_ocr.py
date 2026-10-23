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

اعتبارسنجی داخلی: متن greedy-CTC مدل fp32 و int8 روی نمونه‌های جداگانه مقایسه می‌شود
(agree-CER). اگر از --max-agree (پیش‌فرض ۲٪) بیشتر شد کد خروج ۳ برمی‌گردد (فایل نوشته می‌شود
ولی commit/استفاده نشود). گیت اصلی همچنان در برنامه است: «گیت INT8» (ΔCER ≤ +0.3، ΔWER ≤ +1).
"""
import argparse, glob, hashlib, json, os, random, re, sys, tempfile, time

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


# ------------------------------------------------------------------ کوانتیزه
def ensure_names(src, dst):
    import onnx
    m = onnx.load(src)
    for i, n in enumerate(m.graph.node):
        if not n.name:
            n.name = '%s_%d' % (n.op_type, i)
    onnx.save(m, dst)
    return m


def pick_excludes(m, keep_first):
    ex = []
    nodes = list(m.graph.node)
    core = [n for n in nodes if n.op_type in ('MatMul', 'Gemm', 'Conv')]
    if core:
        ex.append(core[-1].name)          # لایهٔ خروجی (سر CTC)
    if keep_first:
        for n in nodes:
            if n.op_type == 'Conv':
                ex.append(n.name)         # Conv اولِ سه‌کاناله
                break
    return ex


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
    ap.add_argument('--n', type=int, default=240)
    ap.add_argument('--nval', type=int, default=120)
    ap.add_argument('--max-agree', type=float, default=2.0)
    ap.add_argument('--quantize-first-conv', action='store_true')
    ap.add_argument('--report', default='')
    a = ap.parse_args()

    for mod in ('numpy', 'PIL', 'onnx', 'onnxruntime'):
        try:
            __import__(mod)
        except Exception as e:
            die('ماژول %s نصب نیست (pip install onnx onnxruntime numpy pillow): %s' % (mod, e))
    import numpy as np
    import onnxruntime as ort
    from onnxruntime.quantization import (CalibrationDataReader, CalibrationMethod, QuantFormat, QuantType,
                                          quantize_dynamic, quantize_static)

    chars = load_dict(a.dict)
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    tmp = tempfile.mkdtemp(prefix='q8_')
    src = os.path.join(tmp, 'pre.onnx')

    # --- پیش‌پردازش ORT (shape-inference + fusion مثل BN→Conv)
    base = os.path.join(tmp, 'base.onnx')
    try:
        from onnxruntime.quantization.shape_inference import quant_pre_process
        quant_pre_process(a.model, base, skip_optimization=False)
    except Exception as e:
        print('WARN: quant_pre_process ناموفق (%s)؛ مدل اصلی استفاده می‌شود.' % str(e)[:120])
        base = a.model
    m = ensure_names(base, src)
    inp = [i for i in m.graph.input if i.name not in {x.name for x in m.graph.initializer}][0].name
    excl = pick_excludes(m, not a.quantize_first_conv)
    print('input=%s | exclude=%s' % (inp, excl))

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
    val = synth(a.nval, words, find_fonts(a.fonts) or [], 777) if not a.calib else cal[-min(len(cal), a.nval):]
    if not cal or not val:
        die('نمونهٔ کالیبراسیون/اعتبارسنجی ساخته نشد.')
    rnd = random.Random(5)
    Ws = [192, 320, 512]

    class Reader(CalibrationDataReader):
        def __init__(self):
            self.it = iter([{inp: prep(im, rnd.choice(Ws))[None]} for im, _ in cal])

        def get_next(self):
            return next(self.it, None)

    t0 = time.time()
    if a.mode == 'dynamic':
        quantize_dynamic(src, a.out, weight_type=QuantType.QInt8, op_types_to_quantize=['MatMul', 'Gemm'],
                         nodes_to_exclude=excl)
    else:
        at = QuantType.QUInt8 if a.act == 'u8' else QuantType.QInt8
        kw = dict(quant_format=QuantFormat.QDQ, activation_type=at, weight_type=QuantType.QInt8,
                  op_types_to_quantize=['Conv', 'MatMul', 'Gemm'], nodes_to_exclude=excl,
                  calibrate_method=CalibrationMethod.Percentile, reduce_range=False,
                  extra_options={'ActivationSymmetric': False, 'WeightSymmetric': True})
        try:
            quantize_static(src, a.out, Reader(), per_channel=True, **kw)
        except Exception as e:
            print('WARN: per-channel ناموفق (%s) ← per-tensor' % str(e)[:140])
            rnd = random.Random(5)
            quantize_static(src, a.out, Reader(), per_channel=False, **kw)
    print('کوانتیزه در %.0fs' % (time.time() - t0))

    # --- اعتبارسنجی fp32 در برابر int8
    so = ort.SessionOptions()
    so.log_severity_level = 3
    sf = ort.InferenceSession(a.model, so, providers=['CPUExecutionProvider'])
    sq = ort.InferenceSession(a.out, so, providers=['CPUExecutionProvider'])
    nm = sf.get_inputs()[0].name
    nq = sq.get_inputs()[0].name
    rv = random.Random(9)
    tot = ed = 0
    tf = tq = 0.0
    for im, _ in val:
        x = prep(im, rv.choice(Ws))[None]
        t1 = time.time(); yf = sf.run(None, {nm: x})[0][0]; tf += time.time() - t1
        t1 = time.time(); yq = sq.run(None, {nq: x})[0][0]; tq += time.time() - t1
        sa, sb = decode(yf, chars), decode(yq, chars)
        tot += max(1, len(sa))
        ed += edit(sa, sb)
    agree = 100.0 * ed / max(1, tot)
    sz0, sz1 = os.path.getsize(a.model), os.path.getsize(a.out)
    sha = hashlib.sha256(open(a.out, 'rb').read()).hexdigest()
    open(a.out + '.sha256', 'w').write(sha + '  ' + os.path.basename(a.out) + '\n')
    rep = {'mode': a.mode, 'act': a.act, 'agree_cer_pct': round(agree, 3), 'n_val': len(val), 'size_fp32': sz0,
           'size_int8': sz1, 'ratio': round(sz1 / sz0, 3), 'cpu_x86_ms_fp32': round(1000 * tf / len(val), 1),
           'cpu_x86_ms_int8': round(1000 * tq / len(val), 1), 'sha256': sha, 'exclude': excl}
    print(json.dumps(rep, ensure_ascii=False, indent=1))
    if a.report:
        open(a.report, 'w').write(json.dumps(rep, ensure_ascii=False, indent=1))
    print('توجه: زمان‌های x86 معیار سرعت گوشی نیستند؛ سرعت واقعی را «گیت INT8» روی دستگاه می‌سنجد.')
    if agree > a.max_agree:
        die('agree-CER %.2f%% > %.2f%% — این مدل را استفاده نکنید (calib واقعی بدهید یا --act s8 / --mode dynamic را امتحان کنید).' % (agree, a.max_agree), 3)


if __name__ == '__main__':
    main()
