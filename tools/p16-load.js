// tools/p16-load.js — اجرای IIFE اصلی index.html در vm با DOM ساختگی و دریچهٔ eval (فقط برای ممیزی؛ جزو پروژه نیست)
// harness: runs the main IIFE of index.html in vm with a permissive DOM proxy; exposes internals via __X
const fs=require('fs'),vm=require('vm');
const src=fs.readFileSync(process.env.INDEX||process.argv[2]||'index.html','utf8');const L=src.split('\n');
// main IIFE = lines 2760..15658 (1-based)
let a0=L.findIndex(l=>l.includes("APP_BUILD = '"));let st=a0;while(st>0&&L[st].trim()!=='<script>')st--;let en=L.findIndex((l,i)=>i>a0&&l.trim()==='</script>');
let code=L.slice(st+1,en).join('\n');code=code.replace('(function(){','(function(){ window.__ev=function(s){return eval(s)};',1);
const names=(process.env.EXPOSE||'').split(',').filter(Boolean);
const tail='\ntry{window.__X={'+names.map(n=>n+':(typeof '+n+'!=="undefined"?'+n+':undefined)').join(',')+'};}catch(e){window.__XERR=String(e)}\n';
const idx=code.lastIndexOf('})();');
code=code.slice(0,idx)+tail+code.slice(idx);
const store={};
function mkEl(id){const t=()=>{};const base={id,checked:false,value:'',style:new Proxy({setProperty(){},removeProperty(){}},{get:(o,k)=>k in o?o[k]:'',set(o,k,v){o[k]=v;return true}}),dataset:{},classList:{add(){},remove(){},toggle(){},contains(){return false}},children:[],childNodes:[],options:[],addEventListener(){},removeEventListener(){},appendChild(c){return c},removeChild(){},insertBefore(){},setAttribute(){},getAttribute(){return null},removeAttribute(){},querySelector(){return mkEl('q')},querySelectorAll(){return[]},getContext(){return new Proxy({},{get:()=>()=>({})})},focus(){},click(){},dispatchEvent(){return true},blur(){},select(){},remove(){},append(){},prepend(){},closest(){return null},matches(){return false},cloneNode(){return mkEl('c')},contains(){return false},replaceChildren(){},before(){},after(){},animate(){return{cancel(){},finished:Promise.resolve()}},scrollIntoView(){},getBoundingClientRect(){return{left:0,top:0,width:0,height:0,right:0,bottom:0}},textContent:'',innerHTML:''};
 return new Proxy(base,{get(o,k){if(k in o)return o[k];if(typeof k!=='string')return undefined;if(/^(parentNode|parentElement|firstChild|lastChild|nextSibling|previousSibling|firstElementChild|lastElementChild|offsetParent|content|form|labels|control|parentWindow|ownerDocument|body|head)$/.test(k))return (o[k]=mkEl(k));return undefined},set(o,k,v){o[k]=v;return true}});}
const els={};
const cfg=JSON.parse(process.env.CHK||'{}');
const document={hidden:false,body:mkEl('body'),documentElement:mkEl('html'),head:mkEl('head'),readyState:'complete',
 querySelector(s){if(s[0]==='#'){const id=s.slice(1);if(!els[id]){els[id]=mkEl(id);if(id in cfg){const v=cfg[id];if(typeof v==='boolean')els[id].checked=v;else els[id].value=v;}}return els[id];}return mkEl(s)},
 getElementById(i){return this.querySelector('#'+i)},querySelectorAll(){return[]},createElement(t){return mkEl(t)},createTextNode(){return mkEl('t')},addEventListener(){},removeEventListener(){},createDocumentFragment(){return mkEl('f')},fonts:{ready:Promise.resolve(),load(){return Promise.resolve()}}};
const win={};
const ctx={document,window:win,navigator:{userAgent:'node',language:'fa',hardwareConcurrency:4,maxTouchPoints:0,mediaSession:null,storage:{estimate:()=>Promise.resolve({})}},location:{href:'http://x/',origin:'http://x',search:'',hash:'',protocol:'http:'},
 localStorage:{getItem:k=>(k in store?store[k]:null),setItem:(k,v)=>{store[k]=String(v)},removeItem:k=>{delete store[k]},key:i=>Object.keys(store)[i],get length(){return Object.keys(store).length}},
 sessionStorage:{getItem:()=>null,setItem(){},removeItem(){}},
 console:{log(){},warn(){},error(){},info(){},debug(){}},setTimeout:(f,ms)=>0,clearTimeout(){},setInterval:()=>0,clearInterval(){},requestAnimationFrame:()=>0,cancelAnimationFrame(){},requestIdleCallback:()=>0,
 performance:{now:()=>Date.now()},Intl,fetch:()=>Promise.reject(new Error('nofetch')),indexedDB:{open(){return{}}},URL,Blob:class{},Worker:class{postMessage(){}terminate(){}},AudioContext:class{},HTMLMediaElement:{prototype:{}},EventTarget:{prototype:{}},
 matchMedia:()=>({matches:false,addEventListener(){},addListener(){}}),getComputedStyle:()=>({}),addEventListener(){},removeEventListener(){},Capacitor:undefined,MutationObserver:class{observe(){}disconnect(){}},ResizeObserver:class{observe(){}disconnect(){}},IntersectionObserver:class{observe(){}disconnect(){}},
 Event:class{constructor(){} },Audio:class{constructor(){this.style={};this.addEventListener=()=>{};this.removeEventListener=()=>{};this.pause=()=>{};this.play=()=>Promise.resolve();this.load=()=>{}}},Image:class{},FileReader:class{},XMLHttpRequest:class{},BroadcastChannel:class{postMessage(){}close(){}},crypto:{subtle:{digest:()=>Promise.resolve(new ArrayBuffer(32))},getRandomValues:a=>a},OfflineAudioContext:class{},webkitAudioContext:class{},DOMParser:class{},CSS:{supports:()=>false},Highlight:class{},TextEncoder,TextDecoder,Uint8Array,Float32Array,Int16Array,Promise,Map,Set,WeakMap,JSON,Math,Date,RegExp,Array,Object,String,Number,Error,Symbol,parseInt,parseFloat,isNaN,encodeURI,decodeURI,encodeURIComponent,decodeURIComponent,atob:(s)=>Buffer.from(s,'base64').toString('binary'),btoa:(s)=>Buffer.from(s,'binary').toString('base64')};
ctx.self=ctx;Object.assign(ctx.window,{});
vm.createContext(ctx);
// share window props with global
ctx.window=new Proxy(win,{get(o,k){return k in o?o[k]:ctx[k]},set(o,k,v){o[k]=v;ctx[k]=v;return true}});
ctx.globalThis=ctx.window;
// preload lexicon (optional)
let err=null;
try{vm.runInContext(code,ctx,{filename:'index-main.js'});}catch(e){err=e;}
module.exports={ctx,win,els,store,err,ev:(s)=>win.__ev(s)};
