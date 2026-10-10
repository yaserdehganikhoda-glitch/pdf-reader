#!/usr/bin/env bash
# مرحلهٔ «Native Piper» در build-apk.yml: افزونهٔ بومی Piper (sherpa-onnx) را به پروژهٔ اندروید اضافه می‌کند.
set -euo pipefail
cd "$(dirname "$0")/.."
node native/setup-native.js
