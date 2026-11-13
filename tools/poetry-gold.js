/* ارزیاب اسکنر وزن شعر (html-r236+). اجرا: node tools/poetry-gold.js [index.html] [--update-baseline] [--all]
   ورودی: tools/golden-poetry.json {items:[{text,meter (عدد یا آرایه),verified}], prose:[{text}]} — متن با نیم‌فاصلهٔ واقعی (همان‌گونه که برنامه می‌دهد)  ·  خط‌پایه: tools/golden-poetry.baseline.json
   گیت: برازش کاذب نثر ≤ ۲٪؛ برازش فقط-به-وزن-غلط ≤ ۵٪ مجموع؛ نرخ برازش درست از خط‌پایه کمتر نشود (فقط verified:true؛ با --all همه). */
const fs = require('fs'), vm = require('vm'), path = require('path');
const dir = __dirname, html = process.argv.find(a => /\.html$/.test(a)) || path.join(dir, '..', 'index.html');
const h = fs.readFileSync(html, 'utf8'), a = h.indexOf('var PO_MT ='), b = h.indexOf('function ttsPo(s, f){');
if (a < 0 || b < 0) { console.error('بلوک اسکنر پیدا نشد'); process.exit(2); }
const c = { window: {}, Date, sents: [], docName: 'g', poV2: () => 1, sget: () => '1', document: { getElementById: () => null } };
vm.createContext(c); vm.runInContext(h.slice(a, b) + ';this.poMesra=poMesra;', c);
const G = JSON.parse(fs.readFileSync(path.join(dir, 'golden-poetry.json'), 'utf8')), all = process.argv.includes('--all');
const fits = t => { const r = c.poMesra(t); return r ? [...new Set(r.fits.map(x => x[0]))] : []; };
const hit = (f, m) => Array.isArray(m) ? m.some(k => f.includes(k)) : f.includes(m), items = G.items.filter(x => all || x.verified), per = {}; let ok = 0, wrong = 0, any = 0;
items.forEach(x => { const f = fits(x.text), k = [].concat(x.meter)[0], p = per[k] = per[k] || { n: 0, ok: 0 }; p.n++; if (hit(f, x.meter)) { ok++; p.ok++; } if (f.length) any++; if (f.length && !hit(f, x.meter)) wrong++; });
const fp = G.prose.filter(x => fits(x.text).length).length, res = { n: items.length, correct: ok, wrongOnly: wrong, prose: G.prose.length, proseFalse: fp };
console.log('نمونه‌های شمرده‌شده:', items.length, '(verified' + (all ? ' + provisional' : '') + ')'); Object.keys(per).forEach(k => console.log('  وزن', k, per[k].ok + '/' + per[k].n));
console.log('درست', ok + '/' + items.length, '· فقط-غلط', wrong, '· کاذب نثر', fp + '/' + G.prose.length);
const bp = path.join(dir, 'golden-poetry.baseline.json');
if (process.argv.includes('--update-baseline')) { fs.writeFileSync(bp, JSON.stringify(res)); console.log('خط‌پایه ذخیره شد'); process.exit(0); }
let fail = [];
if (G.prose.length && fp / G.prose.length > 0.02) fail.push('برازش کاذب نثر > ۲٪');
if (items.length && wrong / items.length > 0.05) fail.push('برازش به وزن غلط > ۵٪');
if (fs.existsSync(bp)) { const B = JSON.parse(fs.readFileSync(bp, 'utf8')); if (items.length && B.n && ok / items.length < B.correct / B.n - 1e-9) fail.push('نرخ برازش درست از خط‌پایه کمتر شد'); }
if (fail.length) { console.log('FAIL:', fail.join(' · ')); process.exit(1); } console.log('PASS');
