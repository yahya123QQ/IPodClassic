package com.ipod.classic;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.IBinder;

/** Foreground service so Android doesn't kill the process during playback. */
public class KeepAliveService extends Service {
    static void start(Context c) { c.startService(new Intent(c, KeepAliveService.class)); }
    static void stop(Context c) { if (c != null) c.stopService(new Intent(c, KeepAliveService.class)); }

    final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { Player.pause(); }
    };

    @Override public void onCreate() {
        super.onCreate();
        registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    }
    @Override public void onDestroy() { try { unregisterReceiver(noisy); } catch (Exception e) {} super.onDestroy(); }

    @Override public int onStartCommand(Intent i, int f, int id) {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), 0);
        Notification n = new Notification.Builder(this).setContentTitle("iPod")
            .setContentText("Playing music").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi).build();
        startForeground(1, n);
        return START_STICKY;
    }
    @Override public IBinder onBind(Intent i) { return null; }
}
