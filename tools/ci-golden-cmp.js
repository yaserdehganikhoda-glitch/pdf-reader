// tools/ci-golden-cmp.js — خروجی ttsText/emoTag را روی همهٔ آیتم‌های golden-fa.json بین دو نسخهٔ index.html مقایسه می‌کند.
// اجرا (از ریشهٔ مخزن): node tools/ci-golden-cmp.js index-قدیمی.html index.html
const fs=require('fs'),path=require('path');const L=require(path.join(__dirname,'load.js'));
const g=JSON.parse(fs.readFileSync(path.join(__dirname,'golden-fa.json'),'utf8'));
function run(file){
  const X=L(file); X.S.fixOn=1;
  const emoTag=X.ev('typeof emoTag!=="undefined"?emoTag:null');
  const out={hom:[],num:[],ez:[],emo:[],emoh:[]};
  const tt=(s)=>{try{return X.ttsText(s,'device')}catch(e){return 'ERR '+e.message}};
  g.hom.forEach(x=>out.hom.push(tt(x.s))); g.num.forEach(x=>out.num.push(tt(x.s))); g.ez.forEach(s=>out.ez.push(tt(s.replace(/~/g,''))));
  const lab=(s)=>{try{const r=emoTag(s);return r?r.e+':'+r.k:'null'}catch(e){return 'ERR '+e.message}};
  g.emo.forEach(x=>out.emo.push(lab(x.s))); g.emo_holdout.forEach(x=>out.emoh.push(lab(x.s)));
  return out;
}
const A=run(process.argv[2]), B=run(process.argv[3]||'index.html');
let diffs=0;
for(const k of Object.keys(A)){
  A[k].forEach((v,i)=>{
    if(v!==B[k][i]){
      diffs++;
      const src=(k==='ez'?g.ez[i]:(k==='emoh'?g.emo_holdout[i]:g[k][i]));
      console.log('DIFF',k,i,JSON.stringify(src).slice(0,90),'\n   r239:',String(v).slice(0,90),'\n   r243:',String(B[k][i]).slice(0,90));
    }
  });
}
let kdiff=0;['emo','emoh'].forEach(k=>A[k].forEach((v,i)=>{if(v.split(':')[0]===B[k][i].split(':')[0]&&v!==B[k][i])kdiff++;}));
console.log('(از DIFFها: فقط قدرت k فرق دارد، برچسب یکی است:',kdiff,')');
console.log('items compared:',Object.values(A).reduce((a,b)=>a+b.length,0),' differences:',diffs);
// emo macro-F1 for both (labels from emoTag e)
function macro(out,gold){const cls=[...new Set(gold.map(x=>x.e))];let f=0;cls.forEach(c=>{let tp=0,fp=0,fn=0;gold.forEach((x,i)=>{const p=(out[i]||'').split(':')[0];if(p===c&&x.e===c)tp++;else if(p===c)fp++;else if(x.e===c)fn++;});const pr=tp/Math.max(1,tp+fp),rc=tp/Math.max(1,tp+fn);f+=pr+rc?2*pr*rc/(pr+rc):0;});return (f/cls.length).toFixed(3)+' ('+cls.length+' classes)';}
console.log('emo macroF1  r239:',macro(A.emo,g.emo),' r243:',macro(B.emo,g.emo));
console.log('emo holdout  r239:',macro(A.emoh,g.emo_holdout),' r243:',macro(B.emoh,g.emo_holdout));
