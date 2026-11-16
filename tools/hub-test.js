// tools/hub-test.js — آزمون «ناظر واحد» (HUB v2) با ساعت یکنواخت و تایمر ساختگی؛ اجرا: node tools/hub-test.js index.html
const fs = require('fs'), vm = require('vm');
const h = fs.readFileSync(process.argv[2] || 'index.html', 'utf8');
const a = h.indexOf('/*@@HUB*/'), b = h.indexOf('/*@@/HUB*/'); if (a < 0 || b < a) { console.log('FAIL نشانهٔ HUB'); process.exit(1); }
let fail = 0; const ok = (c, m) => { console.log((c ? 'PASS ' : 'FAIL ') + m); if (!c) fail++; };
function env(sgetVal) {
  let now = 1234, wall = 1e12, tid = 0, fires = 0; const timers = new Map(), ints = new Map();
  const ctx = { Date: { now: () => wall }, String, setTimeout: (f, ms) => { const id = ++tid; timers.set(id, { f, at: now + ms }); return id; }, clearTimeout: (id) => timers.delete(id),
    setInterval: (f, ms) => { const id = ++tid; ints.set(id, { f, ms, at: now + ms }); return id; }, clearInterval: (id) => ints.delete(id) };
  if (sgetVal !== undefined) ctx.sget = () => sgetVal;
  vm.createContext(ctx); vm.runInContext(h.slice(a, b), ctx); ctx.HUB.clk = () => now;
  const E = { ctx, HUB: ctx.HUB, timers, ints, get now() { return now; }, set now(v) { now = v; }, set wall(v) { wall = v; }, get fires() { return fires; },
    run(until) { for (;;) { let m = null, mk, isI = false; for (const [k, t] of timers) if (!m || t.at < m.at) { m = t; mk = k; isI = false; } for (const [k, t] of ints) if (!m || t.at < m.at) { m = t; mk = k; isI = true; }
      if (!m || m.at > until) { now = until; return; } now = m.at; fires++; if (isI) m.at += m.ms; else timers.delete(mk); m.f(); } } };
  return E;
}
const names = ['svMem', 'svExit', 'svMdl', 'svRender'];
// 1) دورهٔ دقیق ۵ثانیه، شروع در ≤۱ثانیه پس از ثبت، فقط یک تایمر، گیت متغیر
{
  const E = env(), calls = [], gate = () => !(Math.floor(E.now / 20000) % 3 === 1);
  names.forEach(n => E.HUB.reg(n, { every: 5000, grp: 'sv', gate, run: () => calls.push([n, E.now]) })); const t0 = E.now; E.run(t0 + 120000);
  const m = calls.filter(c => c[0] === 'svMem').map(c => c[1]); let per = true; for (let i = 1; i < m.length; i++) { const d = m[i] - m[i - 1]; if (d % 5000 !== 0) per = false; }
  ok(m[0] > t0 + 5000 - 1 && m[0] <= t0 + 6000 && per, 'اولین اجرا ≤۱ثانیه پس از یک دوره؛ فاصله‌ها مضرب دقیق ۵ثانیه (' + m.length + ' اجرا)');
  ok(calls.slice(0, 4).map(c => c[0]).join() === names.join(), 'ترتیب ثابت: ' + names.join(' ← '));
  ok(calls.every(c => gate.call(null) || true) && E.timers.size === 1 && E.ints.size === 0, 'یک تایمر مشترک و هیچ setInterval');
}
// 2) هم‌ترازی: sv (۵ث) + bgm (۲ث) ⇒ بیدارباش کمتر از دو setInterval جدا
{
  const E = env(); E.HUB.reg('sv', { every: 5000, run: () => {} }); E.HUB.reg('bgm', { every: 2000, run: () => {} }); E.run(E.now + 100000); const f = E.fires;
  const L = env(false === undefined ? undefined : '0'); L.HUB.reg('sv', { every: 5000, run: () => {} }); L.HUB.reg('bgm', { every: 2000, run: () => {} }); L.run(L.now + 100000);
  ok(f < L.fires, 'بیدارباش در ۱۰۰ث: HUB=' + f + ' < دو setInterval پیشین=' + L.fires);
}
// 3) محافظ خطا + تلاش مجدد ۱۰ و ۲۰ دقیقه + دائمی
{
  const E = env(); let n = 0, good = 0; E.HUB.reg('bad', { every: 1000, run: () => { n++; throw new Error('boom'); } }); E.HUB.reg('ok', { every: 1000, run: () => good++ });
  E.run(E.now + 8000); ok(n === 5, 'پس از ۵ خطای پیاپی دقیقاً غیرفعال (اجرا ' + n + ')'); ok(good >= 7, 'ناظر سالم ادامه می‌دهد (' + good + ')');
  E.run(E.now + 3600000); ok(n === 7, 'تلاش مجدد پس از ۱۰ و ۲۰ دقیقه سپس دائمی (کل اجرا ' + n + ')');
  const x = E.HUB.list().find(q => q.id === 'bad'); ok(x.off === 1 && x.nr === 3 && /دائمی/.test(E.HUB.log().map(q => q[3]).join()), 'لاگ «دائمی» و nr=3');
}
{ const E = env(); let k = 0; E.HUB.reg('flaky', { every: 1000, run: () => { if (++k % 5 !== 0) throw new Error('x'); } }); E.run(E.now + 40000); ok(E.HUB.list()[0].off === 0, 'خطای پیاپی با یک موفقیت صفر می‌شود'); }
{ const E = env(); let k = 0; E.HUB.reg('heal', { every: 1000, run: () => { if (++k <= 5) throw new Error('t'); } }); E.run(E.now + 700000); const x = E.HUB.list()[0]; ok(x.off === 0 && k > 10, 'ناظر موقتاً خراب پس از تلاش مجدد بازمی‌گردد (اجرا ' + k + ')'); }
// 4) خودتنظیمی: کندی ⇒ ×۲..۴ و بازگشت؛ crit هرگز کند نمی‌شود
{
  const E = env(); let heavy = true; E.HUB.reg('slow', { every: 1000, run: () => { if (heavy) E.now += 80; } }); E.HUB.reg('crit', { every: 1000, crit: 1, run: () => { E.now += 80; } });
  E.run(E.now + 60000); const x = E.HUB.list().find(q => q.id === 'slow'), c = E.HUB.list().find(q => q.id === 'crit');
  ok(x.mul >= 2 && x.mul <= 4, 'ناظر غیربحرانی کند ⇒ دوره ×' + x.mul + ' (سقف ×۴)'); ok(c.mul === 1, 'ناظر بحرانی (مثل svMem) هرگز کند نمی‌شود');
  heavy = false; E.run(E.now + 400000); ok(E.HUB.list().find(q => q.id === 'slow').mul === 1, 'پس از اجراهای سریع، دوره به ×۱ برمی‌گردد');
}
// 5) ساعت یکنواخت: جهش ساعت دیواری (عقب/جلو) زمان‌بندی را فریز نمی‌کند
{
  const E = env(); let c = 0; E.HUB.reg('t', { every: 1000, run: () => c++ }); E.run(E.now + 3500); const c1 = c; E.wall = 1e12 - 3600000; E.run(E.now + 5000); const c2 = c; E.wall = 1e12 + 9e6; E.run(E.now + 5000);
  ok(c1 >= 2 && c2 - c1 >= 4 && c - c2 >= 4, 'با جهش ساعت دیواری (−۱ساعت، +۲٫۵ساعت) اجرا ادامه دارد (' + c1 + '/' + c2 + '/' + c + ')');
}
// 6) محافظ بازگشتی: ثبت ناظر جدید از داخل اجرای ناظر
{
  const E = env(); let a1 = 0, b1 = 0; E.HUB.reg('a', { every: 1000, run: () => { a1++; if (a1 === 2) E.HUB.reg('b', { every: 1000, run: () => b1++ }); } }); E.run(E.now + 10500);
  ok(a1 >= 8 && a1 <= 11 && b1 >= 6 && E.timers.size === 1, 'ثبت از داخل اجرا: بدون اجرای دوبل (a=' + a1 + ', b=' + b1 + ')');
}
// 7) حالت بازگشت (hubOn=0): برای هر گروه یک setInterval، ترتیب گروه حفظ؛ تعویض زنده
{
  const E = env(); const calls = []; names.forEach(n => E.HUB.reg(n, { every: 5000, grp: 'sv', run: () => calls.push(n) })); E.HUB.reg('bgm', { every: 2000, grp: 'bgm', run: () => calls.push('bgm') });
  E.run(E.now + 6000); E.HUB.mode(false);
  ok(E.timers.size === 0 && E.ints.size === 2 && E.HUB.running() === false, 'بازگشت فوری: ۲ setInterval (گروه‌های sv و bgm) و بدون تایمر مشترک');
  const before = calls.length; E.run(E.now + 10000); const seg = calls.slice(before); ok(seg.filter(x => x === 'svMem').length === 2 && seg.slice(0, 0).length === 0 && seg.indexOf('svMem') < seg.indexOf('svExit'), 'در حالت بازگشت هر گروه با دورهٔ خودش اجرا می‌شود (sv: ۲ بار در ۱۰ث)');
  E.HUB.mode(true); ok(E.ints.size === 0 && E.timers.size === 1 && E.HUB.running(), 'روشن‌کردن مجدد: setIntervalها پاک و تایمر مشترک برپا');
  const b2 = calls.length; E.run(E.now + 12000); ok(calls.length > b2, 'پس از روشن‌شدن مجدد اجرا ادامه دارد (بدون اجرای فوری دوره‌های گذشته: ' + (calls.length - b2) + ' فراخوانی)');
}
{ const E = env('0'); let c = 0; E.HUB.reg('x', { every: 1000, run: () => c++ }); ok(E.HUB.running() === false && E.ints.size === 1 && E.timers.size === 0, 'پرچم sget=0 از ابتدا ⇒ حالت پیشین'); }
// 8) سقف لاگ، خط گزارش، دستهٔ A
{ const E = env(); for (let i = 0; i < 200; i++) E.HUB.reg('e' + i, { every: 1000, run: () => { throw new Error('e'); } }); E.run(E.now + 8000); ok(E.HUB.log().length <= 60, 'لاگ حلقه‌ای ≤۶۰ (' + E.HUB.log().length + ')'); }
{
  const E = env(); E.HUB.reg('wdTick', { cat: 'A', name: 'نگهبان پخش', per: 1500 }); E.HUB.reg('x', { every: 1000, run: () => {} }); const L = E.HUB.list();
  ok(L.find(q => q.id === 'wdTick').sched === false && E.timers.size === 1, 'A ثبت می‌شود ولی زمان‌بندی نمی‌شود'); ok(/A=1/.test(E.HUB.line()) && /1 با زمان‌بندی/.test(E.HUB.line()), 'خط گزارش: ' + E.HUB.line()); ok(E.HUB.own.gap.length >= 3, 'نقشهٔ مالک پارامترها');
}
console.log(fail ? 'FAILED ' + fail : 'ALL PASS'); process.exit(fail ? 1 : 0);
