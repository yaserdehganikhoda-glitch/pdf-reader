#!/bin/sh
# افزودن دسترسی خواندن فایل‌ها به AndroidManifest.xml (برای فهرست خودکار PDFهای دستگاه)؛ چندبار اجرا هم بی‌خطر است.
# استفاده: sh native/add-pdf-permissions.sh [مسیر AndroidManifest.xml]
M="${1:-android/app/src/main/AndroidManifest.xml}"
[ -f "$M" ] || { echo "AndroidManifest.xml پیدا نشد: $M"; exit 1; }
add() { grep -q "$1" "$M" || sed -i "s|<application|$2\n    <application|" "$M"; }
add 'android.permission.MANAGE_EXTERNAL_STORAGE' '<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />'
add 'android.permission.READ_EXTERNAL_STORAGE' '<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />'
echo "دسترسی فایل‌ها در $M تنظیم شد."
