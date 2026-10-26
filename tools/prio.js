// اولویت واژه‌نامهٔ کاربر بر LEX/HOM و گیت کسره به‌ازای هر صدا: node prio.js ../index.html
const fs=require('fs'),vm=require('vm'),assert=require('assert');const h=fs.readFileSync(process.argv[2],'utf8');
const X=require('./load.js')(process.argv[2]);X.S.fixOn=1;let n=0;const ok=(c,m)=>{assert(c,m);n++};
X.ev('dictG='+JSON.stringify('مهر = مهرین\nشکر = شاکِر'));X.ev('parseDict()');
ok(/^مهرین مادری/.test(X.ttsText('مهر مادری زیباست.')),'dict beats LEX/HOM (مهر)');
ok(/شاکِر/.test(X.ttsText('قاشق شکر ریخت.')),'dict beats HOM cue (شکر)');
ok(/گُل/.test(X.ttsText('گل سرخ.')),'non-dict words unaffected');
const m=h.match(/function ezOn\(\)\{[^\n]*\}\s*\/\*/)||h.match(/function ezOn\(\)\s*\{[^\n]*?\}(?=   \/\*)/);
ok(m,'ezOn found');
const st={ezMod:''},cx={sget:(k,d)=>st[k]!==undefined?st[k]:d,currentVoice:()=>({id:'v2'})};vm.createContext(cx);
vm.runInContext(m[0].replace(/\/\*$/,'')+';this.f=ezOn',cx);
st.ezMod='';ok(cx.f()===false,'off');st.ezMod='0';ok(cx.f()===false,'0');st.ezMod='1';ok(cx.f()===true,'all');
st.ezMod='v1,v2';ok(cx.f()===true,'listed voice');st.ezMod='v1,v3';ok(cx.f()===false,'unlisted voice');
console.log('prio OK',n);
