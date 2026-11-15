// tools/hub-test.js — آزمون «ناظر واحد» (HUB) با ساعت و تایمر ساختگی؛ اجرا: node tools/hub-test.js index.html
const fs = require('fs'), vm = require('vm');
const h = fs.readFileSync(process.argv[2] || 'index.html', 'utf8');
const a = h.indexOf('/*@@HUB*/'), b = h.indexOf('/*@@/HUB*/'); if (a < 0 || b < a) { console.log('FAIL نشانهٔ HUB'); process.exit(1); }
let fail = 0; const ok = (c, m) => { console.log((c ? 'PASS ' : 'FAIL ') + m); if (!c) fail++; };
function env() {
  let now = 1000, tid = 0; const timers = new Map();
  const ctx = { Date: { now: () => now }, setTimeout: (f, ms) => { const id = ++tid; timers.set(id, { f, at: now + ms }); return id; }, clearTimeout: (id) => timers.delete(id), String };
  vm.createContext(ctx); vm.runInContext(h.slice(a, b), ctx); ctx.HUB.clk = () => now;
  return { ctx, HUB: ctx.HUB, timers, get now() { return now; }, set now(v) { now = v; },
    run(until) { for (;;) { let m = null, mk; for (const [k, t] of timers) if (!m || t.at < m.at) { m = t; mk = k; } if (!m || m.at > until) { now = until; return; } timers.delete(mk); now = m.at; m.f(); } } };
}
// 1) هم‌ارزی با setInterval(5000) پیشین زیر گیت متغیر
{
  const E = env(), calls = [], gate = () => !(Math.floor(E.now / 20000) % 3 === 1);   // هر ۶۰ث ۲۰ث بسته
  ['svMem', 'svExit', 'svMdl', 'svRender'].forEach(n => E.HUB.reg(n, { every: 5000, gate, run: () => calls.push(n + '@' + E.now) }));
  E.HUB.start(); const t0 = E.now; E.run(t0 + 120000);
  const legacy = []; for (let t = t0 + 5000; t <= t0 + 120000; t += 5000) { const g = !(Math.floor(t / 20000) % 3 === 1); if (g) ['svMem', 'svExit', 'svMdl', 'svRender'].forEach(n => legacy.push(n + '@' + t)); }
  ok(JSON.stringify(calls) === JSON.stringify(legacy), 'هم‌ارزی با setInterval(5000): ترتیب و زمان اجرا یکسان (' + calls.length + ' فراخوانی در ۱۲۰ث)');
  ok(E.timers.size === 1, 'همیشه فقط یک تایمر فعال (' + E.timers.size + ')');
}
// 2) محافظ خطا
{
  const E = env(); let n = 0, good = 0;
  E.HUB.reg('bad', { every: 1000, run: () => { n++; throw new Error('boom'); } }); E.HUB.reg('ok', { every: 1000, run: () => good++ });
  E.HUB.start(); E.run(E.now + 20000);
  ok(n === 5, 'پس از ۵ خطای پیاپی دقیقاً غیرفعال (اجرا ' + n + ')'); ok(good >= 19, 'ناظر سالم دیگر ادامه می‌دهد (' + good + ')');
  ok(E.HUB.list().find(x => x.id === 'bad').off === 1 && /غیرفعال شد/.test(E.HUB.log().map(x => x[3]).join()), 'لاگ ثبت و در فهرست off=1');
}
{ const E = env(); let k = 0; E.HUB.reg('flaky', { every: 1000, run: () => { if (++k % 5 !== 0) throw new Error('x'); } }); E.HUB.start(); E.run(E.now + 30000); ok(E.HUB.list()[0].off === 0, 'شمارندهٔ خطای پیاپی با یک موفقیت صفر می‌شود'); }
// 3) خودتنظیمی هزینه
{
  const E = env(); E.HUB.reg('heavy', { every: 1000, run: () => { E.now += 80; } }); E.HUB.start(); E.run(E.now + 60000);
  const x = E.HUB.list()[0]; ok(x.mul >= 2 && x.mul <= 4, 'اجرای کند ⇒ دوره ×' + x.mul + ' (سقف ×۴)');
}
// 4) سقف لاگ
{ const E = env(); for (let i = 0; i < 200; i++) E.HUB.reg('e' + i, { every: 1000, run: () => { throw new Error('e'); } }); E.HUB.start(); E.run(E.now + 8000); ok(E.HUB.log().length <= 60, 'لاگ حلقه‌ای ≤۶۰ (' + E.HUB.log().length + ')'); }
// 5) start/stop و ثبت‌نام هنگام اجرا
{
  const E = env(); let c = 0; E.HUB.reg('t', { every: 1000, run: () => c++ }); E.HUB.start(); E.run(E.now + 3500); const c1 = c;
  E.HUB.stop(); E.run(E.now + 5000); ok(c === c1 && E.timers.size === 0, 'stop ⇒ اجرا و تایمر متوقف');
  E.HUB.start(); let d = 0; E.HUB.reg('late', { every: 1000, run: () => d++ }); E.run(E.now + 3500); ok(c > c1 && d >= 3, 'start مجدد و ثبت‌نام هنگام اجرا کار می‌کند');
}
// 6) دستهٔ A فقط فهرست می‌شود (بدون زمان‌بندی) و خط گزارش
{
  const E = env(); E.HUB.reg('wdTick', { cat: 'A', name: 'نگهبان پخش', per: 1500 }); E.HUB.reg('x', { every: 1000, run: () => {} }); E.HUB.start();
  const L = E.HUB.list(); ok(L.find(x => x.id === 'wdTick').sched === false && E.timers.size === 1, 'A ثبت می‌شود ولی زمان‌بندی نمی‌شود'); ok(/A=1/.test(E.HUB.line()) && /1 با زمان‌بند/.test(E.HUB.line()), 'خط گزارش: ' + E.HUB.line());
  ok(E.HUB.own.gap.length >= 3 && E.HUB.own.pitch_db.length === 1, 'نقشهٔ مالک پارامترها موجود است');
}
console.log(fail ? 'FAILED ' + fail : 'ALL PASS'); process.exit(fail ? 1 : 0);
