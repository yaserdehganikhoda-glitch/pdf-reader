#!/usr/bin/env node
/* P1 (بخش فونم‌ها): node psn-p1-phoneme-test.js — نیازمند espeak-ng نصب‌شده (مثلاً Termux: pkg install espeak). اجرا نشده در محیط توسعه؛ سنجش سکوت (−45dBFS) نیازمند سنتز واقعی Piper است و این‌جا نیست. */
const { spawnSync } = require('child_process'), Z = '\u200c';
const P = [['می رود', 'می' + Z + 'رود'], ['کتاب ها', 'کتاب' + Z + 'ها'], ['رفته است', 'رفته' + Z + 'است'], ['فصل1', 'فصل 1'], ['آمد ، چون', 'آمد، چون'], ['آمد چون', 'آمد، چون'], ['بنابراین او', 'بنابراین، او']];
const ph = t => { const r = spawnSync('espeak-ng', ['-v', 'fa', '-q', '--ipa=3', '--', t], { encoding: 'utf8' }); if (r.error) { console.error('espeak-ng پیدا نشد'); process.exit(2); } return r.stdout.trim().replace(/\s+/g, ' '); };
console.log('| قاعده | فونم A | فونم B | تغییر فونم؟ |\n|---|---|---|---|');
for (const [a, b] of P) { const x = ph(a), y = ph(b); console.log('| ' + a + ' ← ' + b.replace(Z, '_') + ' | ' + x + ' | ' + y + ' | ' + (x === y ? 'خیر (فقط ظاهری)' : 'بله') + ' |'); }
