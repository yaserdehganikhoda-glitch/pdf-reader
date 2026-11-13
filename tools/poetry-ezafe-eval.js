/* ارزیاب علامت‌گذاری کسرهٔ اضافه: node tools/poetry-ezafe-eval.js [index.html] [1=نمایش خطاها]. حقیقت‌زمینی: اعرابِ دیتاست ۲٫۱ (۲۱۴ مصرع، ۲۱۱ اضافه) */
const fs=require('fs'),vm=require('vm');
const html=process.argv[2]||require('path').join(__dirname,'..','index.html');const h=fs.readFileSync(html,'utf8'),a=h.indexOf('var PO_MT ='),b=h.indexOf('function ttsPo(s, f){');
const c={window:{},Date,sents:[],docName:'g',poV2:()=>1,sget:()=>'1',document:{getElementById:()=>null}};
vm.createContext(c);vm.runInContext(h.slice(a,b)+';this.poScan=poScan;this.PO_MT=PO_MT;',c);

const G=JSON.parse(fs.readFileSync(require('path').join(__dirname,'golden-ezafe.json'),'utf8')),P=JSON.parse(fs.readFileSync(require('path').join(__dirname,'golden-poetry.json'),'utf8')).prose.map(x=>x.text);
const pg={};c.sents=G.map(o=>({text:o.text,po:true,page:(pg[o.src]=pg[o.src]||Object.keys(pg).length+1)}));
let tp=0,fp=0,tr=0,ch=0;const bad=[];
G.forEach((o,k)=>{const out=c.poScan(k);const A=o.text.split(' '),B=out.split(' ');if(out!==o.text)ch++;
 A.forEach((w,i)=>{const mk=B[i]!==w;if(o.ez[i])tr++;if(mk){if(o.ez[i])tp++;else{fp++;bad.push(o.text+' ['+w+']')}}})});
c.sents=P.map(t=>({text:t,po:true,page:1}));let pc=0;P.forEach((t,k)=>{if(c.poScan(k)!==t)pc++});
console.log('ezafe marks: TP',tp,'FP',fp,'truth',tr,'precision',(tp/Math.max(1,tp+fp)).toFixed(2),'recall',(tp/tr).toFixed(2),'| lines changed',ch+'/'+G.length,'| prose changed',pc+'/'+P.length);
if(process.argv[3]==='1')bad.slice(0,6).forEach(x=>console.log('  FP',x));
