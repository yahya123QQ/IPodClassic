package com.ipod.classic;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.PowerManager;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;

/** Native playback: keeps playing (and auto-advancing) with the screen off. */
public class Player {
    static MediaPlayer mp;
    static Context app;
    static final List<String> q = new ArrayList<>();
    static int idx = -1;
    static boolean ready = false;

    static synchronized void setQueue(Context c, JSONArray a, int start) {
        app = c.getApplicationContext();
        q.clear();
        for (int i = 0; i < a.length(); i++) q.add(a.optString(i));
        play(start);
    }

    static synchronized void play(int i) {
        if (i < 0 || i >= q.size()) return;
        idx = i; ready = false;
        try {
            if (mp == null) {
                mp = new MediaPlayer();
                mp.setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK);
                mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
                mp.setOnPreparedListener(m -> { ready = true; m.start(); });
                mp.setOnCompletionListener(m -> next());
                mp.setOnErrorListener((m, what, extra) -> { ready = false; m.reset(); if (idx + 1 < q.size()) play(idx + 1); return true; });
            }
            mp.reset();
            mp.setDataSource(q.get(i));
            mp.prepareAsync();
            KeepAliveService.start(app);
        } catch (Exception e) { ready = false; }
    }

    static synchronized void next() {
        if (idx + 1 < q.size()) play(idx + 1);
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

    static synchronized void seek(int ms) {
        if (ready) mp.seekTo(Math.max(0, Math.min(ms, mp.getDuration())));
    }

    static synchronized String state() {
        int p = 0, d = 0; boolean pl = false;
        if (mp != null && ready) { try { p = mp.getCurrentPosition(); d = mp.getDuration(); pl = mp.isPlaying(); } catch (Exception e) {} }
        return "{\"i\":" + idx + ",\"p\":" + p + ",\"d\":" + d + ",\"pl\":" + pl + "}";
    }
}
