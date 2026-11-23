package com.ipod.classic;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.ToneGenerator;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import javax.net.ssl.*;
import java.security.cert.X509Certificate;

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
                new String[]{"_data", "title", "artist", "album", "duration"}, "duration>1000", null, "title COLLATE NOCASE");
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
            byte[] b = m.getEmbeddedPicture();
            if (b == null) {                       // downloaded songs keep their cover in a sibling .art file
                File af = new File(p.replaceAll("\\.[^./]+$", "") + ".art");
                if (!af.exists()) return "";
                FileInputStream fi = new FileInputStream(af); ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] tb = new byte[8192]; int tn; while ((tn = fi.read(tb)) > 0) bo.write(tb, 0, tn); fi.close(); b = bo.toByteArray();
            }
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

    @JavascriptInterface public void setRepeat(int r) { Player.repeat = r; }
    @JavascriptInterface public void setShuffle(boolean b) { Player.shuffle = b; }
    @JavascriptInterface public void vol(int d) {
        ((AudioManager) a.getSystemService(Context.AUDIO_SERVICE)).adjustStreamVolume(AudioManager.STREAM_MUSIC,
            d > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
    }
    @JavascriptInterface public String volume() {
        AudioManager m = (AudioManager) a.getSystemService(Context.AUDIO_SERVICE);
        return m.getStreamVolume(AudioManager.STREAM_MUSIC) + "," + m.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
    }
    @JavascriptInterface public void dim(final boolean on) {      // battery saver: dim the screen when idle
        a.runOnUiThread(() -> { WindowManager.LayoutParams lp = a.getWindow().getAttributes();
            lp.screenBrightness = on ? 0.03f : -1f; a.getWindow().setAttributes(lp); });
    }
    @JavascriptInterface public boolean del(String path) {       // only files this app downloaded
        if (path == null || !path.contains("/Music/iPod/")) return false;
        boolean ok = new File(path).delete();
        if (ok) MediaScannerConnection.scanFile(a, new String[]{path}, null, null);
        return ok;
    }

    // ---------- exit / battery / sound test ----------
    @JavascriptInterface public void exit() {       // stop music, hand the phone back to its normal launcher
        Player.release();
        a.runOnUiThread(() -> {
            try {
                boolean ok = false;
                Intent hm = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
                for (ResolveInfo r : a.getPackageManager().queryIntentActivities(hm, 0)) {
                    if (!r.activityInfo.packageName.equals(a.getPackageName())) {
                        a.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                            .setClassName(r.activityInfo.packageName, r.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        ok = true; break;
                    }
                }
                if (!ok) open(Settings.ACTION_SETTINGS);
            } catch (Exception e) {}
            a.finish();
        });
    }
    @JavascriptInterface public void beep() { try { new ToneGenerator(AudioManager.STREAM_MUSIC, 100).startTone(ToneGenerator.TONE_PROP_BEEP2, 500); } catch (Exception e) {} }
    @JavascriptInterface public void saveBattery() { new Thread(() -> { int n = killBg(); js("toast('Closed " + n + " background apps')"); }).start(); }
    /** Ends background processes of user-installed apps (Android lets them restart later; system apps are never touched). */
    int killBg() {
        int n = 0;
        try {
            ActivityManager am = (ActivityManager) a.getSystemService(Context.ACTIVITY_SERVICE);
            PackageManager pm = a.getPackageManager();
            for (ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses()) {
                if (p.importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE) continue;
                for (String pkg : p.pkgList) {
                    if (pkg.equals(a.getPackageName())) continue;
                    try {
                        ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                        if ((ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) continue;
                        String l = pkg.toLowerCase();
                        if (l.contains("keyboard") || l.contains("inputmethod")) continue;
                        am.killBackgroundProcesses(pkg); n++;
                    } catch (Exception e) {}
                }
            }
        } catch (Exception e) {}
        return n;
    }

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


    // ---- HTTP helper: follows redirects (even http<->https); old Android 5 often rejects
    // newer HTTPS certificates, so on SSL failure it retries once without chain validation
    // (public music/podcast data only - no logins or personal data are ever sent).
    static SSLSocketFactory LAX;
    static SSLSocketFactory lax() {
        if (LAX == null) try {
            TrustManager[] t = {new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; } }};
            SSLContext sc = SSLContext.getInstance("TLS"); sc.init(null, t, null); LAX = sc.getSocketFactory();
        } catch (Exception e) {}
        return LAX;
    }
    static HttpURLConnection conn(String u, boolean relaxed) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
        if (relaxed && h instanceof HttpsURLConnection) ((HttpsURLConnection) h).setSSLSocketFactory(lax());
        h.setInstanceFollowRedirects(false); h.setConnectTimeout(15000); h.setReadTimeout(25000);
        h.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 5.1.1) iPodClassic");
        return h;
    }
    static HttpURLConnection httpOpen(String u) throws IOException {
        boolean relaxed = false;
        for (int i = 0; i < 7; i++) {
            HttpURLConnection h = conn(u, relaxed); int c;
            try { c = h.getResponseCode(); }
            catch (SSLException e) { if (relaxed) throw e; relaxed = true; i--; continue; }
            if (c >= 300 && c < 400) {
                String l = h.getHeaderField("Location"); h.disconnect();
                if (l == null) break;
                u = new URL(new URL(u), l).toString(); continue;
            }
            return h;
        }
        throw new IOException("redirects");
    }

    // ---------- network ----------
    @JavascriptInterface public void get(final String url, final int id) {
        new Thread(() -> {
            try {
                HttpURLConnection h = httpOpen(url);
                BufferedReader br = new BufferedReader(new InputStreamReader(h.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder(); String ln;
                while ((ln = br.readLine()) != null && sb.length() < 700000) sb.append(ln);
                br.close();
                js("onHttp(" + id + ",true," + JSONObject.quote(sb.toString()) + ")");
            } catch (Exception e) { js("onHttp(" + id + ",false,'')"); }
        }).start();
    }

    @JavascriptInterface public void download(final String url, final String name, final int id, final String img) {
        new Thread(() -> {
            try {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "iPod");
                dir.mkdirs();
                File f = new File(dir, name.replaceAll("[^\\w\\- ]", "_").substring(0, Math.min(80, name.length())) + ".mp3");
                HttpURLConnection h = httpOpen(url);
                if (h.getResponseCode() >= 400) throw new IOException("HTTP");
                int len = h.getContentLength();
                InputStream in = h.getInputStream(); FileOutputStream o = new FileOutputStream(f);
                byte[] b = new byte[16384]; int n, last = -1; long t = 0;
                while ((n = in.read(b)) > 0) {
                    o.write(b, 0, n); t += n;
                    int p = len > 0 ? (int) (t * 100 / len) : 0;
                    if (p != last && p % 10 == 0) { last = p; js("onDl(" + id + "," + p + ",'')"); }
                }
                o.close(); in.close();
                if (img != null && img.startsWith("http")) try {
                    HttpURLConnection g = httpOpen(img); InputStream gi = g.getInputStream();
                    FileOutputStream go = new FileOutputStream(new File(dir, f.getName().replaceAll("\\.mp3$", "") + ".art"));
                    byte[] gb = new byte[8192]; int gn; while ((gn = gi.read(gb)) > 0) go.write(gb, 0, gn); go.close(); gi.close();
                } catch (Exception e) {}
                MediaScannerConnection.scanFile(a, new String[]{f.getPath()}, null, null);
                js("onDl(" + id + ",101," + JSONObject.quote(f.getPath()) + ")");
            } catch (Exception e) { js("onDl(" + id + ",-1,'')"); }
        }).start();
    }
}
