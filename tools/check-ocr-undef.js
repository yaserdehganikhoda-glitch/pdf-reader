#!/usr/bin/env node
/* نگهبان OCR: شناسهٔ تعریف‌نشده در کد Worker را پیش از بیلد پیدا می‌کند (مثل «LX is not defined» یا «window is not defined»).
   استفاده:  npm i acorn   سپس   node check-ocr-undef.js www/index.html
   خروج 1 = یافتهٔ تعریف‌نشده؛ خروج 0 = سالم. متن Worker دقیقاً مثل ocrwSpawn ساخته می‌شود. */
'use strict';
var fs = require('fs'), acorn;
try { acorn = require('acorn'); } catch (e) { console.error('acorn نصب نیست: npm i acorn'); process.exit(2); }

var file = process.argv[2] || 'www/index.html';
var html = fs.readFileSync(file, 'utf8');
var re = /<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi, m, big = '';
while ((m = re.exec(html))) if (m[1].length > big.length) big = m[1];   /* اسکریپت اصلی برنامه */

var ast = acorn.parse(big, { ecmaVersion: 'latest', locations: true });
var fns = {};
(function walk(n) {
  if (!n || typeof n.type !== 'string') return;
  if (n.type === 'FunctionDeclaration' && n.id && !fns[n.id.name]) fns[n.id.name] = big.slice(n.start, n.end);
  for (var k in n) { if (k === 'loc') continue; var c = n[k]; if (Array.isArray(c)) c.forEach(walk); else if (c && typeof c.type === 'string') walk(c); }
})(ast);

var parts = ['stripKashida', 'ocrPurgeMk', 'ocrLines', 'ocrCluster', 'ocrOrder', 'ocrText'], need = parts.concat(['paddleWGlue', 'wkBoot']), i;
for (i = 0; i < need.length; i++) if (!fns[need[i]]) { console.error('تابع پیدا نشد (نام عوض شده؟): ' + need[i]); process.exit(2); }
var src = parts.map(function (n) { return fns[n]; }).join('\n') + '\nvar ocrPurge = ocrPurgeMk();var H = {};(' + fns.paddleWGlue + ')();(' + fns.wkBoot + ')();';

/* سراسری‌های مجاز در DedicatedWorker (window/document/localStorage عمداً نیستند) */
var G = new Set(('self console caches fetch crypto performance WebAssembly TextDecoder TextEncoder OffscreenCanvas ImageData Response Request Headers URL Blob ' +
  'setTimeout clearTimeout setInterval clearInterval postMessage importScripts indexedDB navigator location Atomics SharedArrayBuffer ArrayBuffer DataView ' +
  'Uint8Array Int8Array Uint16Array Int16Array Uint32Array Int32Array Float32Array Float64Array Uint8ClampedArray BigInt64Array Object Array String Number Boolean ' +
  'Symbol Math JSON Date RegExp Error TypeError RangeError SyntaxError Promise Map Set WeakMap WeakSet Proxy Reflect Intl parseInt parseFloat isNaN isFinite ' +
  'undefined NaN Infinity encodeURIComponent decodeURIComponent atob btoa createImageBitmap ImageBitmap MessageChannel MessagePort structuredClone queueMicrotask ' +
  'globalThis eval arguments Function BigInt DOMException AbortController ort').split(/\s+/));

var wast = acorn.parse(src, { ecmaVersion: 'latest', locations: true, allowReturnOutsideFunction: true });
var bad = {};

