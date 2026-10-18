// android/app/src/main/java/<مسیر-پکیج-شما>/MainActivity.java
// خط package را با appId پروژهٔ خودتان جایگزین کنید.
package YOUR.APP.ID;

import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;

import java.util.HashMap;
import java.util.Map;

/**
 * افزودن COOP/COEP به همهٔ پاسخ‌های محلی (https://localhost) تا
 * window.crossOriginIsolated=true شود ← SharedArrayBuffer ← WASM چندنخی.
 * فقط منابع محلی را تغییر می‌دهد؛ برنامه آفلاین است پس require-corp مشکلی ایجاد نمی‌کند.
 */
public class MainActivity extends BridgeActivity {
  @Override
  public void onStart() {
    super.onStart();
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
