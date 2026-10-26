const X=require('./load.js')(process.argv[2]);X.S.fixOn=1;
const G=JSON.parse(require('fs').readFileSync(__dirname+'/golden-fa.json','utf8')).ez;
const split=(a,b)=>[G.slice(a,b)];
function run(list){let tp=0,fp=0,fn=0,bad=[];
 list.forEach(g=>{const toks=g.split(' ');const gold=toks.map(t=>t.endsWith('~'));const clean=toks.map(t=>t.replace(/~$/,'')).join(' ');
  const out=X.ev('ezApply')(X.ev('lexApply')(clean)).split(' ');const base=X.ev('lexApply')(clean).split(' ');
  out.forEach((o,i)=>{const p=o!==base[i];if(p&&gold[i])tp++;else if(p&&!gold[i]){fp++;bad.push('FP '+clean+' @'+base[i])}else if(!p&&gold[i]){fn++;bad.push('FN '+clean+' @'+base[i])}})});
 const P=tp/(tp+fp||1),R=tp/(tp+fn||1);return{tp,fp,fn,P:+P.toFixed(3),R:+R.toFixed(3),F1:+(2*P*R/(P+R||1)).toFixed(3),bad}}
const r=run(G);console.log(JSON.stringify({tp:r.tp,fp:r.fp,fn:r.fn,P:r.P,R:r.R,F1:r.F1}));console.log(r.bad.join('\n'));
