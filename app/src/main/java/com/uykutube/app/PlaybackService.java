package com.uykutube.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/* Video oynarken çalışır: ekran kapalıyken sesin kesilmemesi için partial
   wake lock + wifi lock tutar, uyku zamanlayıcısını sayar (son 60 sn sesi
   kısar), sayfa zamanlayıcıları kısıtlandığında reklam/duraklama denetimini
   (window.__uykuTick) saniyede bir kendisi tetikler. Ayrıca: kulaklık
   çıkınca ve telefon çalınca duraklatır, bildirim + MediaSession ile kilit
   ekranı/kulaklık tuşu kontrolü sağlar. */
public class PlaybackService extends Service {
    private static final String TAG = "FireTube";
    private static final String CHANNEL = "playback";
    private static final int NOTIF_ID = 1;

    // bildirim düğmeleri / MediaSession için servis eylemleri
    static final String ACTION_TOGGLE = "com.uykutube.app.TOGGLE";
    static final String ACTION_PLUS15 = "com.uykutube.app.PLUS15";
    static final String ACTION_STOP = "com.uykutube.app.STOP";

    private static final long FADE_MS = 60_000L;
    private static final Pattern STATUS_POS =
            Pattern.compile("^(caliyor|DURAKLADI) t=(\\d+)s/(\\d+)s .*sayfa=/watch");

    private static PlaybackService inst;
    private static PowerManager.WakeLock wl;
    private static WifiManager.WifiLock wifiLock;
    private static volatile long timerEndAt = 0L;
    private static volatile boolean stopAtEnd = false;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::onTick;
    private int tickCount = 0;
    private boolean started = false;
    private boolean lastInteractive = true;
    private boolean fading = false;

    // oynatma durumu (sayfadan Bridge.onPlaying ile gelir) ve bildirim içeriği
    private boolean playing = true;
    private String title = "";
    private String channel = "";
    private String lastNotifKey = "";

    /* Duraklatma niyeti (sayfanın ömründen bağımsız): video zamanlayıcı,
       kulaklık, telefon, bildirim ya da kullanıcı tarafından durdurulduysa
       true. Sayfa yeniden yüklenirse (kurtarma, yenileme) yeni sayfa bu
       bilgiyle duraklatılmış açılır (MainActivity ön script'e gömer). */
    private static volatile boolean holdPaused = false;

    // telefon görüşmesi: ses modu izlenir (izin gerekmez)
    private AudioManager am;
    private Object modeListener; // AudioManager.OnModeChangedListener (API 31+)
    private boolean inCall = false;
    private boolean pausedForCall = false;
    private int callSeq = 0;
    private boolean callCancelled = false; // görüşmede başka bir duraklatma geldi
    private long callStartedAt = 0;
    private final Runnable resumeAfterCall = () -> {
        // Bekleyen sürdürme çalışana kadar pausedForCall true kalır: arada
        // servis kapanmasın (holdingForCall)
        if (!pausedForCall) return;
        pausedForCall = false;
        MainActivity a = MainActivity.instance;
        if (a == null || a.web == null) return;
        a.web.evaluateJavascript("window.__uykuResume ? window.__uykuResume('telefon bitti') : false",
                v -> AppLog.write(this, "TELEFON: bitti — " + ("true".equals(v)
                        ? "video sürdürüldü" : "sürdürülmedi (başka duraklatma var ya da video yok)")));
    };

    /* Telefon dışındaki her duraklatma (zamanlayıcı, kulaklık, bildirim,
       kilit ekranı) görüşme bitince sürdürmeyi iptal eder: aksi hâlde görüşme
       sırasında dolan zamanlayıcı ya da çıkan kulaklık, görüşme bitince
       videonun (hoparlörden, zamanlayıcısız) yeniden başlamasıyla ezilirdi. */
    private void cancelCallResume() {
        pausedForCall = false;
        // koşulsuz: görüşme bitmiş ama telefonun JS yanıtı henüz gelmemiş olabilir
        // (çağrı başında sıfırlanır, yalnız telefon geri çağrısı okur)
        callCancelled = true;
        h.removeCallbacks(resumeAfterCall);
    }

