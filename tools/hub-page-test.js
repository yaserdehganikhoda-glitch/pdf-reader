// tools/hub-page-test.js — IIFE واقعی صفحهٔ «ناظر هوشمند» + HUB واقعی با stub برای DOM/تایمر؛ اجرا: node tools/hub-page-test.js index.html
const fs = require('fs'), vm = require('vm');
const h = fs.readFileSync(process.argv[2] || 'index.html', 'utf8'), L = h.split('\n');
const a = h.indexOf('/*@@HUB*/'), b = h.indexOf('/*@@/HUB*/');
const s0 = L.findIndex(l => l.includes('r137: صفحهٔ «ناظر هوشمند»')), e0 = L.findIndex((l, i) => i > s0 && /^\s*\}\)\(\);\s*$/.test(l) && /var CFG_KEY/.test(L[i + 2] || ''));
if (s0 < 0 || e0 < 0) { console.log('FAIL IIFE صفحه پیدا نشد', s0, e0); process.exit(1); }
const page = L.slice(s0, e0 + 1).join('\n');
let fail = 0; const ok = (c, m) => { console.log((c ? 'PASS ' : 'FAIL ') + m); if (!c) fail++; };
function mk(store) {
  let now = 1000, tid = 0; const T = new Map(), I = new Map(), els = {}, calls = [];
  const el = (id) => els[id] || (els[id] = { id, checked: false, classList: { contains: () => false }, onchange: null, onclick: null });
  ['hubOn', 'svMem', 'svExit', 'svTx', 'svThm', 'svMdl', 'svLvl', 'svPz', 'svRef', 'svBox'].forEach(el);
  const ctx = {
    Date: { now: () => now }, String, Promise, window: {}, document: { hidden: false, addEventListener() {} },
    setTimeout: (f, ms) => { const i = ++tid; T.set(i, { f, at: now + ms }); return i; }, clearTimeout: (i) => T.delete(i),
    setInterval: (f, ms) => { const i = ++tid; I.set(i, { f, ms, at: now + ms }); return i; }, clearInterval: (i) => I.delete(i),
    $: (q) => (q[0] === '#' ? els[q.slice(1)] || null : null), nf: { format: String },
    sget: (k, d) => (k in store ? store[k] : d), sset: (k, v) => { store[k] = v; }, svOn: () => true,
    svMemTick: () => calls.push('mem'), svSes: () => calls.push('ses'), svMdlRun: () => calls.push('mdl'), svRender: () => calls.push('rnd'), svBoot: () => {}, svTrim: () => {},
    playing: true, ocrBusy: false, curTab: 'voice', SV: {}, changed: () => {}, process: () => {}, pdfDoc: null, TXH: {}
  };
  vm.createContext(ctx); vm.runInContext(h.slice(a, b), ctx); vm.runInContext(page, ctx);
  calls.length = 0;   // svBoot()/svSes() اولیهٔ IIFE جزو تیک نیست
  const adv = (to) => { for (;;) { let m = null, mk2, isI = false; for (const [k, t] of T) if (!m || t.at < m.at) { m = t; mk2 = k; isI = false; } for (const [k, t] of I) if (!m || t.at < m.at) { m = t; mk2 = k; isI = true; } if (!m || m.at > to) { now = to; return; } now = m.at; if (isI) { m.at += m.ms; } else T.delete(mk2); m.f(); } };
  return { ctx, calls, T, I, els, adv, get now() { return now; } };
}
// الف) پیش‌فرض: hub روشن، بدون setInterval پیشین
{
  const E = mk({}); const t0 = E.now; E.adv(t0 + 10000);
  ok(E.ctx.HUB.running() && E.I.size === 0 && E.T.size === 1, 'پیش‌فرض: HUB روشن، تایمر پیشین نیست، فقط یک setTimeout (I=' + E.I.size + ', T=' + E.T.size + ')');
  ok(E.calls.slice(0, 4).join() === 'mem,ses,mdl,rnd'.replace('rnd', 'mdl').split(',').slice(0, 3).concat([]).join() || E.calls.slice(0, 3).join() === 'mem,ses,mdl', 'ترتیب تیک: mem ← ses ← mdl (' + E.calls.slice(0, 4).join() + ')');
  ok(E.calls.filter(x => x === 'mem').length === 2 && E.calls.filter(x => x === 'rnd').length === 1, 'در ۱۰ثانیه: ۲ تیک؛ رندر گزارش هر ۲ تیک (۱ بار)');
}
// ب) hubOn=0 ⇒ همان setInterval(5000) پیشین
{
  const E = mk({ hubOn: '0' }); const t0 = E.now; E.adv(t0 + 10000);
  ok(!E.ctx.HUB.running() && E.I.size === 1 && [...E.I.values()][0].ms === 5000, 'hubOn=0: HUB خاموش و setInterval(5000) پیشین');
  ok(E.calls.filter(x => x === 'mem').length === 2 && E.calls.filter(x => x === 'rnd').length === 1, 'رفتار پیشین یکسان: ۲ تیک و ۱ رندر در ۱۰ثانیه');
}
// پ) تعویض زنده با تیک
{
  const E = mk({}); E.adv(E.now + 6000);
  E.els.hubOn.checked = false; E.els.hubOn.onchange.call(E.els.hubOn); ok(!E.ctx.HUB.running() && E.I.size === 1 && E.T.size === 0, 'خاموش‌کردن زنده ⇒ بازگشت فوری به تایمر پیشین، بدون تایمر HUB');
  E.els.hubOn.checked = true; E.els.hubOn.onchange.call(E.els.hubOn); ok(E.ctx.HUB.running() && E.I.size === 0 && E.T.size === 1, 'روشن‌کردن ⇒ HUB، تایمر پیشین پاک');
}
// ت) گیت: پس‌زمینهٔ بی‌کار ⇒ هیچ ناظری اجرا نمی‌شود (مثل پیش)
{
  const E = mk({}); E.ctx.document.hidden = true; E.ctx.playing = false; E.adv(E.now + 20000); ok(E.calls.length === 0, 'پنهان و بی‌کار: هیچ تیکی اجرا نمی‌شود');
  E.ctx.document.hidden = false; E.adv(E.now + 6000); ok(E.calls.length > 0, 'با برگشت به پیش‌زمینه دوباره اجرا می‌شود');
}
// ث) ثبت‌نام: همهٔ ناظرهای صفحه فهرست شده‌اند
{
  const E = mk({}); const L = E.ctx.HUB.list(), ids = L.map(x => x.id);
  const need = ['svMem', 'svExit', 'svMdl', 'svRender', 'svTx', 'svPz', 'svLvl', 'nzOn', 'hsOn', 'svThm', 'poMt', 'poEj', 'poRest', 'poAll', 'poDs', 'poRowForce', 'spGuardAll', 'wdTick', 'kaSync', 'hbT'];
  ok(need.every(x => ids.includes(x)), 'همهٔ ' + need.length + ' ناظر صفحه/دستهٔ A در فهرست (' + L.length + ' ثبت)');
  ok(L.every(x => x.cat), 'هر ناظر دسته دارد'); ok(L.filter(x => x.sched).length === 4, 'فقط ۴ ناظر B زمان‌بندی مشترک دارند');
}

