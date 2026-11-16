// tools/hub-scopecheck.js — بررسی ایستای دید شناسه‌ها (هر ارجاع باید در زنجیرهٔ scope اعلان داشته باشد). اجرا: node --expose-internals tools/hub-scopecheck.js index.html poRestAt svPzTx ...
// (acorn داخلی node؛ با acorn نصب‌شده، خط require را به require('acorn') و require('acorn-walk') عوض کنید.)
// بررسی ایستای دید شناسه‌ها: هر ارجاع به نام‌های هدف باید در زنجیرهٔ scope خودش اعلان داشته باشد
const fs=require('fs');const acorn=require('internal/deps/acorn/acorn/dist/acorn');const walk=require('internal/deps/acorn/acorn-walk/dist/walk');
const html=fs.readFileSync(process.argv[2],'utf8');const names=process.argv.slice(3);
const scripts=[...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m=>m[1]);
const src=scripts.find(s=>s.includes('function chunkAt('));
const ast=acorn.parse(src,{ecmaVersion:'latest',locations:true});
function declsOf(node){const d=new Set();
  (function w(n,top){ if(!n||typeof n.type!=='string')return;
    if(n.type==='FunctionDeclaration'){ if(n.id)d.add(n.id.name); return; }   // اعلان تابع: فقط نام در همین scope
    if(n.type==='FunctionExpression'||n.type==='ArrowFunctionExpression')return;
    if(n.type==='VariableDeclarator'){ (function ids(p){ if(p.type==='Identifier')d.add(p.name); else if(p.type==='ObjectPattern')p.properties.forEach(q=>ids(q.value||q.argument)); else if(p.type==='ArrayPattern')p.elements.forEach(q=>q&&ids(q)); else if(p.type==='AssignmentPattern')ids(p.left); else if(p.type==='RestElement')ids(p.argument);})(n.id); }
    for(const k in n){ const v=n[k]; if(Array.isArray(v))v.forEach(c=>w(c)); else if(v&&typeof v.type==='string')w(v); }
  })(node.body && node.body.type? node.body : node);
  if(node.params)node.params.forEach(p=>{(function ids(q){ if(q.type==='Identifier')d.add(q.name); else if(q.type==='AssignmentPattern')ids(q.left);})(p);});
  return d; }
const cache=new Map();const scopeOf=(n)=>{ if(!cache.has(n))cache.set(n,declsOf(n)); return cache.get(n); };
const res={};names.forEach(n=>res[n]={decl:[],refs:0,bad:[]});
walk.fullAncestor(ast,(node,st,anc)=>{
  if(node.type==='Identifier'&&names.includes(node.name)){
    const par=anc[anc.length-2];
    if(par&&par.type==='FunctionDeclaration'&&par.id===node){ res[node.name].decl.push(node.loc.start.line); return; }
    if(par&&par.type==='MemberExpression'&&par.property===node&&!par.computed)return;
    if(par&&par.type==='Property'&&par.key===node&&!par.computed&&!par.shorthand)return;
    res[node.name].refs++;
    let ok=false;for(let i=anc.length-1;i>=0;i--){const a=anc[i];if(a.type==='Program'||a.type.startsWith('Function')||a.type==='ArrowFunctionExpression'){ if(scopeOf(a).has(node.name)){ok=true;break;} }}
    if(!ok)res[node.name].bad.push(node.loc.start.line);
  }
});
let bad=0;for(const n of names){const r=res[n];console.log(n.padEnd(11),'declared@',r.decl.join(','),'refs',r.refs,r.bad.length?('NOT VISIBLE at lines '+r.bad.join(',')):'visible ✓'); if(r.bad.length||!r.decl.length)bad++;}
process.exit(bad?1:0);
