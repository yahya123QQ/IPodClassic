package com.ipod.classic;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

/** JS <-> Java bridge, exposed to the page as window.A */
public class Bridge {
    final Activity a; final WebView w;
    Bridge(Activity a, WebView w) { this.a = a; this.w = w; }

    void js(final String s) { a.runOnUiThread(() -> w.evaluateJavascript(s, null)); }

    // ---------- library ----------
    @JavascriptInterface public String library() {
        JSONArray r = new JSONArray();
        try {
            Cursor c = a.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                new String[]{"_data", "title", "artist", "album", "duration"}, null, null, "title COLLATE NOCASE");
            if (c != null) {
                while (c.moveToNext()) {
                    String p = c.getString(0); if (p == null) continue;
                    String l = p.toLowerCase();
                    if (!(l.endsWith(".mp3") || l.endsWith(".m4a") || l.endsWith(".wav") || l.endsWith(".aac") || l.endsWith(".ogg") || l.endsWith(".flac"))) continue;
                    if (l.contains("/ringtones/") || l.contains("/notifications/") || l.contains("/alarms/")) continue;
                    JSONObject o = new JSONObject();
                    o.put("p", p).put("t", c.getString(1)).put("a", c.getString(2)).put("b", c.getString(3)).put("d", c.getLong(4) / 1000);
                    r.put(o);
                }
                c.close();
            }
        } catch (Exception e) {}
        return r.toString();
    }

    @JavascriptInterface public String art(String p) {
        if (p == null || p.startsWith("http")) return "";
        MediaMetadataRetriever m = new MediaMetadataRetriever();
        try {
            m.setDataSource(p);
            byte[] b = m.getEmbeddedPicture(); if (b == null) return "";
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true; BitmapFactory.decodeByteArray(b, 0, b.length, o);
            o.inSampleSize = Math.max(1, o.outWidth / 200); o.inJustDecodeBounds = false;
            Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length, o);
            if (bm == null) return "";
            ByteArrayOutputStream s = new ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.JPEG, 70, s); bm.recycle();
            return "data:image/jpeg;base64," + Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) { return ""; } finally { try { m.release(); } catch (Exception e) {} }
    }

    // ---------- playback ----------
    @JavascriptInterface public void playQueue(String json, int start) {
        try { Player.setQueue(a, new JSONArray(json), start); } catch (Exception e) {}
    }
    @JavascriptInterface public void toggle() { Player.toggle(); }
    @JavascriptInterface public void next() { Player.next(); }
    @JavascriptInterface public void prev() { Player.prev(); }
    @JavascriptInterface public void seek(int ms) { Player.seek(ms); }
    @JavascriptInterface public String state() { return Player.state(); }

    // ---------- status / wifi / system ----------
    @JavascriptInterface public String status() {
        Intent i = a.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int lv = i.getIntExtra("level", 0) * 100 / Math.max(1, i.getIntExtra("scale", 100));
        boolean ch = i.getIntExtra("plugged", 0) != 0;
        WifiManager wm = (WifiManager) a.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        NetworkInfo n = ((ConnectivityManager) a.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetworkInfo();
        return "{\"b\":" + lv + ",\"c\":" + ch + ",\"w\":" + wm.isWifiEnabled() + ",\"n\":" + (n != null && n.isConnected()) + "}";
    }
    @JavascriptInterface public void toggleWifi() {
        WifiManager wm = (WifiManager) a.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wm.setWifiEnabled(!wm.isWifiEnabled());
    }
    @JavascriptInterface public void openWifi() { open(Settings.ACTION_WIFI_SETTINGS); }
    @JavascriptInterface public void openSettings() { open(Settings.ACTION_SETTINGS); }
    void open(String act) { try { a.startActivity(new Intent(act).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception e) {} }

    // ---------- network ----------
    @JavascriptInterface public void get(final String url, final int id) {
        new Thread(() -> {
            try {
                HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
                h.setConnectTimeout(15000); h.setReadTimeout(20000);
                BufferedReader br = new BufferedReader(new InputStreamReader(h.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder(); String ln;
                while ((ln = br.readLine()) != null) sb.append(ln);
                br.close();
                js("onHttp(" + id + ",true," + JSONObject.quote(sb.toString()) + ")");
            } catch (Exception e) { js("onHttp(" + id + ",false,'')"); }
        }).start();
    }

    @JavascriptInterface public void download(final String url, final String name, final int id) {
        new Thread(() -> {
            try {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "iPod");
                dir.mkdirs();
                File f = new File(dir, name.replaceAll("[^\\w\\- ]", "_") + ".mp3");
                HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
                h.setConnectTimeout(15000); h.setReadTimeout(20000);
                int len = h.getContentLength();
                InputStream in = h.getInputStream(); FileOutputStream o = new FileOutputStream(f);
                byte[] b = new byte[16384]; int n, last = -1; long t = 0;
                while ((n = in.read(b)) > 0) {
                    o.write(b, 0, n); t += n;
                    int p = len > 0 ? (int) (t * 100 / len) : 0;
                    if (p != last && p % 10 == 0) { last = p; js("onDl(" + id + "," + p + ",'')"); }
                }
                o.close(); in.close();
                MediaScannerConnection.scanFile(a, new String[]{f.getPath()}, null, null);
                js("onDl(" + id + ",101," + JSONObject.quote(f.getPath()) + ")");
            } catch (Exception e) { js("onDl(" + id + ",-1,'')"); }
        }).start();
    }
}