// ج) مهاجرت دوم: ناظر زنده‌سازی موسیقی (IIFE واقعی bgm فقط همان دستور ثبت‌نام) با stub
{
  const i0 = h.indexOf("HUB.reg('bgmRevive'"), i1 = h.indexOf('} });', i0) + 4; if (i0 < 0 || i1 < i0) { console.log('FAIL ثبت bgmRevive پیدا نشد'); process.exit(1); }
  const stmt = h.slice(i0, i1);
  function mkB(store) {
    let now = 1000, tid = 0; const T = new Map(), I = new Map(); let sync = 0, logs = 0;
    const ctx = { Date: { now: () => now }, String, sget: (k, d) => (k in store ? store[k] : d), setTimeout: (f, ms) => { const i = ++tid; T.set(i, { f, at: now + ms }); return i; }, clearTimeout: (i) => T.delete(i), setInterval: (f, ms) => { const i = ++tid; I.set(i, { f, ms, at: now + ms }); return i; }, clearInterval: (i) => I.delete(i),
      bgm: { loaded: true, el: { paused: true }, ctx: { state: 'running' } }, bgmWant: () => true, bgmLog: () => { logs++; }, musicSync: () => { sync++; } };
    vm.createContext(ctx); vm.runInContext(h.slice(a, b), ctx); vm.runInContext(stmt, ctx);
    const adv = (to) => { for (;;) { let m = null, mk, isI = false; for (const [k, t] of T) if (!m || t.at < m.at) { m = t; mk = k; isI = false; } for (const [k, t] of I) if (!m || t.at < m.at) { m = t; mk = k; isI = true; } if (!m || m.at > to) { now = to; return; } now = m.at; if (isI) m.at += m.ms; else T.delete(mk); m.f(); } };
    return { ctx, T, I, adv, get sync() { return sync; }, get logs() { return logs; }, get now() { return now; } };
  }
  let E = mkB({}); E.adv(E.now + 10000);
  ok(E.ctx.HUB.running() && E.I.size === 0 && E.T.size === 1 && E.sync >= 4 && E.sync <= 5, 'bgmRevive: روی HUB (۱ تایمر، بدون setInterval)؛ موسیقی متوقف ⇒ musicSync هر ۲ث (' + E.sync + ' در ۱۰ث)');
  E = mkB({}); E.ctx.bgm.el.paused = false; E.adv(E.now + 10000); ok(E.sync === 0, 'موسیقی در حال پخش و ctx فعال: هیچ مداخله‌ای');
  E = mkB({}); E.ctx.bgm.el.paused = false; E.ctx.bgm.ctx.state = 'suspended'; E.adv(E.now + 5000); ok(E.sync >= 2, 'ctx معلق ⇒ زنده‌سازی');
  E = mkB({}); E.ctx.bgm.loaded = false; E.adv(E.now + 10000); ok(E.sync === 0, 'موسیقی بارگذاری‌نشده: بی‌اثر');
  E = mkB({ hubOn: '0' }); E.adv(E.now + 10000); ok(!E.ctx.HUB.running() && E.I.size === 1 && [...E.I.values()][0].ms === 2000 && E.sync === 5, 'hubOn=0: همان setInterval(2000) پیشین (' + E.sync + ' اجرا در ۱۰ث)');
  const J = mkB({}); J.ctx.HUB.reg('svMem', { every: 5000, grp: 'sv', crit: 1, run: () => {} }); const w0 = [...J.T.values()].length; J.adv(J.now + 100000); ok(w0 === 1, 'bgm و sv با هم: همچنان یک تایمر مشترک');
}

