// آزمون تلفظ شعر (POEM_BASE + HOM) بدون وابستگی به load.js: node pron.js ../index.html
const fs=require('fs'),vm=require('vm'),assert=require('assert');const h=fs.readFileSync(process.argv[2]||'index.html','utf8');
let n=0;const ok=(c,m)=>{assert(c,m);n++};
const at=s=>{const i=h.indexOf(s);if(i<0)throw new Error('pron.js: anchor یافت نشد: '+s);return i};
// ۱) واژه‌نامه: نبود تکرار با مقدار متفاوت، قالب سالم
const m=/var POEM_BASE = "((?:[^"\\]|\\.)*)"/.exec(h);ok(m,'POEM_BASE');
const L=JSON.parse('"'+m[1]+'"').split('\n').filter(x=>x.trim()&&x[0]!=='#');
const D=new Map();
for(const l of L){const p=l.indexOf('=');ok(p>0,'format: '+l);const v=l.slice(p+1).trim();ok(v,'empty: '+l);
  for(const k of l.slice(0,p).split('|').map(x=>x.trim())){if(D.has(k))ok(D.get(k)===v,'conflict: '+k);D.set(k,v)}}
ok(D.size>=900,'size '+D.size);
// ۲) نمونه‌های طلایی (واژه ← تلفظ)
const G={'نی':'نِی','خدایا':'خُدایا','غریب':'غَریب','حیران':'حِیران','سرگردان':'سَرگَردان','دروغ':'دُروغ','صراط':'صِراط','محفل':'مَحفِل','وطن':'وَطَن','عتاب':'عِتاب','غمزه':'غَمزِه','نغمه':'نَغمِه','شک':'شَکّ','جگرم':'جِگَرَم','تنت':'تَنَت','عشقش':'عِشقَش','دردت':'دَردَت','لبم':'لَبَم','عمرم':'عُمرَم','جهانت':'جَهانَت','زبانش':'زَبانَش','خسرو':'خُسرَو','سکندر':'سِکَندَر'};
for(const k in G)ok(D.get(k)===G[k],'golden '+k+' = '+D.get(k));
// ۳) واژه‌هایی که نباید مدخل شوند (تعارض با واژهٔ دیگر یا همنگاره)
for(const k of ['تنش','حالت','قدم','رخت','رخش2','قدر','قسم','سر','نفس','کرم','پری','می','هزار','خم'])ok(!D.has(k)||k==='رخش2'&&false,'forbidden '+k);
// ۴) HOM: می/هزار/خم
const a=at("var LEX_SRC = '"),a2=at('var HOM_SRC = ['),b=at('/* اعراب پیش از TTS (T-13)');
const code=h.slice(a,h.indexOf('var LEX = null',a))+h.slice(a2,b);
const ctx={bump(){},console};vm.createContext(ctx);vm.runInContext(code+';this.homApply=homApply;',ctx);
const T=[['جام می ده ساقی','جام مِی ده ساقی'],['می خورم و می روم','می خورم و می روم'],['ساقی می دهد جام','ساقی می دهد جام'],['بلبل هزار دستان نالید','بلبل هَزار دستان نالید'],['هزار بار گفتم','هزار بار گفتم'],['خم ابرو و خم زلف','خَم ابرو و خَم زلف'],['خم می شکست','خُم مِی شکست']];
for(const [i,o] of T)ok(ctx.homApply(i)===o,'hom '+i+' → '+ctx.homApply(i));
console.log('pron OK',n,'assertions');
