package ir.sewingstats.app;   // <-- appId پروژهٔ خودت (inject-native.sh خودش جایگزین می‌کند)

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends BridgeActivity {
    /* r23: F4 ثابت کرد crossOriginIsolated در WebView اندروید هرگز true نمی‌شود؛ هدرها سودی ندارند ولی COEP=require-corp می‌تواند زیرمنابع بین‌مبدأ بدون CORP را ببندد.
       برای برگرداندن رفتار قدیمی true کن. */
    private static final boolean ISOLATION_HEADERS = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(PiperNativePlugin.class);   // باید قبل از super.onCreate باشد
        registerPlugin(OcrNativePlugin.class);     // r19 PoC: ORT بومی (نیازمند OcrNativePlugin.java + وابستگی gradle، مطابق ocr-perf-findings-r18.md §P2)
        super.onCreate(savedInstanceState);

        /* COOP/COEP روی همهٔ پاسخ‌های محلی (https://localhost) ← crossOriginIsolated ← SharedArrayBuffer ← WASM چندنخی.
           بلافاصله بعد از super.onCreate تنظیم می‌شود تا سند اصلی هم هدرها را بگیرد (نه فقط زیرمنابع). */
        WebView wv = getBridge().getWebView();
        /* r18: وضعیت حرارتی سیستم برای حاکم مسیرهای OCR (۰ عادی … ۲ متوسط، ۳+ شدید؛ ۱- = پشتیبانی نمی‌شود) */
        final PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wv.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public int thermal() {
                try { return Build.VERSION.SDK_INT >= 29 ? pm.getCurrentThermalStatus() : -1; } catch (Throwable e) { return -1; }
            }
            /* r19: headroom حرارتی (API 30+): ۱٫۰ = تروتل شدید؛ ۱- = نامعلوم. سیستم فراخوانی بیش از یک‌بار در چند ثانیه را NaN می‌دهد؛ سمت JS هر ≥۱۰ثانیه صدا می‌زند. */
            @JavascriptInterface
            public float headroom() {
                try {
                    if (Build.VERSION.SDK_INT < 30) return -1f;
                    float h = pm.getThermalHeadroom(10);
                    return Float.isNaN(h) ? -1f : h;
                } catch (Throwable e) { return -1f; }
            }
            /* r23: تعداد هسته‌های «غیر-LITTLE»: همهٔ هسته‌هایی که فرکانس بیشینه‌شان بالاتر از کم‌سرعت‌ترین خوشه است (+۵٪)؛ ۱- = نامعلوم.
               قاعدهٔ r19 (≥۸۵٪ بیشینه) روی SoCهای سه‌خوشه‌ای کلاستر میانی را «little» می‌شمرد (⇒ ۱–۲ هسته) و سقف مسیر/نخ بومی را بی‌دلیل ۱ می‌کرد.
               هستهٔ ناخوانا نادیده گرفته می‌شود (نه شکست کل تابع). تک‌خوشه ⇒ همهٔ هسته‌ها. */
            private int bigCache = -2;
            @JavascriptInterface
            public synchronized int bigCores() {
                if (bigCache != -2) return bigCache;
                try {
                    int n = Runtime.getRuntime().availableProcessors();
                    java.util.ArrayList<Long> f = new java.util.ArrayList<>();
                    for (int i = 0; i < n; i++) {
                        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq"))) {
                            f.add(Long.parseLong(r.readLine().trim()));
                        } catch (Throwable e) { /* هستهٔ آفلاین/ناخوانا */ }
                    }
                    if (f.isEmpty()) { bigCache = -1; return bigCache; }
                    long lo = java.util.Collections.min(f);
                    int big = 0;
                    for (long x : f) if (x > lo * 1.05) big++;
                    bigCache = big > 0 ? big : f.size();
                } catch (Throwable e) { bigCache = -1; }
                return bigCache;
            }
        }, "NativeThermal");
        if (ISOLATION_HEADERS) {
            wv.setWebViewClient(new BridgeWebViewClient(getBridge()) {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    WebResourceResponse r = super.shouldInterceptRequest(view, request);
                    if (r != null) {
                        Map<String, String> h = new HashMap<>();
                        if (r.getResponseHeaders() != null) h.putAll(r.getResponseHeaders());
                        h.put("Cross-Origin-Opener-Policy", "same-origin");
                        h.put("Cross-Origin-Embedder-Policy", "require-corp");
                        h.put("Cross-Origin-Resource-Policy", "same-origin");
                        r.setResponseHeaders(h);
                    }
                    return r;
                }
            });
        }
    }
}
