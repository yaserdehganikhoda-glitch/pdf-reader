#!/usr/bin/env python3
"""دانلود یک‌باره قلم‌های فارسی (OFL) و ساخت fonts/fonts-extra.css؛ خروجی داخل ریپو می‌ماند و برنامه کاملاً آفلاین کار می‌کند.
اجرا: python3 tools/fetch_fonts.py --out fonts   (کنار fonts.css). فقط زیرمجموعهٔ arabic (شامل حروف و ارقام فارسی و نیم‌فاصله) و وزن‌های ۴۰۰ و ۷۰۰ گرفته می‌شود."""
import argparse, json, os, sys, urllib.request

# (شناسهٔ fontsource، نام family در CSS، size-adjust٪)
FONTS = [
    ("noto-sans-arabic", "Noto Sans Arabic", 100),
    ("ibm-plex-sans-arabic", "IBM Plex Sans Arabic", 100),
    ("cairo", "Cairo", 100),
    ("noto-kufi-arabic", "Noto Kufi Arabic", 100),
    ("noto-naskh-arabic", "Noto Naskh Arabic", 100),
    ("amiri", "Amiri", 105),
    ("scheherazade-new", "Scheherazade New", 100),
    ("lateef", "Lateef", 118),
    ("harmattan", "Harmattan", 105),
    ("aref-ruqaa", "Aref Ruqaa", 100),
    ("noto-nastaliq-urdu", "Noto Nastaliq Urdu", 100),
]
WEIGHTS = (400, 700)
API = "https://api.fontsource.org/v1/fonts/%s"
CDN = "https://cdn.jsdelivr.net/fontsource/fonts/%s@latest/arabic-%d-normal.woff2"


def get(url, tries=3):
    last = None
    for _ in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "fonts-fetch/1"}), timeout=60) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001
            last = e
    raise last


def woff2_url(fid, w, meta):
    try:
        return meta["variants"][str(w)]["normal"]["arabic"]["url"]["woff2"]
    except Exception:  # noqa: BLE001
        return CDN % (fid, w)


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="fonts")
    a = ap.parse_args(argv)
    sub = os.path.join(a.out, "extra")
    os.makedirs(sub, exist_ok=True)
    css, ok_fam, bad = [], 0, []
    for fid, fam, adj in FONTS:
        try:
            meta = json.loads(get(API % fid))
        except Exception:  # noqa: BLE001
            meta = {}
        got = 0
        for w in WEIGHTS:
            fn = "%s-%d.woff2" % (fid, w)
            path = os.path.join(sub, fn)
            try:
                if not (os.path.exists(path) and os.path.getsize(path) > 2048):
                    data = get(woff2_url(fid, w, meta))
                    if data[:4] != b"wOF2" or len(data) < 2048:
                        raise ValueError("not woff2")
                    with open(path, "wb") as f:
                        f.write(data)
            except Exception as e:  # noqa: BLE001
                print("SKIP %s %d: %s" % (fid, w, e))
                continue
            got += 1
            css.append('@font-face{font-family:"%s";font-weight:%d;font-style:normal;font-display:swap;size-adjust:%d%%;src:url("extra/%s") format("woff2")}' % (fam, w, adj, fn))
        if got:
            ok_fam += 1
        else:
            bad.append(fid)
        print("%s: %d/%d وزن" % (fid, got, len(WEIGHTS)))
    with open(os.path.join(a.out, "fonts-extra.css"), "w", encoding="utf-8") as f:
        f.write("/* ساخته‌شده با tools/fetch_fonts.py؛ قلم‌های OFL، آفلاین */\n" + "\n".join(css) + "\n")
    print("خانوادهٔ موفق: %d از %d%s" % (ok_fam, len(FONTS), ("؛ ناموفق: " + ", ".join(bad)) if bad else ""))
    return 0 if ok_fam else 1


if __name__ == "__main__":
    sys.exit(main())