// د) نگهبان خطای ناظرهای رویدادمحور: هشت تابع واقعاً با H.wrap دوباره‌بسته شده‌اند و ثبت‌نام دارند
{
  const want = { svPzTx: 'svPz', svPzCtx: 'svPz', proPD: 'emo', emoSplit: 'emo', poRhSplit: 'poRh', poRestAt: 'poRest', poemEnd: 'poEnd', svLoud: 'svLvl' };
  const miss = Object.keys(want).filter(n => !new RegExp('\\nif \\(typeof HUB !== \'undefined\'\\) ' + n + ' = HUB\\.wrap\\(\'' + want[n] + '\', ' + n + '[,)]').test(h)); ok(miss.length === 0, 'هر ۸ تابع بلافاصله پس از اعلانشان با H.wrap بسته شده‌اند' + (miss.length ? ' — کم: ' + miss : ''));
  const E = mk({}); const ids = E.ctx.HUB.list().map(x => x.id); ok(['emo', 'poRh', 'poEnd', 'svPz', 'poRest', 'svLvl'].every(x => ids.includes(x)), 'شناسهٔ هر نگهبان در فهرست ناظرها هست (' + ids.length + ' ثبت)');
  // تابع واقعی svPzTx با stub: خطای svOn نباید پخش را بشکند
  const i0 = h.indexOf('function svPzTx(s){'), i1 = h.indexOf('\n}', i0) + 2; const fn = h.slice(i0, i1);
  const c = { HUB: null, svOn: () => { throw new Error('boom'); }, SV: { pzT: 0 }, elogR() {}, String };
  vm.createContext(c); vm.runInContext(h.slice(a, b), c); vm.runInContext(fn + '\nvar raw = svPzTx; svPzTx = HUB.wrap("svPz", svPzTx);', c);
  let threw = false; try { vm.runInContext('raw("a،،b")', c); } catch (e) { threw = true; }
  ok(threw && vm.runInContext('svPzTx("a،،b")', c) === 'a،،b', 'svPzTx واقعی: بدون نگهبان می‌پرتاب می‌کرد؛ با نگهبان متن اصلی برمی‌گردد');
  c.svOn = () => true; ok(vm.runInContext('svPzTx("a،،b")', c) === 'a،b', 'بدون خطا: کار اصلی (ادغام مکث تکراری) دست‌نخورده');
}
console.log(fail ? 'FAILED ' + fail : 'ALL PASS'); process.exit(fail ? 1 : 0);
