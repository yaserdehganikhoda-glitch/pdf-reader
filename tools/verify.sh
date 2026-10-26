#!/bin/sh
# tools/verify.sh [index.html] — اعتبارسنجی نشانه‌های TXW، نحو اسکریپت‌ها، Golden Set (نیاز: node)
F="${1:-index.html}"; D="$(dirname "$0")"; E=0
node -e '
const fs=require("fs"),h=fs.readFileSync(process.argv[1],"utf8"),vm=require("vm");
const MK=["/*@@TXW:A*/","/*@@TXW:/A*/","/*@@TXW:B*/","/*@@TXW:/B*/"];
const ix=MK.map(m=>h.indexOf(m));
if(ix.some(i=>i<0)||!(ix[0]<ix[1]&&ix[1]<ix[2]&&ix[2]<ix[3])){console.log("FAIL نشانه‌های TXW");process.exit(1)}
console.log("OK نشانه‌های TXW");
let n=0,re=/<script(?![^>]*src=)[^>]*>([\s\S]*?)<\/script>/g,m;
while((m=re.exec(h))){try{new vm.Script(m[1]);n++}catch(e){console.log("FAIL نحو اسکریپت:",e.message);process.exit(1)}}
console.log("OK نحو",n,"اسکریپت");
const B=h.slice(ix[2],ix[3]);
if(/\bwindow\.(?!__elog)/.test(B))console.log("WARN بلوک B به window ارجاع دارد");
' "$F" || E=1
node -e '
const h=require("fs").readFileSync(process.argv[1],"utf8");let bad=0;
/* E-5(1): ارجاع به شناسه‌های حذف‌شده */
["actx","fxSrcs","setupFx","wireFx","applyFxProfile","slowAmt","FXW","fxUpgrade","phFa","lexOn"].forEach(n=>{if(new RegExp("\\b"+n+"\\b").test(h)){console.log("FAIL ارجاع به حذف‌شده:",n);bad=1}});
/* E-5(3): هر کلید CFG_DEF مصرف‌کننده دارد (کنترل یا sget) */
const m=h.match(/var CFG_DEF = (\{[^}]*\})/);if(m){const k=Object.keys(JSON.parse(m[1]));k.forEach(x=>{if(!h.includes("id=\""+x+"\"")&&!h.includes("sget(\x27"+x+"\x27")&&!h.includes("\x27"+x+"\x27")){console.log("FAIL کلید بی‌مصرف در CFG_DEF:",x);bad=1}});console.log("OK CFG_DEF",k.length,"کلید")}
if(bad)process.exit(1);console.log("OK پاک‌سازی")
' "$F" || E=1
node "$D/g2p.js" "$F" | tee /tmp/g2p.out; node -e '
const r=JSON.parse(require("fs").readFileSync("/tmp/g2p.out","utf8").split("\n")[0]);
if(r.hom.acc<0.9||r.num.acc<0.9||r.msPerSentence_node>5){console.log("FAIL آستانهٔ Golden/زمان");process.exit(1)}console.log("OK Golden")' || E=1
node "$D/evalez.js" "$F" | head -1
node "$D/evalemo.js" "$F" > /tmp/emo.out; head -1 /tmp/emo.out; node -e '
const r=JSON.parse(require("fs").readFileSync("/tmp/emo.out","utf8").split("\n")[0]);
if(r.macroF1_5<0.7||r.ms_per_sentence_node>5){console.log("FAIL آستانهٔ احساس");process.exit(1)}console.log("OK احساس")' || E=1
node "$D/evalemo.js" "$F" --holdout | head -1
node "$D/emopar.js" "$F" | tail -1
exit $E
