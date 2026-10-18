#!/usr/bin/env bash
# تبدیل مدل‌های PaddleOCR به ORT-format از پیش‌بهینه‌شده (برای WASM) + بررسی هم‌ارزی عددی با .onnx
# نیازمند: pip install onnx onnxruntime numpy   (روی کامپیوتر، نه گوشی)
# استفاده: bash make_ort.sh [پوشهٔ مدل‌ها = www/ocr/paddle]
set -euo pipefail
D="${1:-www/ocr/paddle}"
DET="$D/PP-OCRv5_mobile_det"
REC="$D/arabic_PP-OCRv5_mobile_rec"
for m in "$DET" "$REC"; do
  [ -f "$m.onnx" ] || { echo "پیدا نشد: $m.onnx"; exit 1; }
  # Fixed = بهینه‌سازی‌های ثابت در زمان تبدیل (مناسب WASM)؛ Runtime مخصوص EPهای بومی است و برای وب مجاز نیست
  python3 -m onnxruntime.tools.convert_onnx_models_to_ort "$m.onnx" --optimization_style Fixed
  [ -f "$m.ort" ] || { echo "خروجی .ort ساخته نشد: $m"; exit 1; }
done
python3 - "$DET" "$REC" <<'PY'
import sys, numpy as np, onnxruntime as ort
print("onnxruntime (مبدل/PC):", ort.__version__, "— باید ≤ نسخهٔ ORT-web داخل ocr/ort/ باشد وگرنه .ort بارگذاری نمی‌شود (برنامه به .onnx برمی‌گردد)")
def run(path, dyn):
    s = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    sh = [d if isinstance(d, int) and d > 0 else dyn[i] for i, d in enumerate(s.get_inputs()[0].shape)]   # کانال/ارتفاع از خود مدل خوانده می‌شود (نه ثابت ۳)
    x = np.random.default_rng(1).random(sh, dtype=np.float32) * 2 - 1
    return s.run(None, {s.get_inputs()[0].name: x})[0], sh
ok = True
for base, dyn in ((sys.argv[1], [1, 3, 320, 320]), (sys.argv[2], [2, 3, 48, 320])):
    (a, sh), (b, _) = run(base + ".onnx", dyn), run(base + ".ort", dyn)
    d = float(np.abs(a - b).max())
    print(base.split("/")[-1], "shape", sh, "max|Δ| =", d)
    ok &= d < 1e-3
print("OK" if ok else "FAIL: اختلاف زیاد؛ .ort را استفاده نکن")
sys.exit(0 if ok else 1)
PY
echo "فایل‌های .ort کنار .onnx ساخته شدند؛ npx cap sync و بیلد. غیرفعال‌سازی: ocrOrt(0) در کنسول."
