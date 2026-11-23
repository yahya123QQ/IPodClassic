package com.ipod.classic;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.PowerManager;
import org.json.JSONArray;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;

/** Native playback: keeps playing (and auto-advancing) with the screen off. */
public class Player {
    static MediaPlayer mp;
    static Context app;
    static final List<String> q = new ArrayList<>();
    static int idx = -1, repeat = 0, err = 0, streak = 0;   // repeat: 0 off, 1 all, 2 one
    static boolean ready = false, shuffle = false, focusPaused = false, triedCache = false, volSet = false, loading = false;
    static String errMsg = "";

    static final AudioManager.OnAudioFocusChangeListener FL = c -> {
        synchronized (Player.class) {
            try {
                if (mp == null || !ready) return;
                if (c == AudioManager.AUDIOFOCUS_LOSS) { if (mp.isPlaying()) mp.pause(); focusPaused = false; }
                else if (c == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) { if (mp.isPlaying()) { mp.pause(); focusPaused = true; } }
                else if (c == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) mp.setVolume(0.3f, 0.3f);
                else if (c == AudioManager.AUDIOFOCUS_GAIN) { mp.setVolume(1f, 1f); if (focusPaused) { mp.start(); focusPaused = false; } }
            } catch (Exception e) {}
        }
    };

    static synchronized void setQueue(Context c, JSONArray a, int start) {
        app = c.getApplicationContext();
        q.clear();
        for (int i = 0; i < a.length(); i++) q.add(a.optString(i));
        play(start);
    }

    static synchronized void play(int i) {
        if (i < 0 || i >= q.size()) return;
        idx = i; triedCache = false; loading = false;
        playSrc(q.get(i));
    }

    static void playSrc(String src) {
        ready = false;
        try {
            if (mp == null) {
                mp = new MediaPlayer();
                mp.setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK);
                mp.setOnPreparedListener(m -> { synchronized (Player.class) { streak = 0; loading = false; ready = true; m.setVolume(1f, 1f); m.start(); } });
                mp.setOnCompletionListener(m -> done());
                mp.setOnErrorListener((m, what, extra) -> { onErr(what, extra); return true; });
            }
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            am.requestAudioFocus(FL, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            if (!volSet) {                       // first play: make sure media volume is audible
                volSet = true;
                int mx = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                if (am.getStreamVolume(AudioManager.STREAM_MUSIC) < mx * 0.4) am.setStreamVolume(AudioManager.STREAM_MUSIC, (int) (mx * 0.6), 0);
            }
            mp.reset();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setVolume(1f, 1f);
            mp.setDataSource(src);
            mp.prepareAsync();
            KeepAliveService.start(app);
        } catch (Exception e) { fail("Cannot open file"); }
    }

    static synchronized void onErr(int what, int extra) {
        ready = false;
        try { mp.reset(); } catch (Exception e) {}
        String src = (idx >= 0 && idx < q.size()) ? q.get(idx) : "";
        if (src.startsWith("http") && !triedCache) {      // old Android can't stream some HTTPS: download then play
            triedCache = true; loading = true; cacheAndPlay(src, idx);
        } else fail("Cannot play this file (" + what + ")");
    }

    static void fail(String msg) {
        synchronized (Player.class) {
            loading = false; ready = false; err++; errMsg = msg;
            if (++streak < 3 && idx + 1 < q.size()) play(idx + 1);
        }
    }

    static void cacheAndPlay(final String src, final int i) {
        new Thread(() -> {
            try {
                HttpURLConnection h = Bridge.httpOpen(src);
                if (h.getResponseCode() >= 400) throw new Exception("HTTP");
                File f = new File(app.getCacheDir(), "stream.mp3");
                InputStream in = h.getInputStream(); FileOutputStream o = new FileOutputStream(f);
                byte[] b = new byte[16384]; int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
                o.close(); in.close();
                synchronized (Player.class) { if (idx == i) playSrc(f.getPath()); }
            } catch (Exception e) { synchronized (Player.class) { if (idx == i) fail("Download/stream failed - check Wi-Fi"); } }
        }).start();
    }

    static synchronized void release() {
        try { if (mp != null) { mp.release(); mp = null; } } catch (Exception e) {}
        ready = false; q.clear(); idx = -1; KeepAliveService.stop(app);
    }

    static synchronized void done() { if (repeat == 2) play(idx); else next(); }

    static synchronized void next() {
        int n = idx + 1;
        if (shuffle && q.size() > 1) { do { n = (int) (Math.random() * q.size()); } while (n == idx); }
        if (n < q.size()) play(n);
        else if (repeat == 1 && q.size() > 0) play(0);
        else { try { mp.reset(); } catch (Exception e) {} ready = false; KeepAliveService.stop(app); }
    }

    static synchronized void prev() {
        if (ready && mp.getCurrentPosition() > 3000) mp.seekTo(0);
        else if (idx > 0) play(idx - 1);
    }

    static synchronized void toggle() {
        if (mp == null || !ready) return;
        if (mp.isPlaying()) mp.pause(); else mp.start();
    }

    static synchronized void pause() { try { if (mp != null && ready && mp.isPlaying()) mp.pause(); } catch (Exception e) {} }

    static synchronized void seek(int ms) {
        if (ready) mp.seekTo(Math.max(0, Math.min(ms, mp.getDuration())));
    }

    static synchronized String state() {
        int p = 0, d = 0; boolean pl = false;
        if (mp != null && ready) { try { p = mp.getCurrentPosition(); d = mp.getDuration(); pl = mp.isPlaying(); } catch (Exception e) {} }
        return "{\"i\":" + idx + ",\"p\":" + p + ",\"d\":" + d + ",\"pl\":" + pl + ",\"r\":" + repeat + ",\"s\":" + shuffle
            + ",\"e\":" + err + ",\"m\":\"" + errMsg.replace("\"", "'") + "\",\"l\":" + loading + "}";
    }
}
