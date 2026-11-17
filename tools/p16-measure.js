// tools/p16-measure.js — اندازه‌گیری یافته‌های P16 روی توابع واقعی index.html؛ اجرا: node tools/p16-measure.js index.html
// هر خط «BAD» یعنی رفتار هنوز با یافتهٔ گزارش یکی است؛ پس از اصلاح باید «OK» شود.
process.env.INDEX = process.argv[2] || 'index.html';
process.env.CHK = JSON.stringify({ fixOn: true, fixHi: true, fixCite: true });
const h = require('./p16-load.js'); if (h.err) { console.log('FAIL بوت صفحه:', h.err.message); process.exit(1); }
const tx = h.ev('ttsText'), fx = h.ev('svFixTx'), pf = h.ev('poemFlags'), et = h.ev('emoTag');
const say = (t) => fx(tx(t)); let bad = 0;
const chk = (id, ok, m) => { console.log((ok ? 'OK  ' : 'BAD ') + id + ' — ' + m); if (!ok) bad++; };
chk('R-01', /ماده\s+(پنج|۵)/.test(say('طبق ماده (۵) قانون')) && /جدول\s+(یک|۱)/.test(say('جدول (۱) را ببینید')), '«ماده (۵)» و «جدول (۱)»: شمارهٔ برچسب حذف نشود → ' + say('طبق ماده (۵) قانون و جدول (۱)'));
chk('R-02', say('p<0.05') !== say('p>0.05'), '«p<0.05» با «p>0.05» یکسان خوانده نشود → ' + say('p<0.05') + ' | ' + say('p>0.05'));
chk('R-03', /به توان/.test(say('10^3')), '«10^3» ⇒ «به توان» → ' + say('10^3'));
chk('R-04a', !/میلیارد/.test(say('کدپستی ۱۲۳۴۵۶۷۸۹۰')), 'کدپستی رقم‌به‌رقم → ' + say('کدپستی ۱۲۳۴۵۶۷۸۹۰'));
chk('R-04b', !/هزار/.test(say('تلفن 8877 6655')), 'تلفن ۴+۴ رقم‌به‌رقم → ' + say('تلفن 8877 6655'));
const gh = ['الا یا ایها الساقی ادر کأساً و ناولها', 'که عشق آسان نمود اول ولی افتاد مشکل‌ها', 'به بوی نافه‌ای کاخر صبا زان طره بگشاید', 'ز تاب جعد مشکینش چه خون افتاد در دل‌ها'];
const pr = ['در این بخش به بررسی روش‌های پژوهش می‌پردازیم', 'و نتایج به‌دست‌آمده را با یافته‌های پیشین مقایسه', 'می‌کنیم تا روشن شود که تفاوت‌ها از کجا ناشی', 'می‌شوند و چه پیامدهایی برای عمل دارند و همچنین', 'چگونه می‌توان آن‌ها را در آینده بهبود بخشید'];
const np = (ls) => (pf(ls).b || []).filter(Boolean).length;
chk('R-05', np(gh) === 4 && np(pr) === 0, 'غزل ۴/۴ شعر؛ نثر ستون‌باریک بی‌قافیه شعر نباشد (غزل ' + np(gh) + '/4، نثر ' + np(pr) + '/5)');
const ek = et('در این پژوهش، اثر ترس و اضطراب بر عملکرد حافظهٔ کاری دانشجویان بررسی شد و نشان داد که خشم و غم با کاهش توجه همراه است.');
chk('R-06', ek.k === 0, 'جملهٔ علمیِ بی‌رقم: k=۰ (اکنون ' + JSON.stringify(ek) + ')');
const srcTxt = require('fs').readFileSync(process.env.INDEX, 'utf8');
const mm = srcTxt.match(/text\.replace\(\/:\+\/g, ','\)\.replace\((\/\[\^[^\n]*?\/gu), ' '\)/);
const simple = (s) => mm ? s.replace(/:+/g, ',').replace(eval(mm[1]), ' ').replace(/\s+/g, ' ').trim() : s;
chk('R-07', !!mm && /\u200c/.test(simple('می\u200cخواهم')) && /\u0650/.test(simple('دل\u0650')), 'فیلتر بازیابی (plain) ZWNJ و کسره را نگه دارد → ' + JSON.stringify(simple('می\u200cخواهم دل\u0650')));
chk('R-08', !/cdn\.jsdelivr\.net|https?:\/\/[^"'\s]*lame/i.test(srcTxt), 'هیچ نشانی CDN برای کد (lamejs) در صفحه نیست');
console.log(bad ? 'جمع: ' + bad + ' مورد هنوز BAD' : 'جمع: همه OK');
