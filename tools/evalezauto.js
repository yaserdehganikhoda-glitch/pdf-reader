// سنجش Golden ez روی ezAuto (جایگزین evalez.js که ezApply حذف‌شده را می‌سنجید). node evalezauto.js ../index.html [--v]
const X=require('./load.js')(process.argv[2]);X.S.fixOn=1;
const G=JSON.parse(require('fs').readFileSync(__dirname+'/golden-fa.json','utf8')).ez;
const ez=X.ev('ezAuto');let tp=0,fp=0,fn=0,bad=[];
G.forEach(g=>{const toks=g.split(' ');const gold=toks.map(t=>t.endsWith('~'));const clean=toks.map(t=>t.replace(/~$/,'')).join(' ');
 const out=ez(clean).split(' ');
 if(out.length!==toks.length){bad.push('LEN '+clean+' → '+out.join(' '));return}
 out.forEach((o,i)=>{const b=toks[i].replace(/~$/,'');const p=o!==b;if(p&&gold[i])tp++;else if(p&&!gold[i]){fp++;bad.push('FP '+clean+' @'+b)}else if(!p&&gold[i]){fn++;bad.push('FN '+clean+' @'+b)}})});
const P=tp/(tp+fp||1),R=tp/(tp+fn||1);
console.log(JSON.stringify({n:G.length,tp,fp,fn,P:+P.toFixed(3),R:+R.toFixed(3),F1:+(2*P*R/(P+R||1)).toFixed(3)}));
if(process.argv.includes('--v'))console.log(bad.join('\n'));