function Scope(p) { this.p = p; this.v = new Set(); }
Scope.prototype.has = function (n) { for (var s = this; s; s = s.p) if (s.v.has(n)) return true; return false; };
function declPat(p, sc) {
  if (!p) return;
  if (p.type === 'Identifier') sc.v.add(p.name);
  else if (p.type === 'ObjectPattern') p.properties.forEach(function (q) { declPat(q.type === 'RestElement' ? q.argument : q.value, sc); });
  else if (p.type === 'ArrayPattern') p.elements.forEach(function (e) { declPat(e, sc); });
  else if (p.type === 'AssignmentPattern') declPat(p.left, sc);
  else if (p.type === 'RestElement') declPat(p.argument, sc);
}
function hoist(n, sc) {                       /* var/let/const/function/class به‌صورت سهل‌گیرانه در اسکوپ تابع */
  if (!n || typeof n.type !== 'string') return;
  if (n.type === 'FunctionDeclaration') { sc.v.add(n.id.name); return; }
  if (n.type === 'FunctionExpression' || n.type === 'ArrowFunctionExpression') return;
  if (n.type === 'ClassDeclaration') { sc.v.add(n.id.name); return; }
  if (n.type === 'VariableDeclaration') n.declarations.forEach(function (d) { declPat(d.id, sc); });
  for (var k in n) { if (k === 'loc') continue; var c = n[k]; if (Array.isArray(c)) c.forEach(function (x) { hoist(x, sc); }); else if (c && typeof c.type === 'string') hoist(c, sc); }
}
function ref(n, sc) { if (sc.has(n.name) || G.has(n.name)) return; (bad[n.name] || (bad[n.name] = [])).push(n.loc.start.line); }
function pat(p, sc) {
  if (!p) return;
  if (p.type === 'AssignmentPattern') { pat(p.left, sc); visit(p.right, sc); }
  else if (p.type === 'ObjectPattern') p.properties.forEach(function (q) { if (q.type === 'RestElement') pat(q.argument, sc); else { if (q.computed) visit(q.key, sc); pat(q.value, sc); } });
  else if (p.type === 'ArrayPattern') p.elements.forEach(function (e) { pat(e, sc); });
  else if (p.type === 'RestElement') pat(p.argument, sc);
  else if (p.type === 'MemberExpression') visit(p, sc);
}
function visit(n, sc) {
  if (!n) return;
  var s, k, c;
  switch (n.type) {
    case 'Program': s = new Scope(null); n.body.forEach(function (b) { hoist(b, s); }); n.body.forEach(function (b) { visit(b, s); }); return;
    case 'FunctionDeclaration': case 'FunctionExpression': case 'ArrowFunctionExpression':
      s = new Scope(sc); if (n.type === 'FunctionExpression' && n.id) s.v.add(n.id.name); s.v.add('arguments');
      n.params.forEach(function (p) { declPat(p, s); }); n.params.forEach(function (p) { pat(p, s); });
      if (n.body.type === 'BlockStatement') { n.body.body.forEach(function (b) { hoist(b, s); }); n.body.body.forEach(function (b) { visit(b, s); }); } else visit(n.body, s);
      return;
    case 'ClassDeclaration': case 'ClassExpression':
      s = new Scope(sc); if (n.id) s.v.add(n.id.name); visit(n.superClass, s);
      n.body.body.forEach(function (q) { if (q.computed) visit(q.key, s); if (q.value) visit(q.value, s); });
      return;
    case 'CatchClause': s = new Scope(sc); declPat(n.param, s); n.body.body.forEach(function (b) { hoist(b, s); }); visit(n.body, s); return;
    case 'ForStatement': case 'ForInStatement': case 'ForOfStatement':
      s = new Scope(sc); k = n.init || n.left; if (k && k.type === 'VariableDeclaration') k.declarations.forEach(function (x) { declPat(x.id, s); });
      ['init', 'test', 'update', 'left', 'right', 'body'].forEach(function (f) {
        if (!n[f]) return;
        if (n[f].type === 'VariableDeclaration') n[f].declarations.forEach(function (x) { pat(x.id, s); visit(x.init, s); }); else visit(n[f], s);
      });
      return;
    case 'VariableDeclaration': n.declarations.forEach(function (d) { pat(d.id, sc); visit(d.init, sc); }); return;
    case 'Identifier': ref(n, sc); return;
    case 'MemberExpression': visit(n.object, sc); if (n.computed) visit(n.property, sc); return;
    case 'Property': if (n.computed) visit(n.key, sc); visit(n.value, sc); return;
    case 'PropertyDefinition': case 'MethodDefinition': if (n.computed) visit(n.key, sc); visit(n.value, sc); return;
    case 'LabeledStatement': visit(n.body, sc); return;
    case 'BreakStatement': case 'ContinueStatement': case 'MetaProperty': return;
    case 'UnaryExpression': if (n.operator === 'typeof' && n.argument.type === 'Identifier') return; break;
  }
  for (k in n) { if (k === 'type' || k === 'loc' || k === 'start' || k === 'end') continue; c = n[k]; if (Array.isArray(c)) c.forEach(function (x) { if (x && typeof x.type === 'string') visit(x, sc); }); else if (c && typeof c.type === 'string') visit(c, sc); }
}
visit(wast, null);

var names = Object.keys(bad);
if (names.length) {
  console.error('✗ شناسهٔ تعریف‌نشده در Worker OCR (خط نسبی در متن Worker):');
  names.forEach(function (k) { console.error('  ' + k + ' ← ' + bad[k].slice(0, 12).join(', ')); });
  process.exit(1);
}
console.log('✓ Worker OCR: شناسهٔ تعریف‌نشده ندارد (' + src.split('\n').length + ' خط بررسی شد)');
