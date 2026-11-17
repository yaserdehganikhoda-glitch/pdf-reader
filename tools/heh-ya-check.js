#!/usr/bin/env node
/* tools/heh-ya-check.js (html-r251): آزمون قاعدهٔ «ه + ای / ی» در glueFix — بدون مرورگر.
   اجرا: node tools/heh-ya-check.js [index.html] [fa_lexicon.js]
   بدون فایل فرهنگ‌واژه، فرهنگ‌واژهٔ کوچک آزمایشی به‌کار می‌رود؛ با فایل واقعی، شکاف‌های فرهنگ‌واژه هم دیده می‌شود. خروج ۱ = شکست. */
const fs = require('fs'), vm = require('vm'), path = require('path');
const html = path.resolve(process.argv[2] || path.join(__dirname, '..', 'index.html'));
const lexf = process.argv[3] || '';
const L = fs.readFileSync(html, 'utf8').split('\n');
const a = L.findIndex(l => l.includes('var miFix')), b = L.findIndex(l => l.includes('var ocrSpell = (function(){'));
let e = b; while (L[e] !== '})();') e++;
let g = b - 1; while (L[g].trim() !== '})();') g--;
const src = L.slice(a, g + 1).join('\n') + '\n' + L.slice(b, e + 1).join('\n');
const stub = "function normCh(t){return String(t).replace(/ك/g,'ک').replace(/ي/g,'ی');}\n" +
  "var CW=new Set(),EZ_STOP=new Set(),EZ_STOP2=new Set(),EZ_V=new Set(),EZ_VBX=new Set(),NUMW=new Set(),NOCOMMA_PREV=new Set(),P_SPLIT=new Set(),ON=[],TN=[],HN=[],SC=[],MON_FA=[],LEX_SRC='';\n";
const mini = 'انگیزه مسئله لحظه شیشه مجموعه تجربه جامعه ضربه سرچشمه ریشه اندیشه چشمه نافه چرخه محاسبه لایه سایه دیرینه غصه رابطه زاویه نقشه اندازه تازه شیوه زنجیره پنجره قطره چهره حادثه کره پایه نقطه بچه خانه کتاب ساعت نمونه دانشگاه ایده دسته نامه ساده چشم ریش ناف نقش چرخ لای ماهی کوهی راهی گاهی الهی گواهی آگاهی کوتاهی همراهی شاهی نگاهی سیاهی سپاهی خواهی بدهی تنهایی انتهای روزهایی فرایند ذره کوه راه کوتاه';
const win = { FA_WORDS: mini, addEventListener() {} };
const ctx = { window: win, self: win, performance: { now: Date.now }, console, setTimeout };
vm.createContext(ctx);
if (lexf) { vm.runInContext(fs.readFileSync(lexf, 'utf8'), ctx); if (!win.FA_WORDS && win.FA_LEXICON) win.FA_WORDS = win.FA_LEXICON; }
vm.runInContext(stub + src + '\n;this.__g=glueFix;', ctx);
const Z = '\u200c', T = [
  // مثبت: باید اصلاح شود
  ['انگیز های', 'انگیزه' + Z + 'ای'], ['مسئل های', 'مسئله' + Z + 'ای'], ['مسئل' + Z + 'های', 'مسئله' + Z + 'ای'], ['لحظ' + Z + 'های', 'لحظه' + Z + 'ای'],
  ['شیش' + Z + 'های', 'شیشه' + Z + 'ای'], ['غص' + Z + 'های', 'غصه' + Z + 'ای'], ['تازهای', 'تازه' + Z + 'ای'], ['اندازهای', 'اندازه' + Z + 'ای'],
  ['یک چشم' + Z + 'های', 'یک چشمه' + Z + 'ای'], ['هیچ ریش' + Z + 'های', 'هیچ ریشه' + Z + 'ای'],
  ['پایهی', 'پایه' + Z + 'ی'], ['کرهی', 'کره' + Z + 'ی'], ['انگیزهی', 'انگیزه' + Z + 'ی'], ['پایه ی', 'پایه' + Z + 'ی'], ['بچه ای', 'بچه' + Z + 'ای'],
  // منفی: نباید تغییر کند
  ['خانه' + Z + 'های', 'خانه' + Z + 'های'], ['کتاب های', 'کتاب' + Z + 'های'], ['چشم' + Z + 'های', 'چشم' + Z + 'های'], ['نقش' + Z + 'های', 'نقش' + Z + 'های'],
  ['ماهی', 'ماهی'], ['کوهی', 'کوهی'], ['راهی', 'راهی'], ['گاهی', 'گاهی'], ['الهی', 'الهی'], ['گواهی', 'گواهی'], ['آگاهی', 'آگاهی'], ['کوتاهی', 'کوتاهی'],
  ['تنهایی', 'تنهایی'], ['روزهایی', 'روزهایی'], ['بدهی', 'بدهی'], ['خواهی', 'خواهی']
];
let bad = 0;
T.forEach(([i, o]) => { const r = ctx.__g(i); if (r !== o) { bad++; console.log('FAIL', JSON.stringify(i), '->', JSON.stringify(r), 'want', JSON.stringify(o)); } });
console.log(bad ? 'heh-ya-check: ' + bad + ' FAIL / ' + T.length : 'heh-ya-check: OK ' + T.length + '/' + T.length);
process.exit(bad ? 1 : 0);
