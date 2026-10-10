package ir.sewingstats.app;   // <-- appId پروژهٔ خودت

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(PiperNativePlugin.class);   // باید قبل از super.onCreate باشد
        super.onCreate(savedInstanceState);
    }
}
