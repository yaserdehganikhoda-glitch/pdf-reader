// اندازه‌گیری Golden Set: همنگاشت، عدد، کسره. استفاده: node g2p.js ../index.html [--v]
const X=require('./load.js')(process.argv[2]);X.S.fixOn=1;X.S.fixHi=1;X.S.fixPro=1;
const G=JSON.parse(require('fs').readFileSync(__dirname+'/golden-fa.json','utf8'));const V=process.argv.includes('--v');
const strip=w=>w.replace(/[\u064B-\u0652\u200c.,;:!?]/g,'');
let hOk=0,hBad=[];
G.hom.forEach(g=>{const o=X.ttsText(g.s).split(' ');const t=o.find(x=>strip(x)===strip(g.w));const got=t?t.replace(/[.,;:!?]/g,''):'(یافت نشد)';
 if(got===g.e)hOk++;else hBad.push(g.s+'  → '+got+'  (انتظار '+g.e+')')});
let nOk=0,nBad=[];
G.num.forEach(g=>{const o=X.ttsText(g.s);if(o.indexOf(g.e)>=0)nOk++;else nBad.push(g.s+' → '+o+' (انتظار «'+g.e+'»)')});
let t0=process.hrtime.bigint();for(let i=0;i<200;i++)X.ttsText(G.hom[i%G.hom.length].s);const ms=Number(process.hrtime.bigint()-t0)/1e6/200;
console.log(JSON.stringify({hom:{n:G.hom.length,ok:hOk,acc:+(hOk/G.hom.length).toFixed(3)},num:{n:G.num.length,ok:nOk,acc:+(nOk/G.num.length).toFixed(3)},msPerSentence_node:+ms.toFixed(3)}));
if(V){console.log('--hom');console.log(hBad.join('\n'));console.log('--num');console.log(nBad.join('\n'))}
