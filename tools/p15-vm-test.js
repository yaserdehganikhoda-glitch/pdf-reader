// p15-vm-test.js — آزمون یکپارچهٔ بستهٔ Worker (TXW:A+B+C) در Node بدون مرورگر/دستگاه
// اجرا:  node p15-vm-test.js index.html [index-قدیمی.html]
// پوشش: A01 (گارد شعر/عربی)، A02 (fixHi در شعر)، A04 (احساس حقوقی/علمی)، A11 (عربی)، F8 (guardAll). A05/A06/A07/A08 در نخ اصلی‌اند ⇒ آزمون دستگاه.
const fs = require('fs'), vm = require('vm');
function load(file, api) {
  const full = fs.readFileSync(file, 'utf8'), L = full.split('\n');
  const get = (n) => vm.runInNewContext(L.find(l => l.startsWith('  var ' + n + ' =')).replace(/^\s*var /, '(').replace(/;\s*(\/\*.*)?$/, ')'));
  const OVR = get('TXW_OVR'), PRE = get('TXW_PRE');
  const s = [...full.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]).find(t => t.indexOf('/*@@' + 'TXW:A*/') >= 0);
  const MK = ['/*@@TXW:A*/', '/*@@TXW:/A*/', '/*@@TXW:B*/', '/*@@TXW:/B*/', '/*@@TXW:C*/', '/*@@TXW:/C*/'], ix = MK.map(m => s.indexOf(m));
  const bundle = s.slice(ix[0] + MK[0].length, ix[1]) + '\n' + s.slice(ix[2] + MK[2].length, ix[3]) + '\n' + s.slice(ix[4] + MK[4].length, ix[5]);
  const ctx = { console, self: { addEventListener() {} }, setTimeout, clearTimeout, performance, Intl, navigator: {} }; vm.createContext(ctx);
  vm.runInContext(PRE + bundle + '\n' + OVR + '\n;this.__api=' + api + ';', ctx); return ctx;
}
const API = '{manPolish,ttsText,emoTag,vgPgText:(typeof vgPgText!=="undefined"?vgPgText:null),setO:function(v){O=v},poMask:(typeof poMask!=="undefined"?poMask:null),setS:function(o){Object.assign(__S,o)},setPO:function(v){if(typeof TTS_PO!=="undefined")TTS_PO=v}}';
const N = load(process.argv[2] || 'index.html', API), O = process.argv[3] ? load(process.argv[3], API) : null;
const poem = ['ای ماه مرای دل ز غم آزاد کن امشب', 'از تعبین مهر تو جان شاد کن امشب', 'هر شب که تویی یار دل ما شاد باشد', 'با لطف نگاهی دل ما شاد کن امشب', 'ای نازنین بی‌وفا ما را یاد کن امشب', 'در عشق تو ای یار بنیاد کن امشب'];
const prose = ['این متن نثر است و ویزگی‌های آن تعبین می‌شود و باید در این بخش از متن که بلند است مرای دیگر هم اصلاح شود.', 'جملهٔ دوم نیز نثر است و طول آن بیشتر از پنجاه و پنج نویسه است تا هرگز شعر تشخیص داده نشود و بماند.'];
const text = prose.concat(poem).concat(prose).join('\n'), ls = (x) => x.split('\n');
const o = { norm: true, sp: true, spell: false, pun: false, com: false, dot: false, join: false, ts: false, har: 'none' };
const kept = (x) => poem.every(p => ls(x).includes(p)); let fail = 0;
const ok = (c, m) => { console.log((c ? 'PASS ' : 'FAIL ') + m); if (!c) fail++; };
const a = N.__api.manPolish(text, Object.assign({}, o));
ok(kept(a), 'A01 شعر دست‌نخورده در manPolish'); ok(!/ویزگی|تعبین/.test(ls(a)[0] + ls(a)[1]), 'A01 نثر هنوز اصلاح می‌شود');
ok(!kept(N.__api.manPolish(text, Object.assign({ force: true }, o))), 'A01 انتخاب صریح کاربر (force) محفوظ');
if (O) ok(!kept(O.__api.manPolish(text, Object.assign({}, o))), 'مرجع: نسخهٔ قدیم شعر را تغییر می‌داد');
N.__api.setS({ fixOn: true, fixHi: true });
const t0 = N.__api.ttsText('حق حق حق ای دل مست', 'device'); N.__api.setPO(1); const t1 = N.__api.ttsText('حق حق حق ای دل مست', 'device'); N.__api.setPO(0);
ok(t0.indexOf('حق حق') < 0 && /حق حق حق/.test(t1), 'A02 تکرار در شعر حفظ و در نثر کاسته می‌شود');
const e = N.__api.emoTag;
ok(e('به‌خاطر این خبر بسیار خوشحال و شادمانم!').k > 0, 'A04 نثر عادی: احساس برقرار');
ok(e('طبق ماده ۱۲ این قانون باید خوشحال باشد!').k === 0, 'A04 حقوقی: خاموش'); ok(e('نتایج آزمایش نشان داد که خوشحال کننده است!').k === 0, 'A04 علمی: خاموش');

