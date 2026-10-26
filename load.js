// بارگذاری بلوک‌های TXW از index.html در vm (همان الگوی Worker) و در معرض گذاشتن توابع
const fs=require('fs'),vm=require('vm');
module.exports=function(file){
  const h=fs.readFileSync(file,'utf8');
  const m=(a)=>h.indexOf(a);
  const MK=['/*@@TXW:A*/','/*@@TXW:/A*/','/*@@TXW:B*/','/*@@TXW:/B*/'];
  const a0=m(MK[0]),a1=m(MK[1]),b0=m(MK[2]),b1=m(MK[3]);
  const A=h.slice(a0+MK[0].length,a1),B=h.slice(b0+MK[2].length,b1);
  const pre=h.match(/var TXW_PRE = '((?:[^'\\]|\\.)*)';/)[1].replace(/\\'/g,"'").replace(/\\\\/g,'\\');
  const ovr=h.match(/var TXW_OVR = '((?:[^'\\]|\\.)*)';/)[1].replace(/\\'/g,"'").replace(/\\"/g,'"').replace(/\\\\/g,'\\');
  const src='var H={};'+pre+'\n'+A+'\n'+B+'\n'+ovr+'\n;this.__X={ev:function(s){return eval(s)},S:(typeof __S!=="undefined"?__S:null),ttsText:typeof ttsText!=="undefined"&&ttsText,ttsBase:typeof ttsBase!=="undefined"&&ttsBase,homApply:typeof homApply!=="undefined"&&homApply,lexApply:typeof lexApply!=="undefined"&&lexApply,numFa:typeof numFa!=="undefined"&&numFa,HOM_SRC:typeof HOM_SRC!=="undefined"&&HOM_SRC};';
  const ctx={console,setTimeout,clearTimeout,Math,Date,Map,Set,Object,Array,String,RegExp,JSON,Number,parseInt,parseFloat,isNaN,Promise,Symbol,Error};
  ctx.self=ctx;ctx.navigator={};ctx.location={};ctx.__SETTINGS=1;ctx.postMessage=()=>{};ctx.addEventListener=()=>{};
  vm.createContext(ctx);
  vm.runInContext(src,ctx,{filename:'txw'});
  return ctx.__X;
};
