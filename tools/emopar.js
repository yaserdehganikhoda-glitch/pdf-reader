// آزمون emoPar با stub: node emopar.js ../index.html
const fs=require('fs'),vm=require('vm');const h=fs.readFileSync(process.argv[2],'utf8');
const X=require('./load.js')(process.argv[2]);
const a=h.indexOf('var emoForce = null'),b=h.indexOf('window.emoVoiceK');
const code=h.slice(a,b);
const store={emoLvl:'2',emoVK:'{}'};
const ctx={sents:[],sget:(k,d)=>store[k]!==undefined?store[k]:d,currentVoice:()=>({id:'gyro'}),mRate:()=>1,emoTag:X.ev('emoTag'),window:{},console,Math,JSON};
vm.createContext(ctx);vm.runInContext(code+';this.emoPar=emoPar;this.emoOne=emoOne;',ctx);
const txt=['او آرام وارد شد.','«وای! این چیست؟!» فریاد زد.','با ترس به عقب رفت و لرزید.','اشک در چشمانش جمع شد…','سپس همه چیز تمام شد.','آیا شما کجا می‌روید؟','هوا ابری است.'];
ctx.sents=txt.map((t,i)=>({text:t,page:1,pe:i===txt.length-1}));
function show(lv){store.emoLvl=lv;console.log('lvl',lv);txt.forEach((t,i)=>{const r=ctx.emoPar(i);console.log(i,JSON.stringify(r),'|',X.ev('emoTag')(t).e)})}
show('0');show('1');show('2');
let mx=0,mn=9,pm=0;const big=[];for(let i=0;i<400;i++)big.push({text:txt[i%txt.length]+' '+i,page:1+(i>>6),pe:i%9===8});ctx.sents=big;
let t0=process.hrtime.bigint();for(let i=0;i<big.length;i++){const r=ctx.emoPar(i);if(r){mx=Math.max(mx,r.ls);mn=Math.min(mn,r.ls);pm=Math.max(pm,r.pad||0)}}
console.log('ls range',mn,mx,'maxPad s',pm.toFixed(3),'ms/call',(Number(process.hrtime.bigint()-t0)/1e6/big.length).toFixed(3));