// --- r241: F8 «محافظت کل متن»، A11 عربی ---
const plain = ['این متن نثر است و ویزگی‌های آن تعبین می‌شود و باید در این بخش از متن که بلند است مرای دیگر هم اصلاح شود.'];
ok(N.__api.manPolish(plain[0], Object.assign({}, o)) !== plain[0], 'نثر عادی اصلاح می‌شود (مبنا)');
{ const g = N.__api.manPolish(plain[0], Object.assign({ guardAll: true }, o)); ok(g.indexOf('ویزگی') >= 0 && g.indexOf('تعبین') >= 0, 'F8 guardAll: هیچ جایگزینی املا روی نثر'); }
ok(N.__api.manPolish(plain[0], Object.assign({ guardAll: true, force: true }, o)).indexOf('ویزگی') < 0, 'F8 force (ویرایشگر دستی) از guardAll مستثناست');
ok(N.__api.poMask('a\nb', true).every(Boolean), 'F8 poMask(all) همهٔ سطرها');
N.__api.setS({ fixOn: true, fixHi: true });
ok(N.__api.ttsText('الله الله الله رحمة للعالمین', 'device').split('اللاه').length - 1 >= 3, 'A11 عربی: تکرار در سطر عربی کاسته نمی‌شود');
ok(N.__api.ttsText('حق حق حق ای دل', 'device').indexOf('حق حق') < 0, 'A11 مبنا: نثر فارسی همچنان کاسته می‌شود');
ok(e('رحمة للعالمین است و خوشحال و شادمانم!').k === 0, 'A11 عربی: احساس خاموش');

// --- r242: «ة» در سطر عربی/شعر به «ه» تبدیل نمی‌شود؛ ي/ك همچنان یکدست می‌شوند ---
{
  const ar = 'رحمة الله وبركاته عليكم وعلى عباد الله الصالحين'; const fa = 'این جملهٔ فارسی است و كتاب ديگر را می‌خواند و ادامه دارد در همین بند بلند از متن';
  const t = N.__api.manPolish(ar + '\n' + fa, Object.assign({}, o)).split('\n');
  ok(t[0].indexOf('\u0629') >= 0, 'A11 manPolish: «ة» در سطر عربی می‌ماند');
  ok(t[0].indexOf('ك') < 0 && t[0].indexOf('ي') < 0, 'A11 ي/ك در سطر عربی همچنان یکدست می‌شود (نویسه، نه املا)');
  ok(t[1].indexOf('كتاب') < 0 && t[1].indexOf('ديگر') < 0, 'مبنا: نثر فارسی یکدست‌سازی می‌شود');
  ok(N.__api.manPolish(ar, Object.assign({ force: true }, o)).indexOf('\u0629') < 0, 'A11 force (ویرایشگر دستی): رفتار پیشین');
  ok(N.__api.manPolish('رحمة ' + fa, Object.assign({ guardAll: true }, o)).indexOf('\u0629') >= 0, 'F8 guardAll: «ة» حفظ');
  N.__api.setS({ fixOn: false });
  const sp = N.__api.ttsText('رحمة الله', 'device'); ok(sp.indexOf('\u0629') < 0, 'A11 مسیر بدون fixOn: «ة» خام به موتور نمی‌رسد');
  N.__api.setS({ fixOn: true });
  if (N.__api.vgPgText) {
    N.__api.setO({ ln: 'auto', norm: true, glue: false, spell: false, spGuard: false, smart: false });
    let r1 = ''; try { r1 = N.__api.vgPgText(ar + '\n' + fa, 0, false, null, null); } catch (e) { r1 = 'ERR ' + e.message; }
    ok(r1.indexOf('\u0629') >= 0 && r1.indexOf('كتاب') < 0, 'A11 vgPgText: «ة» می‌ماند و نثر یکدست می‌شود ' + (r1.slice(0, 3) === 'ERR' ? r1 : ''));
  }
}
console.log(fail ? ('FAILED ' + fail) : 'ALL PASS'); process.exit(fail ? 1 : 0);
