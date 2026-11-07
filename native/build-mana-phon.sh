#!/usr/bin/env bash
# r187: دادهٔ واج‌آرایی اختصاصی مانا را هنگام بیلد می‌گیرد (مدل ONNX کوانتیزهٔ تشخیص کسرهٔ اضافه + توکنایزر).
# استفاده: build-mana-phon.sh <پوشهٔ assets>      → <assets>/mana/ezafe/{model_quantized.onnx,tokenizer.json,VERSION}
# منبع: https://huggingface.co/abreza/persian-ezafe-albert (همان مدل مقالهٔ Piper+LCA؛ روی یک commit ثابت قفل شده است)
# اگر دانلود ناموفق باشد، بیلد ادامه می‌یابد و مانا بدون این لایه (مثل قبل) کار می‌کند؛ با MANA_PHON_STRICT=1 بیلد می‌شکند.
set -uo pipefail
ASSETS="${1:?usage: build-mana-phon.sh <assets-dir>}"
CACHE="${OFFLINE_CACHE:-$HOME/.offline-cache}"; mkdir -p "$CACHE"
REV="${MANA_EZAFE_REV:-e6614371c6cd93fca2ad2e192a978613a14d92d6}"
BASE="https://huggingface.co/abreza/persian-ezafe-albert/resolve/$REV/quantized"
OUT="$ASSETS/mana/ezafe"
fail() { echo "WARN(mana-phon): $1" >&2; rm -rf "$OUT"; [ "${MANA_PHON_STRICT:-0}" = 1 ] && exit 1; exit 0; }

get() {   # get <نام> <کمینهٔ حجم بایت>
  local name="$1" min="$2" f="$CACHE/mana-ezafe-$REV-$1"
  if [ -s "$f" ] && [ "$(stat -c %s "$f")" -ge "$min" ]; then echo "cache: $name" >&2; echo "$f"; return 0; fi
  rm -f "$f" "$f.part"
  curl -fL --retry 4 --retry-delay 3 -o "$f.part" "$BASE/$name?download=true" >&2 || { rm -f "$f.part"; return 1; }
  [ "$(stat -c %s "$f.part")" -ge "$min" ] || { rm -f "$f.part"; return 1; }
  mv "$f.part" "$f"; echo "$f"
}

M="$(get model_quantized.onnx 5000000)" || fail "دانلود model_quantized.onnx ناموفق"
T="$(get tokenizer.json 1000000)"      || fail "دانلود tokenizer.json ناموفق"
python3 - "$T" <<'PY' || fail "tokenizer.json معتبر نیست (باید Unigram باشد)"
import json, sys
j = json.load(open(sys.argv[1], encoding="utf-8"))
assert j["model"]["type"] == "Unigram" and len(j["model"]["vocab"]) > 1000
PY
mkdir -p "$OUT"
cp "$M" "$OUT/model_quantized.onnx"; cp "$T" "$OUT/tokenizer.json"; echo "$REV" > "$OUT/VERSION"
echo "mana-phon: $(du -h "$OUT/model_quantized.onnx" | cut -f1) مدل + $(du -h "$OUT/tokenizer.json" | cut -f1) توکنایزر → $OUT"
