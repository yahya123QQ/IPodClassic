package com.ipod.classic;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.WindowManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;

public class MainActivity extends Activity {
    WebView w;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);   // volume keys always control music
        w = new WebView(this);
        w.setBackgroundColor(Color.BLACK);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // playlists saved in localStorage
        s.setAllowFileAccess(true);
        Bridge br = new Bridge(this, w);
        w.addJavascriptInterface(br, "A");
        new Thread(br::killBg).start();      // battery: close background apps once on entry
        setContentView(w);
        w.loadUrl("file:///android_asset/index.html");
    }

    View bar;
    /** Invisible touch-blocker over the status bar so the notification shade can't be pulled down (only while this app is in front). */
    void lockBar(boolean on) {
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (on && bar == null) {
                bar = new View(this);
                WindowManager.LayoutParams p = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
                    (int) (26 * getResources().getDisplayMetrics().density), WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSPARENT);
                p.gravity = Gravity.TOP;
                wm.addView(bar, p);
            } else if (!on && bar != null) { wm.removeView(bar); bar = null; }
        } catch (Exception e) { bar = null; }
    }

    void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override protected void onResume() { super.onResume(); w.onResume(); immersive(); w.evaluateJavascript("if(window.wake)wake()", null); lockBar(true); }
    // If the notification shade / system dialogs are pulled down, close them right away
    @Override public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) immersive(); else sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
    }

    @Override protected void onPause() { super.onPause(); w.onPause(); lockBar(false); }   // JS timers stop = saves battery
    @Override public void onBackPressed() { w.evaluateJavascript("back()", null); } // never exit launcher
}
