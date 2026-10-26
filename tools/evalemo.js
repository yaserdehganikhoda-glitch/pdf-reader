// F1 برچسب احساس روی Golden Set. node evalemo.js ../index.html [--v]
const X=require('./load.js')(process.argv[2]);const SET=process.argv.includes('--holdout')?'emo_holdout':'emo';const G=JSON.parse(require('fs').readFileSync(__dirname+'/golden-fa.json','utf8'))[SET];
const emoTag=X.ev('emoTag');const C=['joy','sad','anger','fear','surprise'];const cm={};let bad=[];
const lab=e=>e;G.forEach(g=>{const p=emoTag(g.s).e;cm[g.e+'>'+p]=(cm[g.e+'>'+p]||0)+1;if(p!==g.e)bad.push(g.e+' ← '+p+' | '+g.s)});
const f1={};C.forEach(c=>{let tp=cm[c+'>'+c]||0,fp=0,fn=0;G.forEach(g=>{});Object.keys(cm).forEach(k=>{const [a,b]=k.split('>');if(b===c&&a!==c)fp+=cm[k];if(a===c&&b!==c)fn+=cm[k]});const P=tp/(tp+fp||1),R=tp/(tp+fn||1);f1[c]=+(2*P*R/(P+R||1)).toFixed(3)});
const macro=+(C.reduce((a,c)=>a+f1[c],0)/C.length).toFixed(3);
let t0=process.hrtime.bigint();for(let r=0;r<20;r++)G.forEach(g=>{X.ev('EMO_C').clear();X.ev('EMO_N=0');emoTag(g.s)});const ms=Number(process.hrtime.bigint()-t0)/1e6/20/G.length;
const ok=G.filter(g=>emoTag(g.s).e===g.e).length;
console.log(JSON.stringify({n:G.length,acc:+(ok/G.length).toFixed(3),f1,macroF1_5:macro,ms_per_sentence_node:+ms.toFixed(3)}));
if(process.argv.includes('--v'))console.log(bad.join('\n'));