    private MediaSession session;

    /* Kulaklık çıkınca / Bluetooth kopunca ses telefon hoparlörüne geçer:
       Android bu an için ACTION_AUDIO_BECOMING_NOISY yayınlar. Ses odağı
       istemeden yalnız bu yayın dinlenir. */
    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (!AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction())) return;
            pausePage("kulaklık çıktı");
            AppLog.write(PlaybackService.this, "KULAKLIK: çıktı — video duraklatıldı");
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        // servis oluşmadan önce gelen play/pause olayı kaybolmasın
        MainActivity a = MainActivity.instance;
        playing = a == null || a.videoPlaying;
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisyReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisyReceiver, f);
        if (Build.VERSION.SDK_INT >= 31) {
            AudioManager.OnModeChangedListener l = this::onAudioMode;
            am.addOnModeChangedListener(getMainExecutor(), l);
            modeListener = l;
        }
        setupSession();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (action != null) {
            if (started) handleAction(action);
            else { // servis kapalıyken gelen eski bildirim düğmesi: bildirimi de temizle
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIF_ID);
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (!started) {
            started = true;
            Log.d(TAG, "oynatma servisi başladı");
            AppLog.write(this, "SERVIS: başladı");
            // Donuk-renderer bekçisi: servis kapalıyken durum gelmediği için sayaç
            // eskidi; tazelenmezse ilk tikte sahte KURTARMA tetiklenirdi.
            MainActivity a = MainActivity.instance;
            if (a != null) a.lastStatusAt = System.currentTimeMillis();
            // Servis kapalıyken süresi dolmuş zamanlayıcı: devam edilen video
            // hemen durmasın, zamanlayıcı iptal sayılsın.
            if (timerEndAt > 0 && System.currentTimeMillis() >= timerEndAt) {
                timerEndAt = 0;
                AppLog.write(this, "ZAMANLAYICI: servis kapalıyken doldu — iptal");
            }
        }
        startFg();
        h.removeCallbacks(tick);
        h.postDelayed(tick, 1000);
        return START_NOT_STICKY;
    }

    private void handleAction(String action) {
        switch (action) {
            case ACTION_TOGGLE:
                if (playing) pausePage("bildirim");
                else resumePage("bildirim");
                break;
            case ACTION_PLUS15:
                extendTimer(15);
                break;
            case ACTION_STOP:
                // Durdur: video duraklar, zamanlayıcılar iptal, servis + bildirim kapanır
                pausePage("bildirim: durdur");
                setTimer(0);
                clearStopAtEnd();
                AppLog.write(this, "BILDIRIM: durdur");
                // bildirim önce kesin kapansın: arkadan gelen oynatma durumu
                // (refreshNotification) onu yeniden göstermesin
                started = false;
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
                stopSelf();
                break;
            default:
                break;
        }
    }

    /* Sayfadaki videoyu kullanıcı duraklatması olarak durdurur (otomatik devam
       geri almaz). cb: video o an çalıyor muydu. */
    private void pausePage(String reason, ValueCb cb) {
        if (!reason.startsWith("telefon")) cancelCallResume();
        // Sayfa hazır olmasa da (yükleniyor / hata sayfası) niyet korunur:
        // sayfa yüklenince duraklatılmış açılır
        holdPaused = true;
        MainActivity a = MainActivity.instance;
        if (a == null || a.web == null) {
            if (cb != null) cb.on(false);
            return;
        }
        a.web.evaluateJavascript("window.__uykuPause ? window.__uykuPause(" + jsStr(reason) + ")"
                        + " : (document.querySelector('video')?.pause(), false)",
                v -> {
                    if (cb != null) cb.on("true".equals(v));
                });
    }

    private void pausePage(String reason) {
        pausePage(reason, null);
    }

    private void resumePage(String reason) {
        holdPaused = false;
        cancelCallResume();
        MainActivity a = MainActivity.instance;
        if (a == null || a.web == null) return;
        a.web.evaluateJavascript("window.__uykuResume && window.__uykuResume(" + jsStr(reason) + ")", null);
    }

    private interface ValueCb {
        void on(boolean v);
    }

    private static String jsStr(String s) {
        return org.json.JSONObject.quote(s);
    }

    /* Telefon: zil çalarken (MODE_RINGTONE) ya da görüşmede (IN_CALL /
       IN_COMMUNICATION — WhatsApp vb. dahil) mod NORMAL dışındadır. Çalan video
       duraklatılır; görüşme bitince yalnız bizim durdurduğumuz video sürdürülür. */
    private void onAudioMode(int mode) {
        boolean call = mode > AudioManager.MODE_NORMAL;
        if (call && !inCall) {
            inCall = true;
            callCancelled = false;
            callStartedAt = System.currentTimeMillis();
            final int seq = ++callSeq;
            h.removeCallbacks(resumeAfterCall);
            final boolean alreadyHeld = holdPaused;
            pausePage("telefon", wasPlaying -> {
                boolean resume = wasPlaying && !alreadyHeld && seq == callSeq && !callCancelled;
                if (resume && inCall) {
                    pausedForCall = true;
                } else if (resume) {
                    // JS yanıtı görüşme bittikten sonra geldi (renderer yavaştı):
                    // görüşme bitti, hemen sürdür
                    pausedForCall = true;
                    h.post(resumeAfterCall);
                }
                AppLog.write(this, "TELEFON: mod=" + mode + " zil=" + am.getRingerMode()
                        + (wasPlaying ? " — video duraklatıldı" : " — video zaten duruyordu"));
            });
        } else if (!call && inCall) {
            inCall = false;
            if (pausedForCall) h.postDelayed(resumeAfterCall, 1500);
        }
    }

    /* Görüşme sürerken servis kapanmasın: kapanırsa görüşme bitince video
       sürdürülemezdi (MainActivity 5 dk boşta durdurmayı buna göre erteler).
       Güvenlik ağı: 3 saatten uzun "görüşme" yok sayılır (kilitler sonsuza
       dek tutulmasın). */
    static boolean holdingForCall() {
        PlaybackService s = inst;
        if (s == null || !(s.inCall || s.pausedForCall)) return false;
        return System.currentTimeMillis() - s.callStartedAt < 3 * 3600 * 1000L;
    }

    static void setHold(boolean on) {
        holdPaused = on;
    }

    static boolean isHoldPaused() {
        return holdPaused;
    }

    /* Yeni sayfaya aktarılacak duraklatmanın sahibi: telefon ise görüşme
       bitince yeni sayfada da sürdürülebilsin. */
    static String holdOwner() {
        PlaybackService s = inst;
        return s != null && s.pausedForCall ? "telefon" : "önceki duraklatma";
    }

    private void setupSession() {
        session = new MediaSession(this, "FireTube");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                resumePage("kulaklık/kilit ekranı");
            }

            @Override
            public void onPause() {
                pausePage("kulaklık/kilit ekranı");
            }

            @Override
            public void onStop() {
                pausePage("kulaklık/kilit ekranı: durdur");
            }

            // Medya kartının "+15 dk" / "Durdur" düğmeleri
            @Override
            public void onCustomAction(String action, android.os.Bundle extras) {
                if (started) handleAction(action);
            }
        }, h);
        session.setActive(true);
        updateSession();
    }

    /* Android 13+ medya kartı (kilit ekranı / bildirim paneli) düğmeleri
       bildirimin addAction'larından DEĞİL PlaybackState'ten türetir: +15 dk ve
       Durdur özel eylem olarak eklenir. Kart alt satırı METADATA_KEY_ARTIST'i
       gösterdiği için zamanlayıcı/video sonu durumu oraya yazılır. */
    private void updateSession() {
        if (session == null) return;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP)
                .addCustomAction(new PlaybackState.CustomAction.Builder(
                        ACTION_PLUS15, "+15 dk", R.drawable.ic_plus15).build())
                .addCustomAction(new PlaybackState.CustomAction.Builder(
                        ACTION_STOP, "Durdur", R.drawable.ic_stop).build())
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build());
        String sub = channel;
        if (stopAtEnd || timerEndAt > 0) sub = statusText() + (channel.isEmpty() ? "" : " · " + channel);
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title.isEmpty() ? "FireTube" : title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, sub)
                .build());
    }

    /* Sayfa oynatma durumunu bildirir (Bridge.onPlaying). */
    static void onPlayState(boolean isPlaying) {
        PlaybackService s = inst;
        if (s == null || s.playing == isPlaying) return;
        s.playing = isPlaying;
        s.refreshNotification();
    }

    private PendingIntent actionIntent(String action, int req) {
        Intent i = new Intent(this, PlaybackService.class).setAction(action);
        return PendingIntent.getService(this, req, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private String statusText() {
        if (stopAtEnd) return "⏱ video bitince duracak";
        if (timerEndAt > 0) {
            long m = Math.max(0, (timerEndAt - System.currentTimeMillis() + 59_999) / 60_000);
            return "⏱ " + m + " dk kaldı";
        }
        if (!channel.isEmpty()) return channel;
        return playing ? "Çalıyor — ekran kapalıyken ses devam eder" : "Duraklatıldı";
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Oynatma", NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action toggle = new Notification.Action.Builder(
                Icon.createWithResource(this, playing ? android.R.drawable.ic_media_pause
                        : android.R.drawable.ic_media_play),
                playing ? "Duraklat" : "Oynat", actionIntent(ACTION_TOGGLE, 1)).build();
        Notification.Action plus = new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_recent_history),
                "+15 dk", actionIntent(ACTION_PLUS15, 2)).build();
        Notification.Action stop = new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                "Durdur", actionIntent(ACTION_STOP, 3)).build();
        Notification.MediaStyle style = new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2);
        if (session != null) style.setMediaSession(session.getSessionToken());
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title.isEmpty() ? "FireTube" : title)
                .setContentText(statusText())
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setStyle(style)
                .addAction(toggle)
                .addAction(plus)
                .addAction(stop)
                .build();
    }

    private String notifKey() {
        return playing + "|" + title + "|" + channel + "|" + statusText();
    }

    /* Bildirim ve medya kartı yalnız içerik değişince yenilenir (dakikada bir
       kalan süre). */
    private void refreshNotification() {
        if (!started) return;
        String key = notifKey();
        if (key.equals(lastNotifKey)) return;
        lastNotifKey = key;
        updateSession();
        try {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, buildNotification());
        } catch (Exception e) {
            Log.w(TAG, "bildirim güncellenemedi: " + e.getMessage());
        }
    }

    private void startFg() {
        Notification n = buildNotification();
        updateSession();
        lastNotifKey = notifKey();
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
       yaratıyordu (ekran kapanınca video anında duraklıyordu). MediaSession
       ses odağı istemez; yalnız kontrol düğmelerini taşır. */

    private long lastTickErrorLogAt = 0;

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
                            v -> onStatus(v, heartbeat));
                    a.web.evaluateJavascript("window.__uykuMeta ? window.__uykuMeta() : ''", this::onMeta);
                }
                // Donuk-renderer bekçisi: durum 2 dakikadır hiç gelmiyorsa sayfa
                // cevap vermiyor demektir — WebView'i yeniden kur (kendi kendine iyileşme).
                if (a.lastStatusAt > 0 && System.currentTimeMillis() - a.lastStatusAt > 120000) {
                    a.lastStatusAt = System.currentTimeMillis(); // döngüyü önle
                    a.rebuildWebView("durum-2dk-dir-gelmiyor");
                }
                // Kuralları 6 saatte bir tazele (uygulama açılışındaki çekimenin
                // yanı sıra uzun gece oturumlarında da güncel kalsın).
                if (tickCount > 0 && tickCount % 21600 == 0) {
                    a.filter.refreshRules();
                    a.filter.refreshLists();
                }
                // Ekran açılma geçişinde WebView çizim yüzeyini tekmele (siyah ekran
                // önlemi): onResume her durumda tetiklenmeyebilir, burada da izlenir.
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                boolean interactive = pm.isInteractive();
                if (interactive && !lastInteractive) {
                    Log.d(TAG, "ekran açıldı — yüzey tekmeleniyor");
                    a.kickWebViewSurface();
                }
                lastInteractive = interactive;
                a.checkScreenHold();
                a.updateQualityMode(); // ekran açılıp kapanınca kalite kipi
            } catch (Exception e) {
                Log.w(TAG, "tick hatası: " + e.getMessage());
                long now = System.currentTimeMillis();
                if (now - lastTickErrorLogAt > 60_000) {
                    lastTickErrorLogAt = now;
                    AppLog.write(this, "HATA: tick — " + e);
                }
            }
        }
        // Android 12 öncesi: ses modu dinleyicisi yok, saniyede bir yoklanır
        if (modeListener == null && am != null) onAudioMode(am.getMode());
        onTimerTick(a);
        refreshNotification();
        if (inst != null) h.postDelayed(tick, 1000);
    }

    private void onTimerTick(MainActivity a) {
        if (timerEndAt <= 0) return;
        long rem = timerEndAt - System.currentTimeMillis();
        if (rem <= 0) {
            timerEndAt = 0;
            fading = false;
            pausePage("zamanlayıcı");
            AppLog.write(this, "ZAMANLAYICI: doldu — video durduruldu");
        } else if (rem <= FADE_MS && a != null && a.web != null && playing) {
            // Son 60 sn: ses yavaşça kısılır (ani kesinti uyandırmasın)
            fading = true;
            a.web.evaluateJavascript("window.__uykuFade && window.__uykuFade("
                    + String.format(Locale.ROOT, "%.3f", rem / (double) FADE_MS) + ")", null);
        }
        if (a != null) a.updateChip();
    }

    private void onStatus(String v, boolean heartbeat) {
        MainActivity ma = MainActivity.instance;
        if (ma != null) ma.lastStatusAt = System.currentTimeMillis();
        Log.d(TAG, "durum: " + v);
        if (v == null) return;
        String s = v.replace("\"", "");
        if (heartbeat) AppLog.write(this, "NABIZ: " + s);
        // Oynatma durumu yalnız olaylarla değil, 10 sn'de bir sorgu ile de eşitlenir
        // (kurtarmada "pause" olayı gelmeden sayfa yok edilebiliyor)
        if (s.startsWith("caliyor")) {
            onPlayState(true);
            holdPaused = false; // video çalıyorsa duraklatma niyeti yoktur (kendini düzeltme)
        }
        else if (s.startsWith("DURAKLADI") || s.startsWith("BITTI") || s.equals("video-yok")) onPlayState(false);
        // Kurtarmada kaldığı saniyeden devam için son konum (reklam/canlı hariç)
        Matcher m = STATUS_POS.matcher(s);
        if (ma != null && m.find() && !s.contains("[REKLAM]") && Long.parseLong(m.group(3)) > 0) {
            ma.notePosition(Long.parseLong(m.group(2)));
        }
    }

    private void onMeta(String v) {
        if (v == null || v.equals("null") || v.length() < 3) return;
        try {
            // evaluateJavascript dönüşü JSON metni içeren bir JSON dizesi
            String json = new org.json.JSONArray("[" + v + "]").getString(0);
            org.json.JSONObject o = new org.json.JSONObject(json);
            String t = o.optString("title", "");
            String c = o.optString("channel", "");
            if (!t.equals(title) || !c.equals(channel)) {
                title = t;
                channel = c;
                refreshNotification();
            }
        } catch (Exception ignored) {
        }
    }

    public static void start(Context c) {
        if (inst == null) {
            try {
                c.startForegroundService(new Intent(c, PlaybackService.class));
            } catch (Exception e) {
                Log.e(TAG, "servis başlatılamadı", e);
                AppLog.write(c, "HATA: servis başlatılamadı — " + e);
            }
        }
    }

    /* Servisi durdurur. Zamanlayıcıya DOKUNMAZ: 5 dk'dan uzun duraklatmada
       servis kapanınca zamanlayıcı sessizce siliniyordu. */
    public static void stop(Context c) {
        if (inst != null) c.stopService(new Intent(c, PlaybackService.class));
    }

    public static void setTimer(long minutes) {
        timerEndAt = minutes <= 0 ? 0L : System.currentTimeMillis() + minutes * 60000L;
        PlaybackService s = inst;
        if (s != null) s.afterTimerChange();
    }

    static void extendTimer(long minutes) {
        long now = System.currentTimeMillis();
        PlaybackService s = inst;
        // tek kip: "video sonu" açıksa +15 dk onu zamanlayıcıya çevirir (gizli
        // bir zamanlayıcı videoyu ortada durdurmasın)
        if (stopAtEnd) {
            clearStopAtEnd();
            if (s != null) AppLog.write(s, "VIDEO SONU: +" + minutes + " dk ile zamanlayıcıya geçildi");
        }
        timerEndAt = Math.max(timerEndAt, now) + minutes * 60000L;
        if (s != null) {
            AppLog.write(s, "ZAMANLAYICI: +" + minutes + " dk");
            s.afterTimerChange();
        }
    }

    /* "Video sonu" kipini HEM Java'da HEM sayfada kapatır (yalnız Java bayrağı
       kapanırsa sayfadaki bayrak sonraki videoyu sessizce durdururdu). */
    static void clearStopAtEnd() {
        MainActivity a = MainActivity.instance;
        if (a != null) a.applyStopAtEnd(false);
        else setStopAtEnd(false);
    }

    /* Zamanlayıcı değişince kısılmış ses geri gelir, bildirim/rozet yenilenir. */
    private void afterTimerChange() {
        if (fading) {
            fading = false;
            MainActivity a = MainActivity.instance;
            if (a != null && a.web != null) a.web.evaluateJavascript("window.__uykuFade && window.__uykuFade(1)", null);
        }
        refreshNotification();
        MainActivity a = MainActivity.instance;
        if (a != null) a.updateChip();
    }

    static void setStopAtEnd(boolean on) {
        stopAtEnd = on;
        PlaybackService s = inst;
        if (s != null) s.refreshNotification();
    }

    static boolean isStopAtEnd() {
        return stopAtEnd;
    }

    public static String timerText() {
        if (stopAtEnd) return "video sonu";
        if (timerEndAt <= 0) return "";
        long s = (timerEndAt - System.currentTimeMillis()) / 1000;
        if (s < 0) s = 0;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "oynatma servisi durdu");
        inst = null;
        started = false;
        try {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIF_ID);
        } catch (Exception ignored) {
        }
        h.removeCallbacks(tick);
        h.removeCallbacks(resumeAfterCall);
        try {
            unregisterReceiver(noisyReceiver);
        } catch (Exception ignored) {
        }
        if (Build.VERSION.SDK_INT >= 31 && modeListener != null) {
            am.removeOnModeChangedListener((AudioManager.OnModeChangedListener) modeListener);
        }
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        if (wl != null && wl.isHeld()) wl.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        AppLog.write(this, "SERVIS: durdu");
        MainActivity a = MainActivity.instance;
        if (a != null) a.updateChip();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
