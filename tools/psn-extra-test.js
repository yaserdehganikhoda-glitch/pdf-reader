const fs=require('fs'); const src=fs.readFileSync(process.argv[2],'utf8'), Z='\u200c', z=s=>s.replace(/_/g,Z), u=s=>s.replace(/\u200c/g,'_');
const a=src.indexOf('var PSN = (function(){'), b=src.indexOf('try { window.PSN = PSN; }');
const g=re=>src.match(re)[0];
const pre=[g(/^ *function wcore\(w\)\{.*$/m),g(/^ *var PZ_NOPRE = .*$/m),g(/^ *var PZ_NOPRE_V = .*$/m),g(/^ *var PZ_VERB = .*$/m)].join('\n');
let throwDict=false; const D=new Set('رفت گفت'.split(' '));
const PSN=new Function('ocrSpell',pre+'\n'+src.slice(a,b)+'\nreturn PSN;')({known:w=>{ if(throwDict) throw new Error('x'); return D.has(w); }});
PSN.cfg.lvl=1; let ok=0,bad=0; const t=(n,c)=>{ c?ok++:(bad++,console.log('✗',n)); };
// ۱) !عبارت
t('base merge', u(PSN.norm('او دانش آموز است',true))==='او دانش_آموز است');
PSN.user(['دانش آموز'],[]); t('! جلوگیری', u(PSN.norm('او دانش آموز است',true))==='او دانش آموز است');
PSN.user([],['بلند پایه']); t('+ افزودن', u(PSN.norm('طرح بلند پایه است',true))==='طرح بلند_پایه است');
PSN.user([],[]); t('+ حذف', u(PSN.norm('طرح بلند پایه است',true))==='طرح بلند پایه است');
// ۲) memo
const x='کتاب ها را می خوانم'; const r1=PSN.norm(x,true), r2=PSN.norm(x,true); t('memo',r1===r2&&u(r1)==='کتاب_ها را می_خوانم');
// ۳) memo وقتی فرهنگ‌واژه در دسترس نیست ذخیره نشود
throwDict=true; PSN.clear(); const y='او رفته است'; const k1=PSN.norm(y,true); throwDict=false; const k2=PSN.norm(y,true);
t('KERR no-memo', k1!==undefined); console.log('  بدون فرهنگ‌واژه:',u(k1),'| با فرهنگ‌واژه:',u(k2));
// ۴) load/data/reset
const d=PSN.data(); t('data shape', typeof d.cmp==='string'&&Array.isArray(d.dm)&&typeof d.nobef==='string');
t('load invalid', PSN.load({dm:[['x','z']]})===false && PSN.load('x')===false);
PSN.load({cmp:'برق گیر|'+d.cmp}); t('load cmp', u(PSN.norm('برق گیر نصب شد',true))==='برق_گیر نصب شد');
PSN.reset(); t('reset', u(PSN.norm('برق گیر نصب شد',true))==='برق گیر نصب شد');
// ۵) diff و stats
const df=PSN.diff('کتاب ها را می خوانم'); t('diff', df.some(e=>e.t==='del')&&df.some(e=>e.t==='add')&&df.filter(e=>e.t==='same').length>=1);
const st=PSN.stats(['او به خانه نرفت چون هوا بسیار سرد بود','کتاب ها را می خوانم','او رفت']); console.log('  stats:',JSON.stringify(st));
t('stats', st.sents===3&&st.injectedPer100>0&&st.changedPct>0);
// ۶) نسخه
t('V', PSN.V==='r106');
// ۷) regex dictIn: هر دو شکل
const A='دانش آموز'.replace(/[\\^$.*+?()[\]{}|\/]/g,'\\$&').replace(/ +/g,'[ \u200c]');
const re=new RegExp('(?<![\\p{L}\\p{N}\u200c])'+A+'(?=(?:\u200c(?:ها|های))?(?![\\p{L}\\p{N}\u200c]))','gu');
t('dictIn spaced', 'x دانش آموز y'.replace(re,'#')==='x # y'); t('dictIn zwnj', ('x دانش'+Z+'آموز y').replace(re,'#')==='x # y');
console.log(ok+' موفق | '+bad+' ناموفق'); process.exitCode=bad?1:0;
