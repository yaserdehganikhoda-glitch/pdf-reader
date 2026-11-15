#!/usr/bin/env node
/* tools/sup-replay.js — آزمون بازپخش هستهٔ ناظر واحد (P15-F2/F4/F5) بدون مرورگر؛ ساعت و تایمر مجازی.
   اجرا:  node tools/sup-replay.js index.html
   پوشش: برابری زمان‌بندی با تایمرهای پیشین، تایمر مشترک (ب.م.م)، نگهبان خطا، خودتنظیمی هزینه، سقف لاگ/ردّ، ثبت/لغو، یکپارچگی با index.html. */
'use strict';
const fs = require('fs'), vm = require('vm');
const file = process.argv[2] || 'index.html', html = fs.readFileSync(file, 'utf8');
let fail = 0; const ok = (c, m) => { console.log((c ? 'PASS ' : 'FAIL ') + m); if (!c) fail++; };
const a = html.indexOf('/*@@SUP:A*/'), b = html.indexOf('/*@@SUP:/A*/');
if (a < 0 || b < a) { console.log('FAIL نشانه‌های SUP در ' + file); process.exit(1); }
const src = html.slice(a, b), supHub = vm.runInNewContext(src + ';supHub');
ok(!/\b(document|window|localStorage|navigator|performance)\b/.test(src.replace(/\/\*[\s\S]*?\*\//g, '')), 'هسته بدون وابستگی به DOM/window (فقط ساعت و تایمر تزریقی)');

function Sim() {
  const s = { t: 0, id: 0, iv: [], cleared: 0 };
  s.now = () => s.t;
  s.set = (f, p) => { const o = { id: ++s.id, f, p, next: s.t + p }; s.iv.push(o); return o.id; };
  s.clear = (h) => { const i = s.iv.findIndex(o => o.id === h); if (i >= 0) { s.iv.splice(i, 1); s.cleared++; } };
  s.run = (ms) => {
    const end = s.t + ms;
    for (;;) {
      let n = null; for (const o of s.iv) if (!n || o.next < n.next || (o.next === n.next && o.id < n.id)) n = o;
      if (!n || n.next > end) break;
      s.t = Math.max(s.t, n.next); n.next += n.p; n.f();
    }
    s.t = end;
  };
  return s;
}
const mk = (S, o) => supHub(Object.assign({ now: S.now, set: S.set, clear: S.clear, log: () => {} }, o || {}));

/* S1: یک وظیفهٔ ۵ثانیه‌ای — دقیقاً همان لحظه‌ها و همان تعداد تایمر */
{
  const A = Sim(), B = Sim(), la = [], lb = [];
  A.set(() => la.push(A.t), 5000);
  const h = mk(B); h.reg({ id: 'x', every: 5000, run: () => lb.push(B.t) });
  A.run(600000); B.run(600000);
  ok(JSON.stringify(la) === JSON.stringify(lb) && la.length === 120, 'S1 برابری لحظه‌های اجرا با تایمر پیشین (۱۲۰ اجرا در ۱۰ دقیقه)');
  ok(A.iv.length === 1 && B.iv.length === 1 && h.timers() === 1, 'S1 شمار تایمر: پیش ۱، پس ۱ (بدون افزایش)');
}
/* S2: چند وظیفه — یک تایمر به‌جای چند تایمر، با همان لحظه‌های اجرا */
{
  const A = Sim(), B = Sim(), la = [], lb = [], P = [['p', 5000], ['q', 1500], ['r', 4000]];
  P.forEach(([id, p]) => A.set(() => la.push([A.t, id]), p));
  const h = mk(B); P.forEach(([id, p]) => h.reg({ id, every: p, run: () => lb.push([B.t, id]) }));
  A.run(120000); B.run(120000);
  ok(JSON.stringify(la) === JSON.stringify(lb), 'S2 برابری ترتیب و لحظهٔ اجرای ۳ وظیفه با ۳ تایمر پیشین');
  ok(A.iv.length === 3 && B.iv.length === 1, 'S2 تایمر مشترک: ۳ ← ۱');
}
/* S3: نگهبان خطا */
{
  const S = Sim(), h = mk(S); let okRuns = 0, bad = 0;
  h.reg({ id: 'ok', every: 1000, run: () => okRuns++ }); h.reg({ id: 'bad', every: 1000, run: () => { bad++; throw new Error('x'); } });
  S.run(20000);
  const st = h.stat(), sb = st.find(x => x.id === 'bad'), so = st.find(x => x.id === 'ok');
  ok(sb.off && bad === 5 && !so.off && okRuns === 20, 'S3 پس از ۵ خطای پیاپی فقط همان وظیفه خاموش می‌شود؛ وظیفهٔ سالم ادامه دارد');
  ok(h.ring().some(r => r.id === 'bad' && r.ev === 'off'), 'S3 رویداد off در لاگ');
  const S2 = Sim(), h2 = mk(S2); h2.reg({ id: 'bad', every: 1000, run: () => { throw 1; } });
  S2.run(10000);
  ok(h2.timers() === 0 && S2.iv.length === 0 && S2.cleared === 1, 'S3 با خاموشیِ آخرین وظیفه تایمر آزاد می‌شود (بدون تایمر یتیم)');
  const S3 = Sim(), h3 = mk(S3); let c = 0;
  h3.reg({ id: 'flaky', every: 1000, run: () => { if (++c % 5 !== 0) throw 1; } });
  S3.run(100000);
  ok(!h3.stat()[0].off, 'S3 خطای متناوب (هر ۵ اجرا یک موفق) خاموش نمی‌کند: شمارندهٔ پیاپی بازنشانی می‌شود');
  const S4 = Sim(), h4 = mk(S4, { log: () => { throw new Error('log'); } }); h4.reg({ id: 'e', every: 1000, run: () => { throw 1; } });
  let thrown = false; try { S4.run(10000); } catch (e) { thrown = true; }
  ok(!thrown && h4.stat()[0].off, 'S3 خرابیِ خودِ لاگ، حلقهٔ هسته را نمی‌شکند');
}
/* S4: خودتنظیمی هزینه */
{
  const S = Sim(), h = mk(S); let cost = 100; const at = [];
  h.reg({ id: 't', every: 5000, budget: 80, run: () => { at.push(S.t); S.t += cost; } });
  S.run(100000);
  const slowMax = h.stat()[0].slow;
  ok(at.join() === '5000,10000,15000,25000,35000,45000,65000,85000', 'S4 ۳ اجرای گران ⇒ دوره ۲×؛ ۳ اجرای گرانِ دیگر ⇒ ۴× (لحظه‌های دقیق)');
  ok(slowMax === 4 && h.ring().filter(r => r.ev === 'slow').length === 2, 'S4 سقف ۴× (دو بار slow؛ هرگز ۸×)');
  cost = 10; S.run(1500000);
  ok(h.stat()[0].slow === 1 && h.ring().filter(r => r.ev === 'fast').length === 2, 'S4 پس از اجراهای ارزان به دورهٔ عادی برمی‌گردد');
  const gaps = []; for (let i = at.length - 6; i < at.length - 1; i++) gaps.push(at[i + 1] - at[i]);
  ok(gaps.every(g => g >= 5000 && g <= 5100), 'S4 پس از بازگشت فاصله‌ها ≈ ۵ثانیه');
}
/* S5: سقف لاگ و ردّ */
{
  const S = Sim(), h = mk(S, { trace: true }); let c = 0;
  h.reg({ id: 'f', every: 1000, run: () => { if (++c % 2) throw 1; } });
  S.run(5000000);
  ok(h.ring().length <= 40 && h.trace().length <= 200, 'S5 سقف ثابت: لاگ ≤۴۰ و ردّ ≤۲۰۰ (حافظه بی‌حد رشد نمی‌کند)');
  const tr = h.trace()[0]; ok(Object.keys(tr).sort().join() === 'id,k,ms,ok,t', 'S5 ردّ فقط شناسه/زمان/هزینه/وضعیت دارد (بدون متن)');
  const S2 = Sim(), h2 = mk(S2); h2.reg({ id: 'f', every: 1000, run: () => {} }); S2.run(10000);
  ok(h2.trace().length === 0, 'S5 ردّ پیش‌فرض خاموش');
}
/* S6: ثبت/لغو در حین کار */
{
  const S = Sim(), h = mk(S), A = [], B = [];
  h.reg({ id: 'a', every: 5000, run: () => A.push(S.t) }); S.run(12000);
  h.reg({ id: 'b', every: 2000, run: () => B.push(S.t) }); S.run(30000);
  ok(S.iv.length === 1 && h.timers() === 1, 'S6 با ثبت وظیفهٔ دوم هنوز یک تایمر');
  const gp = (r) => r.slice(1).map((x, i) => x - r[i]), ga = gp(A.slice(2)), gb = gp(B);
  ok(ga.length > 3 && gb.length > 10 && ga.every(g => g === 5000) && gb.every(g => g === 2000), 'S6 پس از بازتنظیم فاصله‌ها دقیقاً ۵ و ۲ ثانیه');
  h.unreg('b'); S.run(30000);
  ok(S.iv.length === 1 && S.iv[0].p === 5000, 'S6 پس از لغو، دورهٔ تایمر به ۵ثانیه برمی‌گردد');
  h.unreg('a'); ok(S.iv.length === 0 && h.timers() === 0, 'S6 پس از لغوِ همه هیچ تایمری نمی‌ماند');
}
/* S7: اعتبارسنجی ثبت */
{
  const S = Sim(), h = mk(S);
  ok(!h.reg(null) && !h.reg({ id: 'x', every: 0, run() {} }) && !h.reg({ id: 'x', every: 5000 }) && !h.reg({ every: 5000, run() {} }) && !h.reg({ id: 'x', every: 50, run() {} }), 'S7 ثبت نامعتبر رد می‌شود');
  ok(h.reg({ id: 'x', every: 5000, run() {} }) && !h.reg({ id: 'x', every: 1000, run() {} }), 'S7 شناسهٔ تکراری رد می‌شود (هر وظیفه یک مالک)');
  ok(S.iv.length === 1, 'S7 ثبت نامعتبر تایمر نمی‌سازد');
}
/* S8: یکپارچگی با index.html */
{
  const cnt = (s) => html.split(s).length - 1;
  ok(cnt('svMemTick(); svSes(); svMdlRun();') === 1, 'S8 بدنهٔ تیک ۵ثانیه‌ای فقط یک‌جا (svTick)');
  ok(/function svTick\(\)/.test(html) && /function svSched\(\)/.test(html), 'S8 svTick و svSched تعریف شده‌اند');
  ok(!/setInterval\(function\(\)\{\s*if \(document\.hidden && !playing && !ocrBusy\) return;/.test(html), 'S8 تایمر ناشناسِ پیشین حذف شده است');
  ok(html.indexOf('id="svHub"') > 0, 'S8 کنترل svHub در صفحهٔ «ناظر هوشمند»');
  const m = html.match(/var CFG_DEF = (\{[^}]*\})/); ok(m && JSON.parse(m[1]).svHub === true, 'S8 svHub در CFG_DEF');
  const k = html.match(/var CFG_KEY = (\/.*\/[a-z]*);/); ok(k && vm.runInNewContext(k[1]).test('svHub'), 'S8 svHub در CFG_KEY (پشتیبان‌گیری)');
  ok(/svSched\(\);\s*\}\)\(\);/.test(html), 'S8 راه‌اندازی با svSched()');
}
/* S9: اجرای واقعی کد svTick/svSched از index.html با بدل‌ها — روشن/خاموش، بازگشت فوری، خطای راه‌اندازی */
{
  const i1 = html.indexOf('    function svTick(){'), e = '    svSched();', i2 = html.indexOf(e + '\n  })();', i1);
  ok(i1 > 0 && i2 > i1, 'S9 بلوک svTick/svSched پیدا شد');
  const code = html.slice(i1, i2 + e.length);
  const boot = (store, hubImpl, hidden, ov) => {
    const S = Sim(), calls = { mem: 0, ses: 0, mdl: 0, render: 0 }, ctx = {
      tk: 0, window: {}, performance: { now: S.now }, setInterval: S.set, clearInterval: S.clear,
      document: { hidden: !!hidden }, playing: false, ocrBusy: false, curTab: 'voice',
      $: () => ({ classList: { contains: () => false } }), svRender: () => calls.render++,
      svMemTick: () => calls.mem++, svSes: () => calls.ses++, svMdlRun: () => calls.mdl++, svLog: () => {},
      sget: (k, d) => (k in store ? store[k] : d), svOn: (k) => (k in store ? store[k] : '1') !== '0', supHub: hubImpl || supHub
    };
    if (ov) Object.assign(ctx, ov(calls)); vm.createContext(ctx); vm.runInContext(code, ctx); return { S, calls, ctx };
  };
  let r = boot({});
  r.S.run(30000);
  ok(r.S.iv.length === 1 && r.calls.mem === 6 && r.calls.ses === 6 && r.calls.mdl === 6 && r.calls.render === 3, 'S9 پیش‌فرض (ناظر واحد روشن): یک تایمر، ۶ تیک در ۳۰ث، رندر هر ۲ تیک');
  const store = { svHub: '0' }; r = boot(store);
  r.S.run(30000);
  ok(r.S.iv.length === 1 && r.calls.mem === 6 && r.calls.render === 3, 'S9 خاموش: همان رفتار با تایمر پیشین (یک تایمر، همان شمارها)');
  const st2 = { svHub: '1' }; const r2 = boot(st2);
  r2.S.run(10000);
  st2.svHub = '0'; r2.ctx.svSched(); ok(r2.S.iv.length === 1 && r2.S.iv[0].p === 5000, 'S9 خاموش‌کردن حین کار: تایمر مشترک آزاد و تایمر پیشین جایگزین (بدون تایمر دوم)');
  st2.svHub = '1'; r2.ctx.svSched(); r2.ctx.svSched(); ok(r2.S.iv.length === 1, 'S9 روشن‌کردن و فراخوانی مکرر svSched تایمر تکراری نمی‌سازد');
  const before = r2.calls.mem; r2.S.run(20000); ok(r2.calls.mem - before === 4, 'S9 پس از رفت‌وبرگشت، تیک‌ها ادامه دارند (۴ تیک در ۲۰ث)');
  r = boot({}, () => { throw new Error('boom'); }); r.S.run(15000);
  ok(r.S.iv.length === 1 && r.calls.mem === 3, 'S9 خطای ساخت هسته ⇒ بازگشت خودکار به تایمر پیشین؛ ناظر از کار نمی‌افتد');
  r = boot({}, null, true); r.S.run(20000); ok(r.calls.mem === 0 && r.calls.ses === 0, 'S9 در پس‌زمینه و بی‌کار، svTick زود برمی‌گردد (مثل پیش)');
  r = boot({ dbgSup: '1' }); r.S.run(12000); ok(r.ctx.window.__sup && r.ctx.window.__sup.trace().length === 2, 'S9 dbgSup=1: ردّ تشخیصی ثبت و __sup ارائه می‌شود');
  r = boot({}); r.S.run(12000); ok(!r.ctx.window.__sup, 'S9 بدون dbgSup هیچ چیزی روی window گذاشته نمی‌شود');
  /* S10 (r247، K-02): svTick با ۵ خطای پیاپی نباید ناظر حافظه را بکشد ⇒ بازگشت به تایمر پیشین */
  r = boot({}, null, false, (c) => ({ svMemTick: () => { c.mem++; throw new Error('mem'); } }));
  r.S.run(40000);
  ok(r.S.iv.length === 1 && r.calls.mem === 8, 'S10 svTick: ۵ تیک خطادار در هسته + ۳ تیک پس از بازگشت به تایمر پیشین (یک تایمر، بدون دوبل)');
  r.ctx.svSched(); r.S.run(10000);
  ok(r.S.iv.length === 1, 'S10 svSched پس از بازگشت تایمر تکراری نمی‌سازد');
}
/* S10 (r247، K-02): خاموشیِ نگهبان به فراخواننده خبر می‌دهد؛ svTick پس از ۵ خطای پیاپی به تایمر پیشین برمی‌گردد (ناظر حافظه نمی‌میرد) */
{
  const S = Sim(), h = mk(S); let off = 0, ran = 0, who = null;
  h.reg({ id: 'g', every: 1000, run: () => { ran++; throw 1; }, onOff: (id) => { off++; who = id; } });
  S.run(20000);
  ok(off === 1 && who === 'g' && ran === 5 && h.stat()[0].off, 'S10 onOff دقیقاً یک‌بار و با شناسهٔ وظیفه؛ پس از آن دیگر اجرا نمی‌شود');
  const S2 = Sim(), h2 = mk(S2); let t = 0; h2.reg({ id: 'g2', every: 1000, run: () => { throw 1; }, onOff: () => { t++; throw new Error('cb'); } });
  let thrown = false; try { S2.run(10000); } catch (e) { thrown = true; }
  ok(!thrown && t === 1, 'S10 خرابیِ خودِ onOff حلقهٔ هسته را نمی‌شکند');
  const S3 = Sim(), h3 = mk(S3); let n3 = 0; h3.reg({ id: 'g3', every: 1000, run: () => { n3++; throw 1; } });
  S3.run(10000); ok(n3 === 5 && h3.stat()[0].off, 'S10 بدون onOff رفتار پیشین (۵ خطا ⇒ خاموش) بی‌تغییر');
}
/* F4: بازپخش ردّ دستگاه — node tools/sup-replay.js index.html trace.json  (trace.json = JSON.stringify(__sup.trace()) با dbgSup=1)؛ هزینه/وضعیت هر اجرا به هستهٔ مجازی داده می‌شود و ضریب کندی بازتولیدشده با ثبت‌شده مقایسه می‌شود */
if (process.argv[3]) {
  const tr = JSON.parse(fs.readFileSync(process.argv[3], 'utf8')).filter(x => x.id === 'svTick');
  const S = Sim(), h = mk(S, { trace: true }); let i = 0;
  h.reg({ id: 'svTick', every: 5000, budget: 80, run: () => { const e = tr[i++]; if (e) S.t += e.ms; if (e && !e.ok) throw new Error('replayed'); } });
  S.run(tr.length * 5000 * 4 + 10000);
  const rp = h.trace(), n = Math.min(tr.length, rp.length), bad = [];
  for (let j = 0; j < n; j++) if (rp[j].k !== tr[j].k) bad.push(j);
  ok(n > 0 && !bad.length, 'F4 بازپخش ردّ دستگاه: ' + n + ' اجرا، ضریب کندیِ بازتولیدشده با ثبت‌شده یکی است' + (bad.length ? ' (اختلاف در ' + bad.slice(0, 5).join(',') + ')' : ''));
}
console.log(fail ? 'FAILED ' + fail : 'ALL PASS'); process.exit(fail ? 1 : 0);
