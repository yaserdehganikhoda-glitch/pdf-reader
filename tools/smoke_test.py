#!/usr/bin/env python3
"""تست دودی خودکار index.html با Chromium بدون سر.
اجرا:  python3 tools/smoke_test.py [پوشهٔ پروژه]     (نیاز: pip install playwright && playwright install chromium)
خروجی: گزارش خطاهای JS، درخواست‌های شکست‌خورده، و نتیجهٔ کلیک‌زدن روی همهٔ دکمه‌های قابل‌مشاهده. کد خروج ≠۰ = خطا."""
import asyncio, os, sys, threading, functools, http.server, json, subprocess, re
from playwright.async_api import async_playwright
ROOT = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else '.')
PORT = 8791
IGNORE = re.compile(r'fonts/fonts\.css|favicon|Failed to load resource.*404')   # منابع اختیاری
PDF = b"%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 300 300]/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>>endobj\n4 0 obj<</Length 63>>stream\nBT /F1 18 Tf 20 200 Td (Hello world. This is a smoke test.) Tj ET\nendstream endobj\n5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj\ntrailer<</Root 1 0 R/Size 6>>\n%%EOF"

def syntax():
    s = open(os.path.join(ROOT, 'index.html'), encoding='utf-8').read()
    bad = 0
    for i, b in enumerate(re.findall(r'<script(?![^>]*\bsrc=)[^>]*>(.*?)</script>', s, re.S)):
        if len(b) < 50: continue
        p = f'/tmp/_smoke_{i}.js'; open(p, 'w', encoding='utf-8').write(b)
        r = subprocess.run(['node', '--check', p], capture_output=True, text=True)
        if r.returncode: bad += 1; print('SYNTAX', i, r.stderr[:300])
    return bad

async def main():
    class Q(http.server.SimpleHTTPRequestHandler):
        def log_message(self, *a): pass
    srv = http.server.ThreadingHTTPServer(('127.0.0.1', PORT), functools.partial(Q, directory=ROOT))
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    errs, bad_req = [], []
    async with async_playwright() as p:
        b = await p.chromium.launch(args=['--no-sandbox'])
        pg = await b.new_page(viewport={'width': 390, 'height': 800})
        pg.on('pageerror', lambda e: errs.append('PAGEERROR ' + str(e)[:300]))
        pg.on('console', lambda m: errs.append('CONSOLE ' + m.text[:300]) if m.type == 'error' and not IGNORE.search(m.text) else None)
        pg.on('requestfailed', lambda r: bad_req.append(r.url))
        await pg.goto(f'http://127.0.0.1:{PORT}/index.html'); await pg.wait_for_timeout(2000)
        open('/tmp/_t.pdf', 'wb').write(PDF)
        await pg.set_input_files('#file', '/tmp/_t.pdf'); await pg.wait_for_timeout(4000)
        body = await pg.inner_text('body')
        print('PDF opened:', 'جمله' in body)
        if 'نمایش صفحه ممکن نشد' in body: errs.append('PAGEVIEW نمایش صفحه ممکن نشد')
        # کلیک روی همهٔ دکمه‌ها (بیشینه ۲۰۰) با بستن دیالوگ‌ها
        n = 0; skipped = ('خروج', 'پاک', 'حذف')
        for rnd in range(3):
            btns = await pg.query_selector_all('button, [role=tab], .tab, summary')
            for el in btns:
                if n >= 200: break
                try:
                    if not await el.is_visible(): continue
                    t = (await el.inner_text())[:20]
                    if any(k in t for k in skipped): continue
                    await el.click(timeout=800, no_wait_after=True); n += 1
                    await pg.wait_for_timeout(60)
                except Exception: pass
                await pg.keyboard.press('Escape')
        print('clicked:', n)
        await pg.wait_for_timeout(500)
        await b.close()
    sb = syntax()
    print('syntax errors:', sb); print('JS errors:', len(errs)); [print(' ', e) for e in sorted(set(errs))[:30]]
    real = [u for u in bad_req if not IGNORE.search(u)]
    if real: print('failed requests:', sorted(set(real))[:15])
    sys.exit(1 if (sb or errs) else 0)
asyncio.run(main())
