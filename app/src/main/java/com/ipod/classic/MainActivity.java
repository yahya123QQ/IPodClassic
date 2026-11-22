package com.ipod.classic;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;

public class MainActivity extends Activity {
    WebView w;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        w = new WebView(this);
        w.setBackgroundColor(Color.BLACK);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // playlists saved in localStorage
        s.setAllowFileAccess(true);
        w.addJavascriptInterface(new Bridge(this, w), "A");
        setContentView(w);
        w.loadUrl("file:///android_asset/index.html");
    }

    void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override protected void onResume() { super.onResume(); w.onResume(); immersive(); }
    @Override protected void onPause() { super.onPause(); w.onPause(); }   // JS timers stop = saves battery
    @Override public void onBackPressed() { w.evaluateJavascript("back()", null); } // never exit launcher
}
