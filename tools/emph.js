// آزمون شکست تأکید و سلامت هایلایت: node emph.js ../index.html
const fs=require('fs'),vm=require('vm'),assert=require('assert');const h=fs.readFileSync(process.argv[2],'utf8');
const X=require('./load.js')(process.argv[2]);X.S.fixOn=1;
const code=h.slice(h.indexOf('function emoSplit('),h.indexOf('window.emoVoiceK'));
let lvl=2;const ctx={sents:[],rsm:null,emoLvl:()=>lvl,emoEmph:X.ev('emoEmph'),ttsText:(t,a,b,c)=>X.ttsText(t,a,b,c),spk:o=>o.text,console};
vm.createContext(ctx);vm.runInContext(code+';this.emoSplit=emoSplit;this.emoFoldMarks=emoFoldMarks;this.emoPartW=emoPartW;',ctx);
let n=0;const ok=(c,m)=>{assert(c,m);n++};
// ۱) تشخیص
const E=X.ev('emoEmph');
ok(E('او امروز *واقعاً* خیلی دوستش داشت.').m==='واقعاً','star');
ok(E('او امروز «واقعاً» خیلی دوستش داشت.').m==='واقعاً','quote');
ok(E('او امروز واقعاً خیلی دوستش داشت.')===null,'none');
ok(E('*واقعاً* او امروز خیلی دوستش داشت.')===null,'first word');
ok(E('او امروز خیلی دوستش *داشت*.')===null,'last word');
ok(E('او «بله» گفت و «نه» شنید و رفت.')===null,'two candidates');
ok(E('او *خیلی* و *واقعاً* دوستش داشت.')===null,'two stars');
ok(E('کوتاه *است* این.')===null,'short');
// ۲) شکست
const q={t:'x',s:1,g:0.3,n:0.6};
ctx.sents=[{text:'او امروز *واقعاً* خیلی دوستش داشت.',cont:false}];
let sp=ctx.emoSplit(0,q);ok(sp&&sp.length===3,'3 parts');
ok(sp[0].g===0.08&&sp[1].g===0.08&&sp[2].g===0.3,'gaps');ok(sp[1].s===0.9&&sp[0].s===1&&sp[2].s===1,'speeds');
ok(sp.every(p=>p.n===0.6),'noise carried');ok(sp.every(p=>/[\u0621-\u06D3]/.test(p.t)),'letters');
lvl=1;ok(ctx.emoSplit(0,q)===null,'only expressive');lvl=2;ctx.rsm={i:0};ok(ctx.emoSplit(0,q)===null,'resume skip');ctx.rsm=null;
// ۳) مرزها: ۳ جمله، جملهٔ وسط شکسته (۳ part) ⇒ ۵ part
const pm=[0,1,1,1,2],c={start:10,end:12,pm,parts:[{t:'aaaa',g:0.3},{t:'bb',g:0.08},{t:'cc',g:0.08},{t:'dddd',g:0.3},{t:'eeeeee',g:0.3}]};
ctx.sents=[];for(let k=0;k<13;k++)ctx.sents.push({text:'جمله '+k});
const mk=[0,0.2,0.3,0.4,0.7];const f=ctx.emoFoldMarks(mk,pm,3);
ok(JSON.stringify(f)==='[0,0.2,0.7]','fold marks: '+JSON.stringify(f));
ok(ctx.emoFoldMarks([0,0.5,0.9],[0,1,2],3)!==null,'no split passthrough');
ok(ctx.emoFoldMarks([0,0.5],[0,1,1],3)===null,'mismatch → fallback');
ok(ctx.emoFoldMarks([0,0.5,0.9],null,3).length===3,'pm null with n marks');
// وزن: جملهٔ میانی = مجموع سه part + دو مکث درونی + مکث پایانی؛ بدون pm فرمول قبلی
const cps=14;
ok(Math.abs(ctx.emoPartW(c,1,cps,true)-(2+0.08*cps+2+0.08*cps+4+0.3*cps))<1e-9,'weight mid');
ok(Math.abs(ctx.emoPartW(c,2,cps,false)-6)<1e-9,'weight last');
const c2={start:10,end:11,pm:null,parts:[{t:'abc',g:0.5},{t:'de',g:0.1}]};
ok(ctx.emoPartW(c2,0,cps,true)===3+0.5*cps&&ctx.emoPartW(c2,1,cps,false)===2,'legacy formula');
// ۴) شبیه‌سازی هایلایت: جملهٔ فعال بر پایهٔ marks تاشده برابر با marks جمله‌ای است
function idxAt(m,f){let lo=0,hi=m.length-1;while(lo<hi){const mid=(lo+hi+1)>>1;if(m[mid]<=f)lo=mid;else hi=mid-1}return lo}
for(let t=0;t<=100;t++){const fr=t/100;const a=idxAt(f,fr);const b=idxAt([0,0.2,0.7],fr);ok(a===b,'idx '+fr)}
console.log('emph OK',n,'assertions');
// ۵) chunkAt واقعی با stub: ناوردا‌ها
{
  const s0=h.indexOf('function chunkAt('),s1=h.indexOf('/* نشانهٔ نسخه (APP_BUILD)');
  const cc={sents:[],rsm:null,mRate:()=>1,paceSpeed:()=>1,pauseMs:()=>300,emoPar:()=>null,emoLvl:()=>2,emoEmph:X.ev('emoEmph'),ttsText:(t,a,b,c)=>X.ttsText(t,a,b,c),spk:o=>o.text,Math,String,console};
  vm.createContext(cc);
  vm.runInContext(h.slice(h.indexOf('function emoSplit('),h.indexOf('function emoFoldMarks('))+h.slice(s0,s1)+';this.chunkAt=chunkAt;',cc);
  const T=['اولین جمله ساده است.','او امروز *واقعاً* خیلی دوستش داشت.','جملهٔ سوم هم ساده است.'];
  cc.sents=T.map((x,i)=>({text:x,page:1,pe:false,cont:false}));
  let c=cc.chunkAt(0,500);
  ok(c.end===2&&c.pm&&c.parts.length===c.pm.length&&c.parts.length===5,'chunk split: '+c.parts.length);
  ok(c.pm.every((v,i)=>i===0||v>=c.pm[i-1]),'monotonic');ok([0,1,2].every(j=>c.pm.includes(j)),'cover');
  ok(c.text.split(' ').length>8,'text intact');
  cc.sents=T.map((x,i)=>({text:x.replace(/\*/g,''),page:1,pe:false,cont:false}));
  c=cc.chunkAt(0,500);ok(c.pm===null&&c.parts.length===3,'no-split legacy shape');
  cc.emoLvl=()=>1;cc.sents=T.map((x)=>({text:x,page:1,pe:false,cont:false}));c=cc.chunkAt(0,500);ok(c.pm===null&&c.parts.length===3,'natural level never splits');
  console.log('chunkAt invariants OK');
}
