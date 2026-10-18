package ir.sewingstats.app;   // <-- appId پروژهٔ خودت (inject-native.sh خودش جایگزین می‌کند)

import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(PiperNativePlugin.class);   // باید قبل از super.onCreate باشد
        super.onCreate(savedInstanceState);

        /* COOP/COEP روی همهٔ پاسخ‌های محلی (https://localhost) ← crossOriginIsolated ← SharedArrayBuffer ← WASM چندنخی.
           بلافاصله بعد از super.onCreate تنظیم می‌شود تا سند اصلی هم هدرها را بگیرد (نه فقط زیرمنابع). */
        WebView wv = getBridge().getWebView();
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
