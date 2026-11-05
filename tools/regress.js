// رگرسیون اصلاحات r184 (نرمال‌سازی/جمله‌بندی): node regress.js ../index.html
const assert=require('assert');const X=require('./load.js')(process.argv[2]);X.S.fixOn=1;X.S.fixHi=1;X.S.fixPro=1;
let n=0;const ok=(c,m)=>{assert(c,m);n++};
X.ev('O.maxl=260;O.pause=true');
const T=s=>String(X.ttsText(s)).replace(/[\u064B-\u0652]/g,''),sp=X.ev('splitSentences');   /* با harOn اعراب اضافه می‌شود؛ مقایسه بی‌اعراب */
ok(/اول فروردین هزار و چهارصد و سه/.test(T('فردا 1403.01.01 است.')),'F-03 تاریخ شمسی نقطه‌دار');
ok(/یک میلیون و دویست و پنجاه هزار/.test(T('قیمت 1.250.000 تومان.')),'F-03 هزارگان نقطه‌ای');
ok(/سه نقطه دو نقطه یک/.test(T('نسخه 3.2.1 منتشر شد.')),'F-03 نسخه دست‌نخورده');
ok(/نود میلی‌گرم بر دسی‌لیتر/.test(T('۹۰ mg/dL است.')),'F-04 mg/dL');
ok(/کیلومتر بر ساعت/.test(T('سرعت ۵۰ km/h بود.')),'F-04 km/h سالم');
ok(sp('آقای ا. ب. جمالی آمد. او رفت.').length===2,'F-02 زنجیرهٔ حرف اول');
ok(sp('احمد ر. محمدی نوشت. دیگری خواند.').length===2,'F-02 نام+حرف اول+نام‌خانوادگی');
ok(sp('در بند ب. سپس رفتیم.').length===2,'F-02 بند ب. استثنا');
ok(sp('الف. ب. ج. د.').length>=1&&sp('گزینه ب. درست است.').length===2,'F-02 فهرست/گزینه');
ok(!/غیره/.test(T('نمی‌دانم... شاید.')),'F-01 تردید ≠ غیره');
ok(/غیره/.test(T('شامل سیب، گلابی، پرتقال و … است.')),'F-01 فهرست = غیره');
ok(!/غیره غیره/.test(T('و غیره...')),'F-01 غیره دوباره‌خوانی ندارد');
const t0=Date.now();X.ev("translit('ab'.repeat(8000))");ok(Date.now()-t0<300,'F-07 translit خطی');
console.log('regress OK',n,'assertions');
