const fs=require('fs');const s=fs.readFileSync('index.html','utf8');
const a=s.indexOf('var WD_MS = 25000'); const b=s.indexOf('var renderRaw');
if(a<0||b<0) throw new Error('wdRender snippet not found');
const code=s.slice(a,b).replace('WD_MS = 25000','WD_MS = 300');
const cx={};
const f=new Function('cx', code+'; return {wdRender, WD_MS};')(cx);
const mk=(mode)=>({render:()=>{ let cancelled=false, rej;
  const p=new Promise((res,r)=>{rej=r; if(mode==='ok') setTimeout(res,50); if(mode==='slow-ok') setTimeout(res,1000);});
  return {promise:p, cancel(){cancelled=true; rej&&rej(new Error('RenderingCancelledException'));}};}});
(async()=>{
  let r=[];
  await f.wdRender(mk('ok'),{}); r.push('ok-passes');
  const t0=Date.now(); try{ await f.wdRender(mk('hang'),{}); r.push('FAIL hang resolved'); }catch(e){ r.push('hang->'+e.message+' in '+(Date.now()-t0)+'ms'); }
  try{ await f.wdRender(mk('slow-ok'),{}); r.push('FAIL slow resolved'); }catch(e){ r.push('slow->'+e.message); }
  process.on('unhandledRejection',e=>{console.log('UNHANDLED',e.message);process.exitCode=1});
  await new Promise(r=>setTimeout(r,1500));
  console.log(r.join('\n'));
})();
