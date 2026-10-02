package com.uykutube.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String TAG = "FireTube";
    private static final String START_URL = "https://m.youtube.com/";

    /* Ağ seviyesinde engellenecek reklam/izleyici adresleri. Bilinçli olarak
       SADECE cross-domain reklam/izleyici altyapısı ve izlemesiz ping'ler burada:
       reklama ait MEDYA URL'lerini boş yanıtla kesmek video oynatıcıyı kilitliyor
       (simsiyah ekran + sessizlik). Reklamın kendisi inject.js'te oynatıcı
       seviyesinde yakalanıp sessizleştirilip 16x hızla geçilir. */
    private static final Pattern[] BLOCK_RE = new Pattern[]{
            Pattern.compile("://[^/]*doubleclick\\.net/"),
            Pattern.compile("://[^/]*googlesyndication\\.com/"),
            Pattern.compile("://[^/]*googleadservices\\.com/"),
            Pattern.compile("://[^/]*adservice\\.google\\."),
            Pattern.compile("://[^/]*adserver\\."),
            Pattern.compile("://[^/]*adsystem"),
            Pattern.compile("://[^/]*scorecardresearch\\.com/"),
            Pattern.compile("/pagead/"),
            Pattern.compile("/api/stats/ads"),
    };

    public static volatile MainActivity instance;

    public WebView web;
    private FrameLayout root;
    private TextView chip;
    private SwipeRefreshLayout swipe;
    private final AtomicInteger blocked = new AtomicInteger();
    private String injectSrc;
    private String preinjectSrc;
    private volatile String uaString = null;
    private boolean askedNotifPermission = false;
    private boolean askedBattery = false;
    private volatile String lastUrl = null;
    public volatile long lastStatusAt = 0L;

    private View customView;
    private MyChromeClient chromeClient;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        injectSrc = readAsset("inject.js");
        preinjectSrc = readAsset("preinject.js");

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0F0F0F"));

        // Aşağı çek → sayfayı yenile (orijinal YouTube uygulamasındaki gibi).
        // Sadece sayfa en baştayken tetiklenir, normal kaydırma/video etkileşimini bozmaz.
        swipe = new SwipeRefreshLayout(this);
        swipe.setColorSchemeColors(Color.parseColor("#C5221F"));
        swipe.setOnRefreshListener(() -> {
            web.reload();
            // güvenlik: sayfa bitmese bile gösterge 4 sn sonra kendini kapar
            swipe.postDelayed(() -> swipe.setRefreshing(false), 4000);
        });
        swipe.setOnChildScrollUpCallback((parent, child) -> web.canScrollVertically(-1));
        root.addView(swipe, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        chip = new TextView(this);
        chip.setText("🛡 0");
        chip.setTextColor(Color.parseColor("#8FD18F"));
        chip.setBackgroundColor(0xCC1A2B1A);
        float d = getResources().getDisplayMetrics().density;
        chip.setPadding((int) (10 * d), (int) (5 * d), (int) (10 * d), (int) (5 * d));
        chip.setTextSize(13);
        chip.setOnClickListener(v -> showMenu());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        // YouTube'un üst şeridinin (logo/ara düğmesi) altına, çakışmayacak yere
        lp.topMargin = (int) (72 * d);
        lp.rightMargin = (int) (10 * d);
        root.addView(chip, lp);

        setContentView(root);

        /* Sistem durum çubuğu (saat/pil/çentik) ile gezinme çubuğu uygulama
           içeriğinin üstüne binmesin: kök görünüme sistem çubukları kadar
           iç boşluk veriyoruz. Tam ekranda çubuklar gizlenince boşluk kendiliğinden
           kapanır. */
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets sb = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                top = sb.top;
                bottom = sb.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top, 0, bottom);
            return insets;
        });

        createWebView();

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else if (lastUrl == null) {
            lastUrl = START_URL;
        }
        web.loadUrl(lastUrl);
    }

    /* Ana HTML belgesini kendimiz indirir, <head>'in hemen sonrasına
       preinject.js'i gömer ve değiştirilmiş belgeyi döndürür. Hata olursa
       null döner ve WebView normal şekilde yükler. */
    private WebResourceResponse rewriteHtml(String urlStr) {
        java.net.HttpURLConnection c = null;
        try {
            java.net.URL url = new java.net.URL(urlStr);
            c = (java.net.HttpURLConnection) url.openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", uaString != null ? uaString : "");
            c.setRequestProperty("Accept-Language", "tr,en;q=0.9");
            // açılışı basit tut: sıkıştırma isteme
            c.setRequestProperty("Accept-Encoding", "identity");
            String cookies = CookieManager.getInstance().getCookie(urlStr);
            if (cookies != null) c.setRequestProperty("Cookie", cookies);

            int code = c.getResponseCode();
            if (code != 200) return null;

            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream in = c.getInputStream()) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            }
            String html = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            int head = html.indexOf("<head");
            int gt = head >= 0 ? html.indexOf('>', head) : -1;
            if (gt < 0) return null;
            html = html.substring(0, gt + 1) + "<script>" + preinjectSrc + "</script>" + html.substring(gt + 1);

            WebResourceResponse res = new WebResourceResponse("text/html", "utf-8",
                    new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("Content-Type", "text/html; charset=utf-8");
            // CSP başlıklarını bilerek iletmiyoruz: kendi enjekte ettiğimiz
            // satır içi script'in çalışabilmesi için.
            res.setResponseHeaders(headers);
            return res;
        } catch (Exception e) {
            Log.w(TAG, "html yeniden yazma başarısız (" + e.getMessage() + ") — normal yükleme");
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /* WebView'i (yeniden) kurar — ilk açılışta ve renderer çöktüğünde
       (rebuildWebView) kullanılır. */
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void createWebView() {
        web = new KeepAliveWebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        // Reklam sonrası videonun kendiliğinden devam etmesi için şart
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setGeolocationEnabled(false);
        // UA'dan WebView ("wv") ibaresini kaldır; Google girişi webview'e takılmasın
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));
        uaString = s.getUserAgentString(); // arka plan iş parçacığından kullanılacak

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                String us = url == null ? "" : url.toString();
                for (Pattern p : BLOCK_RE) {
                    if (p.matcher(us).find()) {
                        blocked.incrementAndGet();
                        runOnUiThread(MainActivity.this::updateChip);
                        return new WebResourceResponse("text/plain", "utf-8",
                                new ByteArrayInputStream(new byte[0]));
                    }
                }
                // Ana belge (HTML) isteklerini kendimiz indirip <head>'in başına
                // preinject.js gömeriz: script YouTube'un kodundan ÖNCE çalışır ve
                // player verisinden reklam alanlarını siler (masaüstü eklenti tekniği).
                if (request.isForMainFrame() && "GET".equals(request.getMethod()) && preinjectSrc != null) {
                    String host = url == null || url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
                    if (host.endsWith("youtube.com") && !us.contains("/embed/")) {
                        WebResourceResponse rewritten = rewriteHtml(us);
                        if (rewritten != null) return rewritten;
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                // Uygulama açma (intent://, vnd.youtube://, market:// vb.) özel
                // şemalar sessizce yutulur — hata sayfası/toast çıkmasın
                String scheme = u.getScheme();
                if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
                    return true;
                }
                String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
                if (host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com")
                        || host.endsWith("googlevideo.com") || host.endsWith("google.com")
                        || host.endsWith("googleusercontent.com") || host.endsWith("ytimg.com")
                        || host.endsWith("gstatic.com") || host.endsWith("googleapis.com")
                        || host.endsWith("gvt1.com") || host.endsWith("youtubeeducation.com")) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && url.startsWith("http")) lastUrl = url;
                if (swipe != null) swipe.setRefreshing(false);
                positionChip(url);
                if (injectSrc != null) view.evaluateJavascript(injectSrc, null);
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                if (url != null && url.startsWith("http")) lastUrl = url;
                positionChip(url);
            }

            /* Video motoru (renderer) çökince WebView simsiyah kalır ve ses ölür.
             * Burada yakalayıp WebView'i sıfırdan kuruyoruz — kullanıcı müdahale
             * etmeden kaldığı sayfaya geri döner. */
            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                Log.e(TAG, "renderer öldü (crash=" + detail.didCrash() + ") — WebView yeniden kuruluyor");
                MainActivity a = MainActivity.instance;
                if (a != null) a.rebuildWebView("renderer-cokus");
                return true;
            }
        });

        chromeClient = new MyChromeClient();
        web.setWebChromeClient(chromeClient);
        web.addJavascriptInterface(new Bridge(), "FireTube");
        lastStatusAt = System.currentTimeMillis();
        swipe.addView(web, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /* Renderer çöktüğünde/donduğunda çağrılır: eski WebView'i temizleyip
       yeni kurar ve kalınan sayfayı geri yükler (hesap açık olduğu için
       video kaldığı yerden devam eder). */
    /* Kalıcı günlük: logcat tamponu döndüğünde bile gece olan biten
       sonra incelenebilsin. files/uyku.log (256 KB üstünde döndürülür). */
    private File logFile = null;
    private long logSize = 0;

    public synchronized void appLog(String m) {
        try {
            if (logFile == null) {
                logFile = new File(getFilesDir(), "uyku.log");
                logSize = logFile.exists() ? logFile.length() : 0;
            }
            if (logSize > 256 * 1024) {
                File old = new File(getFilesDir(), "uyku.old.log");
                //noinspection ResultOfMethodCallIgnored
                old.delete();
                //noinspection ResultOfMethodCallIgnored
                logFile.renameTo(old);
                logFile = new File(getFilesDir(), "uyku.log");
                logSize = 0;
            }
            String line = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT)
                    .format(new java.util.Date()) + " " + m + "\n";
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(logFile, true)) {
                fo.write(line.getBytes(StandardCharsets.UTF_8));
            }
            logSize += line.length();
        } catch (Exception ignored) {
        }
    }

    private static long lastRebuildAt = 0;

    public void rebuildWebView(String reason) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastRebuildAt < 120000) {
            Log.w(TAG, "kurtarma çok sık — atlandı: " + reason);
            return;
        }
        lastRebuildAt = now;
        Log.e(TAG, "WebView kurtarma: " + reason);
        appLog("KURTARMA: " + reason);
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (customView != null) {
                try { root.removeView(customView); } catch (Exception ignored) {}
                customView = null;
            }
            try {
                swipe.removeView(web);
                web.loadUrl("about:blank");
                web.destroy();
            } catch (Exception ignored) {}
            String url = lastUrl != null ? lastUrl : START_URL;
            createWebView();
            web.loadUrl(url);
        });
    }

    private class MyChromeClient extends WebChromeClient {
        @Override
        public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
            Log.d(TAG, "console[" + cm.messageLevel() + "] " + cm.message() + " @" + cm.lineNumber());
            return true;
        }

        @Override
        public void onShowCustomView(View v, CustomViewCallback callback) {
            if (customView != null) {
                callback.onCustomViewHidden();
                return;
            }
            customView = v;
            root.addView(v, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            web.setVisibility(View.GONE);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            hideCallback = callback;
        }

        @Override
        public void onHideCustomView() {
            if (customView == null) return;
            root.removeView(customView);
            customView = null;
            web.setVisibility(View.VISIBLE);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            if (hideCallback != null) {
                hideCallback.onCustomViewHidden();
                hideCallback = null;
            }
            kickWebViewSurface();
        }

        private CustomViewCallback hideCallback;
    }

    /* Video durunca servisi hemen değil 5 dakika sonra durdur: reklam
       geçişlerindeki kısa duraklamalarda gözcüler (yerel tick) ayakta kalsın. */
    private final android.os.Handler bridgeHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable delayedServiceStop = () -> PlaybackService.stop(MainActivity.this);

    private class Bridge {
        @JavascriptInterface
        public void onPlaying(boolean playing) {
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (playing) {
                    bridgeHandler.removeCallbacks(delayedServiceStop);
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    askNotifPermissionOnce();
                    askBatteryOnce();
                    PlaybackService.start(MainActivity.this);
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    bridgeHandler.removeCallbacks(delayedServiceStop);
                    bridgeHandler.postDelayed(delayedServiceStop, 5 * 60 * 1000L);
                }
            });
        }

        @JavascriptInterface
        public void onAdBlocked() {
            runOnUiThread(() -> {
                blocked.incrementAndGet();
                updateChip();
            });
        }

        @JavascriptInterface
        public void log(String msg) {
            Log.d(TAG, "[player] " + msg);
            appLog("[player] " + msg);
        }
    }

    private void askNotifPermissionOnce() {
        if (askedNotifPermission || Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == PackageManager.PERMISSION_GRANTED) return;
        askedNotifPermission = true;
        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
    }

    /* MIUI/Android pil kısıtlaması gece ağ'ı kısıtlayıp sesin gitmesine yol
     * açabilir; ilk oynatışta sistemden muafiyet onayı istenir. */
    private void askBatteryOnce() {
        if (askedBattery) return;
        askedBattery = true;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            Log.d(TAG, "pil muafiyeti istenemedi: " + e.getMessage());
        }
    }

    public void updateChip() {
        String t = PlaybackService.timerText();
        chip.setText("🛡 " + blocked.get() + (t.isEmpty() ? "" : "  ⏱ " + t));
    }

    /* Video sayfasında gösterge videonun en üst kenarına (çakışmasın diye),
       diğer sayfalarda YouTube başlık şeridinin altına konur. */
    private void positionChip(String url) {
        if (chip == null) return;
        boolean watch = url != null && url.contains("/watch");
        float d = getResources().getDisplayMetrics().density;
        int m = (int) (d * (watch ? 4 : 72));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) chip.getLayoutParams();
        if (lp.topMargin != m) {
            lp.topMargin = m;
            chip.requestLayout();
        }
    }

    private void showMenu() {
        final long[] mins = {0, 15, 30, 45, 60, 90, 120};
        String[] items = new String[]{
                "⏱ Zamanlayıcı: KAPALI",
                "⏱ 15 dakika", "⏱ 30 dakika", "⏱ 45 dakika",
                "⏱ 60 dakika", "⏱ 90 dakika", "⏱ 120 dakika",
                "🏠 Ana sayfa",
                "⟳ Yenile",
                "🔋 Arka plan çalma izni (pil)",
        };
        String versionName = "1.5";
        try {
            versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        new AlertDialog.Builder(this)
                .setTitle("FireTube v" + versionName)
                .setItems(items, (d, which) -> {
                    if (which <= 6) {
                        PlaybackService.setTimer(mins[which]);
                        updateChip();
                    } else if (which == 7) {
                        web.loadUrl(START_URL);
                    } else if (which == 8) {
                        web.reload();
                    } else if (which == 9) {
                        requestBatteryException();
                    }
                })
                .setNegativeButton("Kapat", null)
                .show();
    }

    /* MIUI/xiaomi pil kısıtlaması ekran kapalıyken uygulamayı dondurabilir;
     sistem "pil optimizasyonundan muaf" onayı ister. */
    private void requestBatteryException() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, "Zaten muaf — pil kısıtlaması kapalı 👍", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
            Toast.makeText(this, "Açılan pencerede \"İzin ver\" de", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Ayarlar açılamadı: Ayarlar → Pil → FireTube", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            chromeClient.onHideCustomView();
        } else if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    /* Video oynatılırken ekran kapalıyken sesin devam etmesi için
       WebView'i duraklatmıyoruz (web.onPause çağrılmıyor). */
    @Override
    protected void onPause() {
        super.onPause();
    }

    /* WebView çizim yüzeyi (surface) uygulama arka plandayken ölebiliyor:
       ses ve JS devam eder ama ekran simsiyah kalır (yalnızca süreç yeniden
       başlatınca düzelirdi). Kısa bir görünmez→görünür geçişi yüzeyi yeniden
       bağlar; ekran her açıldığında otomatik uygulanır. */
    public void kickWebViewSurface() {
        runOnUiThread(() -> {
            if (web == null || isFinishing() || isDestroyed()) return;
            try {
                web.setVisibility(View.INVISIBLE);
                web.post(() -> {
                    try {
                        web.setVisibility(View.VISIBLE);
                    } catch (Exception ignored) {
                    }
                });
            } catch (Exception ignored) {
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.resumeTimers();
        kickWebViewSurface();
    }

    @Override
    protected void onDestroy() {
        instance = null;
        PlaybackService.stop(this);
        web.loadUrl("about:blank");
        web.destroy();
        super.onDestroy();
    }

    /* Ekran kapanınca Android pencereye GONE görünürlüğü bildirir ve Chromium
       WebView medyayı native olarak duraklatır (JS'ten engellenemez). Görünürlük
       bildirimini yutarak sesin ekran kapalıyken de akmasını sağlıyoruz. */
    private static class KeepAliveWebView extends WebView {
        KeepAliveWebView(Context c) { super(c); }

        @Override
        public int getWindowVisibility() {
            return View.VISIBLE;
        }

        @Override
        protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(View.VISIBLE);
        }
    }

    private String readAsset(String name) {
        try (InputStream is = getAssets().open(name);
             BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "inject.js okunamadı", e);
            return null;
        }
    }
}
