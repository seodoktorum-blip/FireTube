package com.uykutube.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.util.Locale;

/* Video oynarken çalışır: ekran kapalıyken sesin kesilmemesi için
   partial wake lock + wifi lock tutar, ses odağı alır, uyku zamanlayıcısını
   sayar ve sayfa zamanlayıcıları kısıtlandığında reklam/duraklama
   denetimini (window.__uykuTick) saniyede bir kendisi tetikler. */
public class PlaybackService extends Service {
    private static final String TAG = "FireTube";
    private static final String CHANNEL = "playback";
    private static final int NOTIF_ID = 1;

    private static PlaybackService inst;
    private static PowerManager.WakeLock wl;
    private static WifiManager.WifiLock wifiLock;
    private static volatile long timerEndAt = 0L;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::onTick;
    private int tickCount = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "oynatma servisi başladı");
        MainActivity a = MainActivity.instance;
        if (a != null) a.appLog("SERVIS: başladı");
        startFg();
        h.removeCallbacks(tick);
        h.postDelayed(tick, 1000);
        return START_NOT_STICKY;
    }

    private void startFg() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "Oynatma", NotificationManager.IMPORTANCE_LOW));
        }
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("FireTube")
                .setContentText("Çalıyor — ekran kapalıyken ses devam eder")
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, n);
        }
        if (wl == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UykuTube:play");
            wl.setReferenceCounted(false);
        }
        if (!wl.isHeld()) wl.acquire(10L * 60 * 60 * 1000);
        if (wifiLock == null) {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "UykuTube:wifi");
            wifiLock.setReferenceCounted(false);
        }
        if (!wifiLock.isHeld()) wifiLock.acquire();
    }

    /* Ses odağı (audio focus) bilinçli olarak İSTENMİYOR: WebView/Chromium medya
       için kendi oturumunu yönetir; bizimkisi onunla çakışıp play/pause döngüsü
       yaratıyordu (ekran kapanınca video anında duraklıyordu). */

    private void onTick() {
        MainActivity a = MainActivity.instance;
        if (a != null && a.web != null) {
            try {
                // Ekran kapalıyken sayfanın kendi zamanlayıcıları kısıtlanır;
                // doğrudan çağrı kısıta takılmaz.
                a.web.evaluateJavascript("window.__uykuTick && window.__uykuTick()", null);
                if (++tickCount % 10 == 0) {
                    final boolean heartbeat = tickCount % 60 == 0;
                    a.web.evaluateJavascript("window.__uykuStatus ? window.__uykuStatus() : 'yok'",
                            v -> {
                                MainActivity ma = MainActivity.instance;
                                if (ma != null) ma.lastStatusAt = System.currentTimeMillis();
                                Log.d(TAG, "durum: " + v);
                                if (heartbeat && v != null) {
                                    String s = v.replace("\"", "");
                                    if (ma != null) ma.appLog("NABIZ: " + s);
                                }
                            });
                }
                // Donuk-renderer bekçisi: durum 2 dakikadır hiç gelmiyorsa sayfa
                // cevap vermiyor demektir — WebView'i yeniden kur (kendi kendine iyileşme).
                if (a.lastStatusAt > 0 && System.currentTimeMillis() - a.lastStatusAt > 120000) {
                    a.lastStatusAt = System.currentTimeMillis(); // döngüyü önle
                    a.rebuildWebView("durum-2dk-dir-gelmiyor");
                }
            } catch (Exception e) {
                Log.w(TAG, "tick hatası: " + e.getMessage());
            }
        }
        if (timerEndAt > 0) {
            if (System.currentTimeMillis() >= timerEndAt) {
                timerEndAt = 0;
                if (a != null && a.web != null) {
                    a.web.evaluateJavascript("document.querySelector('video')?.pause()", null);
                }
            }
            if (a != null) a.updateChip();
        }
        if (inst != null) h.postDelayed(tick, 1000);
    }

    public static void start(Context c) {
        if (inst == null) {
            try {
                c.startForegroundService(new Intent(c, PlaybackService.class));
            } catch (Exception e) {
                Log.e(TAG, "servis başlatılamadı", e);
            }
        }
    }

    public static void stop(Context c) {
        timerEndAt = 0;
        if (inst != null) c.stopService(new Intent(c, PlaybackService.class));
    }

    public static void setTimer(long minutes) {
        timerEndAt = minutes <= 0 ? 0L : System.currentTimeMillis() + minutes * 60000L;
    }

    public static String timerText() {
        if (timerEndAt <= 0) return "";
        long s = (timerEndAt - System.currentTimeMillis()) / 1000;
        if (s < 0) s = 0;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "oynatma servisi durdu");
        inst = null;
        h.removeCallbacks(tick);
        if (wl != null && wl.isHeld()) wl.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        MainActivity a = MainActivity.instance;
        if (a != null) {
            a.appLog("SERVIS: durdu");
            a.updateChip();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
