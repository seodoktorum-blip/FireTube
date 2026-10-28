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
import android.view.KeyEvent;
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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {
    private static final String TAG = "FireTube";
    private static final String START_URL = "https://m.youtube.com/";

    public static volatile MainActivity instance;

    // reklam engelleme verisi: kurallar, ABP listeleri, uBlock anahtarlari
    AdFilter filter;

    public WebView web;
    private FrameLayout root;
    private TextView chip;
    private SwipeRefreshLayout swipe;
    /* WebView'in kalıcı kabı. SwipeRefreshLayout ilk çocuğunu (mTarget) bir kez
       kaydeder ve hep onu ölçer/yerleştirir; WebView doğrudan çocuğu olunca
       kurtarmada (rebuildWebView) eski WebView sökülüp yenisi eklendiğinde yeni
       WebView hiç ölçülmüyor, 0x0 kalıyordu → ses sürer ama ekran simsiyah
       ("ekranı kapatıp yarım saat sonra açınca siyah": gece renderer ölümü +
       kurtarma). Kap hep aynı kalır, değişen yalnız içindeki WebView. */
    private FrameLayout webBox;
    private final AtomicInteger blocked = new AtomicInteger();
    private String injectSrc;
    private String preinjectSrc;
    private volatile String uaString = null;
    private boolean askedNotifPermission = false;
    private boolean askedBattery = false;
    private volatile String lastUrl = null;
    public volatile long lastStatusAt = 0L;

    // kurtarmada kaldığı saniyeden devam (PlaybackService durum sorgusundan)
    private volatile long lastPosSec = -1;
    private volatile long lastPosAt = 0;
    private volatile String lastPosUrl = null;

    // kilitlemeden uyuyakalınırsa: 25 dk dokunulmazsa ekranı açık tutmayı bırak
    private static final long SCREEN_HOLD_MS = 25 * 60 * 1000L;
    private long lastTouchAt = System.currentTimeMillis();
    volatile boolean videoPlaying = false;
    private boolean screenReleased = false;
    // Android TV kutusu: kumanda gezinmesi; sayfa CSS tam ekranını bildirir
    private boolean isTv = false;
    private boolean tvFullscreen = false;

    // internet kesintisi: ana sayfa yüklenemezse geri çekilmeli yeniden deneme
    private static final int[] NET_RETRY_S = {30, 60, 120, 300};
    private boolean mainFrameError = false;
    private int netRetries = 0;
    private String failedUrl = null;
    private android.net.ConnectivityManager.NetworkCallback netCallback;
    private long lastRewriteFailLogAt = 0;

    // v1.15: kalıcı ayarlar (menüden aç/kapa), düşük kalite kipi, SponsorBlock
    private android.content.SharedPreferences prefs;
    private boolean prefLowQuality = true;
    private boolean prefSponsor = true;
    private boolean activityStarted = false;
    private Boolean qualityLow = null; // sayfaya en son bildirilen kip
    private final SponsorBlock sponsor = new SponsorBlock();

    private View customView;
    private MyChromeClient chromeClient;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        logStartup();
        prefs = getSharedPreferences("firetube", MODE_PRIVATE);
        prefLowQuality = prefs.getBoolean("lowQuality", true);
        prefSponsor = prefs.getBoolean("sponsorBlock", true);
        injectSrc = readAsset("inject.js");
        preinjectSrc = readAsset("preinject.js");
        isTv = ((android.app.UiModeManager) getSystemService(UI_MODE_SERVICE)).getCurrentModeType()
                == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION;

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
        webBox = new FrameLayout(this);
        swipe.addView(webBox, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
        if (isTv) setupTvChip();
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

        // Diskteki kurallar EŞZAMANLI uygulanır: ilk sayfa da güncel anahtarlarla
        // açılsın (uzak çekim/listeler arkadan gelip açık sayfaya itilir)
        filter = new AdFilter(this, this::pushKeysToPage);
        filter.applyStored();
        filter.refreshRules();
        filter.refreshLists();

        createWebView();
        registerNetwork();

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else if (lastUrl == null) {
            lastUrl = START_URL;
        }
        ensureDarkTheme();
        web.loadUrl(lastUrl);
    }

    /* YouTube koyu teması: PREF çerezinin f6 alanına 0x400 eklenir. Eski
       WebView'ler (TV kutusu: 80) sistemin koyu tercihini sayfaya iletmiyor,
       tema açık kalıyordu. YouTube'da açık tema açıkça seçildiyse dokunulmaz. */
    private void ensureDarkTheme() {
        try {
            CookieManager cm = CookieManager.getInstance();
            String pref = YtPref.withDarkTheme(YtPref.prefValue(cm.getCookie(START_URL)));
            if (pref == null) return;
            cm.setCookie(START_URL, "PREF=" + pref + "; Domain=.youtube.com; Path=/; Max-Age=63072000; Secure");
            cm.flush();
        } catch (Exception e) {
            Log.w(TAG, "koyu tema çerezi yazılamadı: " + e.getMessage());
        }
    }

    /* TV: 🛡 rozeti kumandayla seçilebilir (sayfanın en üstünden yukarı);
       seçiliyken belirgin, aşağı/sola basınca odak sayfaya döner. */
    private void setupTvChip() {
        chip.setFocusable(true);
        chip.setOnFocusChangeListener((v, has) ->
                chip.setBackgroundColor(has ? 0xFF3EA6FF : 0xCC1A2B1A));
        chip.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_DPAD_DOWN && keyCode != KeyEvent.KEYCODE_DPAD_LEFT) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) tvFocusWeb();
            return true;
        });
    }

    private void tvFocusWeb() {
        if (!isTv || web == null || customView != null) return;
        web.requestFocus();
        web.evaluateJavascript("window.__uykuTvKey && window.__uykuTvKey('focus')", null);
    }

    /* TV kumandası: MENÜ tuşu uygulama menüsünü açar; medya tuşları (bildirim
       oturumu yokken etkinliğe gelir) ve tam ekran videodaki yön tuşları
       sayfadaki oynatıcıya iletilir. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!isTv || web == null) return super.dispatchKeyEvent(event);
        int code = event.getKeyCode();
        String key = null;
        switch (code) {
            case KeyEvent.KEYCODE_MENU:
                if (event.getAction() == KeyEvent.ACTION_UP) showMenu();
                return true;
            case KeyEvent.KEYCODE_SEARCH:
                key = "search";
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                key = "toggle";
                break;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                key = "right";
                break;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                key = "left";
                break;
            default:
                // Chromium'un tam ekran görünümü (customView) tuşları sayfaya iletmez
                if (customView != null) {
                    if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) key = "toggle";
                    else if (code == KeyEvent.KEYCODE_DPAD_LEFT) key = "left";
                    else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) key = "right";
                    else if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) key = "up";
                }
        }
        if (key == null) return super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            web.evaluateJavascript("window.__uykuTvKey && window.__uykuTvKey('" + key + "')", null);
        }
        return true;
    }

    /* Kurallar/uBO anahtarlari sonradan gelince acik sayfadaki on script'e
       iletilir (SPA oldugu icin sayfa yeniden yuklenmeden). */
    private void pushKeysToPage() {
        String js = filter.setKeysScript();
        runOnUiThread(() -> {
            if (web != null && !isDestroyed()) web.evaluateJavascript(js, null);
        });
    }

    /* Yönlendirme döngüsü freni: 10 sn içinde 6'dan fazla yönlendirmede
       yeniden yazma bırakılır, WebView kendisi yükler. */
    private long redirectWindowAt = 0;
    private int redirectCount = 0;

    private synchronized boolean allowRedirectRewrite() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - redirectWindowAt > 10000) {
            redirectWindowAt = now;
            redirectCount = 0;
        }
        return ++redirectCount <= 6;
    }

    /* Yanıttaki Set-Cookie'leri WebView çerez deposuna yazar: belgeyi
       kendimiz indirdiğimiz için WebView bunları görmez (oturum yenileme
       çerezleri kaybolurdu). */
    private static void storeCookies(String url, java.net.HttpURLConnection c) {
        try {
            java.util.Map<String, java.util.List<String>> h = c.getHeaderFields();
            CookieManager cm = CookieManager.getInstance();
            for (java.util.Map.Entry<String, java.util.List<String>> e : h.entrySet()) {
                if (e.getKey() == null || !e.getKey().equalsIgnoreCase("Set-Cookie")) continue;
                for (String v : e.getValue()) cm.setCookie(url, v);
            }
        } catch (Exception ignored) {
        }
    }

    private static WebResourceResponse htmlResponse(String html) {
        return htmlResponse(new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
    }

    private static WebResourceResponse htmlResponse(InputStream body) {
        WebResourceResponse res = new WebResourceResponse("text/html", "utf-8", body);
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Content-Type", "text/html; charset=utf-8");
        // CSP başlıklarını bilerek iletmiyoruz: kendi enjekte ettiğimiz
        // satır içi script'in çalışabilmesi için.
        res.setResponseHeaders(headers);
        return res;
    }

    /* Ana HTML belgesini kendimiz indirir, <head>'in hemen sonrasına
       preinject.js'i gömer ve değiştirilmiş belgeyi döndürür. Hata olursa
       null döner ve WebView normal şekilde yükler. */
    private WebResourceResponse rewriteHtml(String urlStr) {
        long start = android.os.SystemClock.elapsedRealtime();
        for (int attempt = 1; ; attempt++) {
            try {
                WebResourceResponse r = rewriteHtmlOnce(urlStr);
                if (attempt > 1 && r != null) appLog("ön script " + attempt + ". denemede eklendi");
                return r;
            } catch (Exception e) {
                // Bağlantı kurulamadıysa kısa arayla yeniden dene: gece kurtarmasında
                // ağ birkaç sn geç gelebiliyor (24 Eki: DNS çözülemedi, WebView 2 sn
                // sonra açabildi) ve ön scriptsiz sayfa tüm oturum reklam verisi alır.
                boolean noConnection = e instanceof java.net.UnknownHostException
                        || e instanceof java.net.ConnectException
                        || e instanceof java.net.NoRouteToHostException;
                if (noConnection && attempt < 3 && android.os.SystemClock.elapsedRealtime() - start < 25_000) {
                    Log.w(TAG, "html yeniden yazma: bağlantı yok (" + e.getMessage() + ") — yeniden denenecek");
                    android.os.SystemClock.sleep(2000);
                    continue;
                }
                Log.w(TAG, "html yeniden yazma başarısız (" + e.getMessage() + ") — normal yükleme");
                long now = System.currentTimeMillis();
                if (now - lastRewriteFailLogAt > 60_000) {
                    lastRewriteFailLogAt = now;
                    appLog("UYARI: ön script eklenemedi (" + e.getClass().getSimpleName() + ": "
                            + e.getMessage() + ", " + attempt + " deneme) — sayfa ön scriptsiz açılıyor");
                }
                return null;
            }
        }
    }

    private WebResourceResponse rewriteHtmlOnce(String urlStr) throws Exception {
        java.net.HttpURLConnection c = null;
        try {
            java.net.URL url = new java.net.URL(urlStr);
            c = (java.net.HttpURLConnection) url.openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            // Yönlendirme İZLENMEZ: izlenseydi hedefin içeriği (ör. accounts.google.com)
            // orijinal adresin origin'i altında sunulurdu.
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", uaString != null ? uaString : "");
            c.setRequestProperty("Accept-Language", "tr,en;q=0.9");
            // Accept-Encoding bilerek verilmez: HttpURLConnection gzip ister ve kendisi
            // açar (HTML 350 KB → 114 KB; "identity" ile TV kutusunda 4,3 sn sürüyordu)
            String cookies = CookieManager.getInstance().getCookie(urlStr);
            if (cookies != null) c.setRequestProperty("Cookie", cookies);

            int code = c.getResponseCode();
            storeCookies(urlStr, c);
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                if (loc == null || !allowRedirectRewrite()) return null;
                String target = new java.net.URL(url, loc).toString();
                if (target.equals(urlStr)) return null;
                // WebResourceResponse 3xx döndüremez: tarayıcıyı hedefe gönderen
                // küçük bir belge döner; hedef youtube.com ise o da yakalanıp ön
                // script'i alır. quote() "/" kaçışlar → </script> oluşamaz.
                return htmlResponse("<!doctype html><script>location.replace("
                        + org.json.JSONObject.quote(target) + ")</script>");
            }
            if (code != 200) return null;

            // Yalnız <head> etiketine kadar okunur (genelde ilk parça); ön script
            // araya eklenir, gerisi ağdan AKARAK verilir: WebView belgenin tamamını
            // beklemeden işlemeye başlar (zayıf TV kutusunda ilk görüntü erken gelir).
            java.io.InputStream in = c.getInputStream();
            byte[] data = new byte[16384];
            int len = 0, gt = -1, n;
            while (gt < 0 && len < 512 * 1024) {
                if (len == data.length) data = java.util.Arrays.copyOf(data, data.length * 2);
                n = in.read(data, len, data.length - len);
                if (n <= 0) break;
                len += n;
                gt = HtmlHead.end(data, len);
            }
            if (gt < 0) {
                in.close();
                return null;
            }
            byte[] script = ("<script>" + filter.bootScript() + holdBootScript() + preinjectSrc + "</script>")
                    .getBytes(StandardCharsets.UTF_8);
            java.io.InputStream body = new java.io.SequenceInputStream(java.util.Collections.enumeration(
                    java.util.Arrays.asList(
                            new ByteArrayInputStream(data, 0, gt + 1),
                            new ByteArrayInputStream(script),
                            new ByteArrayInputStream(data, gt + 1, len - gt - 1),
                            in)));
            // bağlantı, WebView akışı okuyup kapatınca kapanır
            final java.net.HttpURLConnection conn = c;
            c = null;
            return htmlResponse(new java.io.FilterInputStream(body) {
                @Override
                public void close() throws java.io.IOException {
                    try {
                        super.close();
                    } finally {
                        conn.disconnect();
                    }
                }
            });
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /* WebView'i (yeniden) kurar — ilk açılışta ve renderer çöktüğünde
       (rebuildWebView) kullanılır. */
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void createWebView() {
        web = new KeepAliveWebView(this);
        if (screenReleased) ((KeepAliveWebView) web).setHoldScreen(false);
        // yüklenene kadar koyu zemin: gece kurtarmasında beyaz parlama olmasın
        web.setBackgroundColor(Color.parseColor("#0F0F0F"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        // Reklam sonrası videonun kendiliğinden devam etmesi için şart
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setGeolocationEnabled(false);
        // UA'dan WebView ("wv") ibaresini kaldır; Google girişi webview'e takılmasın
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));
        uaString = s.getUserAgentString(); // arka plan iş parçacığından kullanılacak
        if (isTv) {
            // kumanda tuşları sayfaya gitsin (yoksa odak 🛡 rozetinde kalıyordu)
            web.setFocusable(true);
            web.setFocusableInTouchMode(true);
            web.post(web::requestFocus);
        }

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                String us = url == null ? "" : url.toString();
                String host = url == null || url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
                // ABP topluluk domain kümesi + rules.json ağ desenleri
                if (filter.shouldBlock(host, us)) {
                    blocked.incrementAndGet();
                    runOnUiThread(MainActivity.this::updateChip);
                    return new WebResourceResponse("text/plain", "utf-8",
                            new ByteArrayInputStream(new byte[0]));
                }
                // Ana belge (HTML) isteklerini kendimiz indirip <head>'in başına
                // preinject.js gömeriz: script YouTube'un kodundan ÖNCE çalışır ve
                // player verisinden reklam alanlarını siler (masaüstü eklenti tekniği).
                if (request.isForMainFrame() && "GET".equals(request.getMethod()) && preinjectSrc != null) {
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
                if (Hosts.stayInApp(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                mainFrameError = false;
                // yeni belge tam ekranda başlamaz (sayfa gerekirse yeniden bildirir)
                if (tvFullscreen) {
                    tvFullscreen = false;
                    if (chip != null) chip.setVisibility(View.VISIBLE);
                }
            }

            /* Ana sayfa yüklenemezse (gece modem yeniden başladı vb.) WebView hata
               sayfasında kalıyordu: geri çekilmeli yeniden denenir, ağ gelince hemen. */
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (!request.isForMainFrame()) return;
                mainFrameError = true;
                failedUrl = request.getUrl().toString();
                appLog("AG: sayfa yüklenemedi (" + error.getErrorCode() + " " + error.getDescription() + ")");
                scheduleNetRetry();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && url.startsWith("http")) lastUrl = url;
                if (swipe != null) swipe.setRefreshing(false);
                positionChip(url);
                if (mainFrameError) return; // hata sayfası: enjekte etme, yeniden deneme bekliyor
                if (netRetries > 0) appLog("AG: sayfa yeniden yüklendi");
                netRetries = 0;
                bridgeHandler.removeCallbacks(netRetry);
                if (injectSrc != null) view.evaluateJavascript((isTv ? "window.__uykuTv=1;" : "") + injectSrc, null);
                ensureDarkTheme(); // sonraki yüklemeler için (çıkış/girişte çerez yenilenebilir)
                // yeni sayfa kalite kipini bilmez: her yüklemede bildirilir
                if (qualityLow == null) updateQualityMode();
                else pushQualityMode();
                pushBackgroundMode();
                // Önceki duraklatma (zamanlayıcı, telefon, kullanıcı...) yeni sayfada da
                // geçerli: ön script gömülemediyse inject sonrası burada uygulanır
                // Belge başına YALNIZ bir kez karar verilir: onPageFinished SPA içi geri
                // gezinmede de geliyor; her seferinde uygulansa geri tuşuyla dönülen
                // video yeniden kilitlenirdi.
                view.evaluateJavascript("if(!window.__uykuHoldChecked){window.__uykuHoldChecked=1;"
                        + (PlaybackService.isHoldPaused()
                        ? "if(window.__uykuPause&&!window.__uykuHoldBoot)window.__uykuPause("
                        + org.json.JSONObject.quote(PlaybackService.holdOwner()) + ");"
                        : "") + "}", null);
                // "video bitince dur" sayfa yenilense de geçerli kalsın
                if (PlaybackService.isStopAtEnd()) {
                    view.evaluateJavascript("window.__uykuStopAtEnd && window.__uykuStopAtEnd(true)", null);
                }
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
                boolean crashed = detail.didCrash();
                // öncelik: 0 WAIVED (arka plan öldürmesi), 1 BOUND, 2 IMPORTANT
                appLog("RENDERER: öldü (crash=" + crashed + ", öncelik="
                        + detail.rendererPriorityAtExit() + ") — yeniden kuruluyor");
                Log.e(TAG, "renderer öldü (crash=" + crashed + ") — WebView yeniden kuruluyor");
                MainActivity a = MainActivity.instance;
                if (a != null) a.rebuildWebView("renderer-cokus", true);
                return true;
            }
        });

        chromeClient = new MyChromeClient();
        web.setWebChromeClient(chromeClient);
        web.addJavascriptInterface(new Bridge(), "FireTube");
        lastStatusAt = System.currentTimeMillis();
        webBox.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /* Renderer çöktüğünde/donduğunda çağrılır: eski WebView'i temizleyip
       yeni kurar ve kalınan sayfayı geri yükler (hesap açık olduğu için
       video kaldığı yerden devam eder). */
    public void appLog(String m) {
        AppLog.write(this, m);
    }

    private static long lastRebuildAt = 0;
    private String pendingUrl = null;
    private final Runnable loadPending = () -> {
        if (web == null || isDestroyed() || pendingUrl == null) return;
        appLog("KURTARMA: bekletilen sayfa yükleniyor");
        web.loadUrl(pendingUrl);
        pendingUrl = null;
    };

    public void rebuildWebView(String reason) {
        rebuildWebView(reason, false);
    }

    /* Kurtarma freni (2 dk'da bir): çökme döngüsünde sürekli yükleme olmasın.
       dead=true (renderer öldü): ölü WebView kullanılamaz — fren olsa da HEMEN
       yenisiyle değiştirilir, fren yalnız sayfa yüklemesini kalan süre kadar
       geciktirir (önceden ölü görünüm ekranda kalıyordu → siyah sayfa, aşağı
       çekme de ölü sayfayı kullanıyordu). dead=false (donmuş ama canlı): fren
       içinde hiçbir şey yapılmaz. */
    public void rebuildWebView(String reason, boolean dead) {
        long now = android.os.SystemClock.elapsedRealtime();
        final long wait = 120000 - (now - lastRebuildAt);
        if (wait > 0 && !dead) {
            Log.w(TAG, "kurtarma çok sık — atlandı: " + reason);
            appLog("KURTARMA: çok sık — atlandı (" + reason + ")");
            return;
        }
        // geciktirilen yüklemede fren, yüklemenin yapılacağı andan sayılır
        lastRebuildAt = wait > 0 ? now + wait : now;
        Log.e(TAG, "WebView kurtarma: " + reason);
        appLog("KURTARMA: " + reason);
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (customView != null) {
                try { root.removeView(customView); } catch (Exception ignored) {}
                customView = null;
                // tam ekrandayken kurtarma: sistem çubukları gizli kalmasın
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }
            try {
                webBox.removeView(web);
                web.loadUrl("about:blank");
                web.destroy();
            } catch (Exception ignored) {}
            // Eski sayfa "pause" olayı göndermeden yok ediliyor: çalmıyor say; yeni
            // sayfa oynarsa onPlaying(true) bunu düzeltir, oynamazsa servis kapanır
            videoPlaying = false;
            PlaybackService.onPlayState(false);
            bridgeHandler.removeCallbacks(delayedServiceStop);
            bridgeHandler.postDelayed(delayedServiceStop, 5 * 60 * 1000L);
            String url = resumeUrl(lastUrl != null ? lastUrl : START_URL);
            if (url.contains("&t=") || url.contains("?t=")) {
                appLog("KURTARMA: kaldığı yerden → " + url.replaceAll(".*[?&]t=", "t="));
            }
            bridgeHandler.removeCallbacks(loadPending);
            createWebView();
            if (webFrozen) {
                // zamanlayıcı duraklatması süreç geneli: yeni sayfa donuk açılmasın
                webFrozen = false;
                try { web.resumeTimers(); } catch (Exception ignored) {}
            }
            if (wait > 0) {
                appLog("KURTARMA: çok sık — ölü görünüm değiştirildi, sayfa "
                        + (wait / 1000) + " sn sonra yüklenecek");
                pendingUrl = url;
                bridgeHandler.postDelayed(loadPending, wait);
            } else {
                pendingUrl = null;
                web.loadUrl(url);
            }
            kickWebViewSurface(); // ekranda siyah kalmasin: yeni yuzey garantilensin
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
    private final Runnable delayedServiceStop = new Runnable() {
        @Override
        public void run() {
            // telefon görüşmesi sürerken kapatma: bitince video sürdürülecek
            if (PlaybackService.holdingForCall()) {
                bridgeHandler.postDelayed(this, 60_000L);
                return;
            }
            PlaybackService.stop(MainActivity.this);
            maybeFreezeWeb(); // servis çalışıyorduysa onDestroy'u dondurur
        }
    };
    private final Runnable netRetry = this::retryAfterNetError;

    private class Bridge {
        @JavascriptInterface
        public void onPlaying(boolean playing) {
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                videoPlaying = playing;
                PlaybackService.onPlayState(playing);
                if (playing) {
                    unfreezeWeb("video başladı");
                    bridgeHandler.removeCallbacks(delayedServiceStop);
                    if (!screenReleased) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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

        /* Sayfadaki duraklatma niyeti (userPause) değişti: sayfa yeniden
           yüklenirse yeni sayfa buna göre açılır. */
        /* Video değişti: SponsorBlock açıksa bölümler arka planda sorulur ve
           sayfaya itilir (yalnız sayılar; kimlik doğrulanmış). */
        @JavascriptInterface
        public void onVideo(String videoId) {
            if (!prefSponsor || !SponsorBlock.validId(videoId)) return;
            sponsor.lookup(videoId, (id, segs) -> runOnUiThread(() -> {
                if (web != null && !isDestroyed()) {
                    web.evaluateJavascript("window.__uykuSegments && window.__uykuSegments("
                            + org.json.JSONObject.quote(id) + "," + segs + ")", null);
                }
            }));
        }

        @JavascriptInterface
        public void onHold(boolean on) {
            PlaybackService.setHold(on);
        }

        // inject.js önyükleme kilidini devralırken Java'nın güncel niyetini sorar
        @JavascriptInterface
        public boolean isHold() {
            return PlaybackService.isHoldPaused();
        }

        @JavascriptInterface
        public void onStopAtEndDone() {
            runOnUiThread(() -> {
                PlaybackService.setStopAtEnd(false);
                appLog("VIDEO SONU: video bitti — durduruldu");
                updateChip();
            });
        }

        @JavascriptInterface
        public void onAdBlocked() {
            runOnUiThread(() -> {
                blocked.incrementAndGet();
                updateChip();
            });
        }

        /* TV: sayfanın "tam ekran" kipi (CSS ile). Geri tuşu önce bundan çıkar;
           🛡 rozeti videonun üstünde durmasın. */
        @JavascriptInterface
        public void onTvFull(boolean on) {
            runOnUiThread(() -> {
                tvFullscreen = on;
                if (chip != null) chip.setVisibility(on ? View.GONE : View.VISIBLE);
            });
        }

        // TV: sayfanın en üstünden yukarı basılınca odak uygulama menüsünün rozetine
        @JavascriptInterface
        public void focusChip() {
            runOnUiThread(() -> {
                if (chip != null && chip.getVisibility() == View.VISIBLE) chip.requestFocus();
            });
        }

        /* Köprü sayfadaki tüm frame'lere açık (iframe'ler de çağırabilir):
           günlüğe yazılan mesaj uzunluğu sınırlanır. */
        @JavascriptInterface
        public void log(String msg) {
            if (msg == null) return;
            if (msg.length() > 300) msg = msg.substring(0, 300) + "…";
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
        // TV'de bu ekran boş bir taslak: açılıp kapanıyor ve her açılışta video
        // başlarken uygulamayı duraklatıp sürdürüyordu (yüzey yenilemesi dahil)
        if (askedBattery || isTv) return;
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
        // TV (yatay, geniş): üst çubukta arama simgesinin solunda; çip şeridine binmesin
        int m = (int) (d * (isTv ? 8 : watch ? 4 : 72));
        int right = (int) (d * (isTv ? 56 : 10));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) chip.getLayoutParams();
        if (lp.topMargin != m || lp.rightMargin != right) {
            lp.topMargin = m;
            lp.rightMargin = right;
            chip.requestLayout();
        }
    }

    private void showMenu() {
        final long[] mins = {0, 15, 30, 45, 60, 90, 120};
        final boolean sae = PlaybackService.isStopAtEnd();
        java.util.List<String> items = new java.util.ArrayList<>();
        java.util.List<Runnable> acts = new java.util.ArrayList<>();
        for (int k = 0; k < mins.length; k++) {
            final long m = mins[k];
            items.add(m == 0 ? "⏱ Zamanlayıcı: KAPALI" : "⏱ " + m + " dakika");
            acts.add(() -> {
                // tek kip: dakika seçimi (ya da KAPALI) "video sonu"nu iptal eder
                applyStopAtEnd(false);
                PlaybackService.setTimer(m);
                updateChip();
            });
        }
        items.add(sae ? "🎬 Video bitince dur: İPTAL" : "🎬 Bu video bitince dur");
        acts.add(() -> {
            if (!sae) PlaybackService.setTimer(0);
            applyStopAtEnd(!sae);
            updateChip();
        });
        items.add("📶 Ekran kapalıyken 144p: " + (prefLowQuality ? "AÇIK" : "KAPALI"));
        acts.add(() -> {
            prefLowQuality = !prefLowQuality;
            prefs.edit().putBoolean("lowQuality", prefLowQuality).apply();
            appLog("AYAR: ekran kapalıyken 144p " + (prefLowQuality ? "açık" : "kapalı"));
            updateQualityMode();
        });
        items.add("⏭ SponsorBlock (sponsor atla): " + (prefSponsor ? "AÇIK" : "KAPALI"));
        acts.add(() -> {
            prefSponsor = !prefSponsor;
            prefs.edit().putBoolean("sponsorBlock", prefSponsor).apply();
            appLog("AYAR: SponsorBlock " + (prefSponsor ? "açık" : "kapalı"));
            // kapanınca bölümler silinir; açılınca mevcut video yeniden sorulur
            web.evaluateJavascript("window.__uykuSbReset && window.__uykuSbReset()", null);
        });
        items.add("🏠 Ana sayfa");
        acts.add(() -> web.loadUrl(START_URL));
        if (isTv) {
            // kumandayla alt çubuğa (Abonelikler/Siz) inmek zahmetli: menüden de gidilir
            items.add("🔍 Ara");
            acts.add(() -> web.evaluateJavascript("window.__uykuTvKey && window.__uykuTvKey('search')", null));
            items.add("📺 Abonelikler");
            acts.add(() -> web.loadUrl("https://m.youtube.com/feed/subscriptions"));
            items.add("🕘 Geçmiş");
            acts.add(() -> web.loadUrl("https://m.youtube.com/feed/history"));
        }
        items.add("⟳ Yenile");
        acts.add(() -> web.reload());
        if (!isTv) {
            items.add("🔋 Arka plan çalma izni (pil)");
            acts.add(this::requestBatteryException);
        }

        String versionName = "?";
        try {
            versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        new AlertDialog.Builder(this)
                .setTitle("FireTube v" + versionName)
                .setItems(items.toArray(new String[0]), (d, which) -> acts.get(which).run())
                .setNegativeButton("Kapat", null)
                .show();
    }

    /* MIUI/xiaomi pil kısıtlaması ekran kapalıyken uygulamayı dondurabilir;
     sistem "pil optimizasyonundan muaf" onayı ister. */
    private static final android.content.ComponentName MIUI_AUTOSTART = new android.content.ComponentName(
            "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity");

    /* Arka planda çalma izinleri. Android'in standart pil muafiyeti tek başına
       yetmez: MIUI/HyperOS'un ayrıca "Otomatik başlatma" ve uygulamaya özel pil
       kısıtlaması ("Kısıtlama yok") ayarları var — gece uygulamayı kapatan
       bunlar olabilir. Önceki "Zaten muaf 👍" mesajı bu yüzden yanıltıcıydı. */
    private void requestBatteryException() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
                Toast.makeText(this, "Açılan pencerede \"İzin ver\" de", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "Ayarlar açılamadı: Ayarlar → Pil → FireTube", Toast.LENGTH_LONG).show();
            }
            return;
        }
        String brand = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.ROOT);
        boolean hasAutostart = new Intent().setComponent(MIUI_AUTOSTART).resolveActivity(getPackageManager()) != null;
        boolean miui = hasAutostart || brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco");
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Arka planda çalma")
                .setMessage(miui
                        ? "✓ Android pil muafiyeti açık.\n\nXiaomi / HyperOS'ta ayrıca iki ayar gerekir:\n"
                        + "• Otomatik başlatma: AÇIK\n• Pil tasarrufu: Kısıtlama yok\n\n"
                        + "Bunlar kapalıysa sistem uygulamayı gece kapatabilir."
                        : "✓ Android pil muafiyeti açık — ek ayar gerekmez.")
                .setNegativeButton("Kapat", null);
        if (miui) {
            b.setPositiveButton("Pil tasarrufu", (d, w) -> {
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName())));
                    Toast.makeText(this, "Pil tasarrufu → \"Kısıtlama yok\" seç", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Açılamadı: Ayarlar → Uygulamalar → FireTube → Pil", Toast.LENGTH_LONG).show();
                }
            });
            if (hasAutostart) {
                b.setNeutralButton("Otomatik başlatma", (d, w) -> {
                    try {
                        startActivity(new Intent().setComponent(MIUI_AUTOSTART));
                        Toast.makeText(this, "Listede FireTube'u aç", Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "Açılamadı: Güvenlik → Otomatik başlatma", Toast.LENGTH_LONG).show();
                    }
                });
            }
        }
        b.show();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            chromeClient.onHideCustomView();
        } else if (isTv && tvFullscreen) {
            // TV: Geri önce sayfanın tam ekranından çıkar (izleme sayfasında kalınır);
            // sayfa tam ekranda değilse (durum eskimiş) normal geri
            web.evaluateJavascript("window.__uykuTvKey ? window.__uykuTvKey('back') : false", v -> {
                if ("true".equals(v)) return;
                tvFullscreen = false;
                chip.setVisibility(View.VISIBLE);
                onBackPressed();
            });
        } else if (web.canGoBack()) {
            // geri tuşu sayfaya dokunuş olarak ulaşmaz: kullanıcı uyanık, kilit kalksın
            web.evaluateJavascript("window.__uykuWake && window.__uykuWake()", null);
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
            if (web == null || isFinishing() || isDestroyed() || webFrozen) return;
            // Tam ekranda video customView'da çizilir; WebView'i söküp görünür
            // yapmak tam ekrandan çıkarabilir/üstüne binebilir — dokunma.
            if (customView != null) return;
            // TV: söküp takarken odak rozete kayar; rozet seçili değilse sayfaya geri verilir
            final boolean chipFocused = chip != null && chip.isFocused();
            // 1) AwContents'i duraklat/destekle: compositor yüzeyini bırakır/yeniden ister
            try { web.onPause(); } catch (Exception ignored) {}
            try { web.onResume(); } catch (Exception ignored) {}
            // 2) Gorunum agacindan sokup tak: Android tarafinda Surface yeniden olusur
            try {
                ViewGroup parent = (ViewGroup) web.getParent();
                if (parent != null) {
                    ViewGroup.LayoutParams lp = web.getLayoutParams();
                    int idx = parent.indexOfChild(web);
                    parent.removeView(web);
                    parent.addView(web, Math.max(idx, 0), lp);
                }
            } catch (Exception ignored) {}
            // 3) Gorunurluk gecisi
            try {
                web.setVisibility(View.INVISIBLE);
                web.post(() -> {
                    try { web.setVisibility(View.VISIBLE); } catch (Exception ignored) {}
                });
            } catch (Exception ignored) {}
            Log.d(TAG, "agir yuzey tekrmesi yapildi");
            appLog("YUZEY: agir tekrme");
            if (isTv && !chipFocused) web.post(web::requestFocus);
        });
    }

    /* Arka planda, video çalmıyorken ve servis kapalıyken sayfa dondurulur:
       duraklatılmış/biten YouTube sayfası arka planda CPU harcamayı sürdürüyor,
       Android uygulamayı bu yüzden "aşırı CPU" ile kapatıyordu (23 Eki: 5 dk'da
       %12). Ekrandayken ya da çalarken dondurulmaz (ses ekran kapalıyken sürmeli). */
    private boolean webFrozen = false;

    void maybeFreezeWeb() {
        if (webFrozen || web == null || isFinishing() || isDestroyed()) return;
        if (activityStarted || videoPlaying || PlaybackService.isRunning()) return;
        webFrozen = true;
        try { web.onPause(); } catch (Exception ignored) {}
        try { web.pauseTimers(); } catch (Exception ignored) {}
        appLog("DONDURMA: arka planda ve çalmıyor — sayfa donduruldu");
    }

    private void unfreezeWeb(String why) {
        if (!webFrozen || web == null) return;
        webFrozen = false;
        try { web.onResume(); } catch (Exception ignored) {}
        try { web.resumeTimers(); } catch (Exception ignored) {}
        appLog("DONDURMA: çözüldü (" + why + ")");
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityStarted = true;
        unfreezeWeb("öne geldi");
        updateQualityMode();
        if (stoppedAt > 0) {
            long away = android.os.SystemClock.elapsedRealtime() - stoppedAt;
            appLog("YASAM: öne geldi (arka planda " + fmtDur(away) + ", bellek kırpma: " + trimSinceStop + ")");
            checkBlankPending = true;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        activityStarted = false;
        stoppedAt = android.os.SystemClock.elapsedRealtime();
        trimSinceStop = "yok";
        appLog("YASAM: arka plana geçti (ekran " + (((PowerManager) getSystemService(POWER_SERVICE))
                .isInteractive() ? "açık" : "kapalı") + ")");
        updateQualityMode();
        maybeFreezeWeb();
    }

    /* --- Siyah ekran teşhisi: davranışı değiştirmez, yalnız ölçer ve yazar ---
       Uygulama arka plandan dönünce WebView alanının piksellerine bakılır
       (PixelCopy): parlak piksel neredeyse yoksa "SİYAH" yazılır. YouTube koyu
       tema olduğu için ölçüt siyah oranı değil, parlak (yazı/küçük resim)
       piksel oranıdır. Bağlam: arka planda kalma süresi, bellek kırpmaları,
       WebView ve sayfa durumu. */
    private long stoppedAt = 0;
    private String trimSinceStop = "yok";
    private boolean checkBlankPending = false;

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        trimSinceStop = trimSinceStop.equals("yok") ? String.valueOf(level) : trimSinceStop + "," + level;
        appLog("BELLEK: kırpma seviyesi=" + level);
    }

    private static String fmtDur(long ms) {
        long s = ms / 1000;
        return s < 120 ? s + " sn" : (s / 60) + " dk";
    }

    private void checkBlank(String tag) {
        if (Build.VERSION.SDK_INT < 26 || web == null || isDestroyed()) return;
        // ölçüm sırasında uygulama yeniden arka plana geçtiyse (ekran birkaç sn
        // açılıp kapandı) pencerenin yüzeyi yoktur: ölçülecek bir şey yok
        if (!activityStarted) return;
        if (customView != null) {
            appLog("SIYAH-TEST " + tag + ": tam ekran — atlandı");
            return;
        }
        int w = web.getWidth(), h = web.getHeight();
        if (w < 100 || h < 400) {
            appLog("SIYAH-TEST " + tag + ": WebView boyutu " + w + "x" + h + " — ölçülemedi");
            return;
        }
        int[] loc = new int[2];
        web.getLocationInWindow(loc);
        // üst çeyrek atlanır: 🛡 rozeti (yerel görünüm) ölçümü bozmasın
        android.graphics.Rect r = new android.graphics.Rect(loc[0], loc[1] + h / 4, loc[0] + w, loc[1] + h);
        final android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                Math.max(1, r.width() / 8), Math.max(1, r.height() / 8), android.graphics.Bitmap.Config.ARGB_8888);
        final String state = "bağlı=" + web.isAttachedToWindow() + " görünür=" + (web.getVisibility() == View.VISIBLE)
                + " boyut=" + w + "x" + h + " katman=" + web.getLayerType();
        try {
            android.view.PixelCopy.request(getWindow(), r, bmp, result -> {
                if (result != android.view.PixelCopy.SUCCESS) {
                    appLog("SIYAH-TEST " + tag + ": kopyalanamadı (kod " + result + ") | " + state);
                    return;
                }
                int bright = 0, n = 0;
                for (int y = 0; y < bmp.getHeight(); y++) {
                    for (int x = 0; x < bmp.getWidth(); x++) {
                        int c = bmp.getPixel(x, y);
                        int l = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
                        if (l > 60) bright++;
                        n++;
                    }
                }
                double frac = n == 0 ? 0 : bright / (double) n;
                final String verdict = frac < 0.002 ? "SİYAH" : "normal";
                if (frac >= 0.002) return; // normal: günlüğe yazma
                final String pct = String.format(Locale.ROOT, "%.2f%%", frac * 100);
                // sayfa canlı mı? (renderer yanıt veriyor mu, video durumu)
                web.evaluateJavascript("(window.__uykuStatus?window.__uykuStatus():'inject-yok')"
                                + "+' vis='+document.visibilityState",
                        v -> appLog("SIYAH-TEST " + tag + ": parlak=" + pct + " → " + verdict
                                + " | " + state + " | sayfa: " + v));
            }, new android.os.Handler(android.os.Looper.getMainLooper()));
        } catch (Exception e) {
            appLog("SIYAH-TEST " + tag + ": hata " + e);
        }
    }

    /* Ekran kapalı ya da uygulama arka plandayken en düşük kalite. Kilit
       ekranı açıkken (ekran açık, kilit duruyor) eski kaliteye dönülür:
       YouTube yeni kaliteye ~25-30 sn'de geçtiği için kilit açılırken hazır olsun.
       Kip değişince sayfaya iletilir; PlaybackService her tikte çağırır.
       Aynı yerde arka plan kipi de izlenir (ekran kapalı ya da uygulama arka
       planda): sayfa o sırada sık denetim ve görsel temizliği bırakır. */
    private Boolean backgroundMode = null;

    void updateQualityMode() {
        boolean interactive = ((PowerManager) getSystemService(POWER_SERVICE)).isInteractive();
        boolean bg = !interactive || !activityStarted;
        if (backgroundMode == null || backgroundMode != bg) {
            backgroundMode = bg;
            pushBackgroundMode();
        }
        boolean low = false;
        if (prefLowQuality) {
            boolean locked = ((android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE)).isKeyguardLocked();
            low = !interactive || (!activityStarted && !locked);
        }
        if (qualityLow != null && qualityLow == low) return;
        boolean first = qualityLow == null;
        qualityLow = low;
        pushQualityMode();
        if (!first || low) appLog("KALITE: " + (low ? "144p (ekran kapalı / arka plan)" : "eski ayar"));
    }

    private void pushQualityMode() {
        if (web != null && qualityLow != null) {
            web.evaluateJavascript("window.__uykuLowQuality && window.__uykuLowQuality(" + qualityLow + ")", null);
        }
    }

    private void pushBackgroundMode() {
        if (web != null && backgroundMode != null) {
            web.evaluateJavascript("window.__uykuBackground && window.__uykuBackground(" + backgroundMode + ")", null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        lastTouchAt = System.currentTimeMillis();
        restoreScreenHold("uygulamaya dönüldü");
        web.resumeTimers();
        kickWebViewSurface();
        if (checkBlankPending) {
            checkBlankPending = false;
            bridgeHandler.postDelayed(() -> checkBlank("+2 sn"), 2000);
            bridgeHandler.postDelayed(() -> checkBlank("+15 sn"), 15000);
        }
    }

    @Override
    protected void onDestroy() {
        instance = null;
        // uygulama kapanınca zamanlayıcı/"video sonu" da biter (servis durunca bitmez)
        PlaybackService.setTimer(0);
        PlaybackService.setStopAtEnd(false);
        PlaybackService.setHold(false);
        PlaybackService.stop(this);
        bridgeHandler.removeCallbacksAndMessages(null);
        if (netCallback != null) {
            try {
                ((android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE))
                        .unregisterNetworkCallback(netCallback);
            } catch (Exception ignored) {
            }
        }
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

        /* Chromium video çalarken ekranı açık tutar (setKeepScreenOn). Uyuyakalınca
           bırakılabilsin diye istek kaydedilir, holdScreen kapalıyken uygulanmaz. */
        boolean holdScreen = true;
        private boolean chromiumWantsOn = false;

        @Override
        public void setKeepScreenOn(boolean on) {
            chromiumWantsOn = on;
            super.setKeepScreenOn(on && holdScreen);
        }

        void setHoldScreen(boolean hold) {
            holdScreen = hold;
            super.setKeepScreenOn(chromiumWantsOn && hold);
            if (!hold) enforceRelease();
        }

        // Chromium bayrağı bir alt görünüme (anchor) de koyabilir: her tikte temizlenir
        void enforceRelease() {
            if (holdScreen) return;
            if (getKeepScreenOn()) super.setKeepScreenOn(false);
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (c.getKeepScreenOn()) c.setKeepScreenOn(false);
            }
        }
    }

    /* --- v1.14 yardımcıları --- */

    void applyStopAtEnd(boolean on) {
        PlaybackService.setStopAtEnd(on);
        if (web != null) web.evaluateJavascript("window.__uykuStopAtEnd && window.__uykuStopAtEnd(" + on + ")", null);
        if (on) appLog("VIDEO SONU: açıldı");
    }

    /* Video duraklatılmışken sayfa yeniden yüklenirse yeni sayfa duraklatılmış
       açılsın: ön script YouTube'un autoplay'ini en baştan engeller. */
    private String holdBootScript() {
        if (!PlaybackService.isHoldPaused()) return "";
        return "window.__uykuHoldBoot=1;window.__uykuHoldOwner="
                + org.json.JSONObject.quote(PlaybackService.holdOwner()) + ";";
    }

    /* PlaybackService durum sorgusundan son konum (reklam/canlı hariç). */
    void notePosition(long sec) {
        lastPosSec = sec;
        lastPosAt = System.currentTimeMillis();
        lastPosUrl = lastUrl;
    }

    /* Kurtarmada kaldığı saniyeden devam: izleme geçmişi kapalıysa video baştan
       başlıyordu (günlükte 43. dk → 4. dk). Yalnız aynı video ve taze konum. */
    private String resumeUrl(String url) {
        if (lastPosSec < 10 || System.currentTimeMillis() - lastPosAt > 3 * 60_000L) return url;
        try {
            Uri u = Uri.parse(url);
            if (!"/watch".equals(u.getPath()) || lastPosUrl == null) return url;
            String vid = u.getQueryParameter("v");
            if (vid == null || !vid.equals(Uri.parse(lastPosUrl).getQueryParameter("v"))) return url;
            Uri.Builder b = u.buildUpon().clearQuery();
            for (String k : u.getQueryParameterNames()) {
                if ("t".equals(k)) continue;
                for (String v : u.getQueryParameters(k)) b.appendQueryParameter(k, v);
            }
            b.appendQueryParameter("t", (lastPosSec - 3) + "s");
            return b.build().toString();
        } catch (Exception e) {
            return url;
        }
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        lastTouchAt = System.currentTimeMillis();
        restoreScreenHold("dokunuldu");
    }

    private void restoreScreenHold(String why) {
        if (!screenReleased) return;
        screenReleased = false;
        if (web instanceof KeepAliveWebView) ((KeepAliveWebView) web).setHoldScreen(true);
        if (videoPlaying) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        appLog("EKRAN: " + why + " — açık tutma geri geldi");
    }

    /* PlaybackService her tikte çağırır. Video çalarken ekran açık tutulur; ama
       kilitlemeden uyuyakalınınca bütün gece yanıyordu. 25 dk dokunulmazsa (tam
       ekran hariç) açık tutma bırakılır, sistem zaman aşımı ekranı kapatır; ses sürer. */
    void checkScreenHold() {
        if (!(web instanceof KeepAliveWebView)) return;
        KeepAliveWebView kw = (KeepAliveWebView) web;
        if (screenReleased) {
            if (kw.holdScreen) kw.setHoldScreen(false); // yeniden kurulmuş WebView
            else kw.enforceRelease();
            return;
        }
        if (!videoPlaying || customView != null) return;
        // TV'de film izlerken 25 dk'da ekran koruyucu gelmesin: TV'yi uyku
        // zamanlayıcısı kapatır (telefondaki "uyuyakaldı" senaryosu yok)
        if (isTv) return;
        // Ekran zaten kapalıysa bırakmanın faydası yok (kilit açılınca geri
        // gelmesi de gerekmesin): yalnız ekran açıkken karar verilir
        if (!((PowerManager) getSystemService(POWER_SERVICE)).isInteractive()) return;
        if (System.currentTimeMillis() - lastTouchAt < SCREEN_HOLD_MS) return;
        screenReleased = true;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        kw.setHoldScreen(false);
        appLog("EKRAN: " + (SCREEN_HOLD_MS / 60000) + " dk dokunulmadı — açık tutma bırakıldı");
    }

    private void scheduleNetRetry() {
        bridgeHandler.removeCallbacks(netRetry);
        if (netRetries >= 12) {
            appLog("AG: yeniden deneme sınırı doldu (12)");
            return;
        }
        int sec = NET_RETRY_S[Math.min(netRetries, NET_RETRY_S.length - 1)];
        netRetries++;
        bridgeHandler.postDelayed(netRetry, sec * 1000L);
    }

    private void retryAfterNetError() {
        if (web == null || isDestroyed() || !mainFrameError) return;
        String u = failedUrl != null ? failedUrl : (lastUrl != null ? lastUrl : START_URL);
        appLog("AG: yeniden deneniyor (" + netRetries + ")");
        web.loadUrl(u);
    }

    /* Ağ durumu: navigator.onLine'ı gerçeğe bağlar (inject.js ağ yokken
       yenilemez) ve bağlantı gelince hata sayfasından hemen kurtarır. Ağ
       değişimlerinde kısa kopmalar 3 sn ertelemeyle yutulur. */
    private final Runnable netLost = () -> {
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm.getActiveNetwork() != null) return; // bu arada başka ağa geçildi
        } catch (Exception ignored) {
        }
        if (web != null) web.setNetworkAvailable(false);
        appLog("AG: bağlantı yok");
    };

    private void registerNetwork() {
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            netCallback = new android.net.ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(android.net.Network n) {
                    runOnUiThread(() -> {
                        bridgeHandler.removeCallbacks(netLost);
                        if (web != null) web.setNetworkAvailable(true);
                        if (mainFrameError) {
                            appLog("AG: bağlantı geri geldi — hemen deneniyor");
                            bridgeHandler.removeCallbacks(netRetry);
                            bridgeHandler.postDelayed(netRetry, 2000);
                        }
                    });
                }

                @Override
                public void onLost(android.net.Network n) {
                    runOnUiThread(() -> {
                        bridgeHandler.removeCallbacks(netLost);
                        bridgeHandler.postDelayed(netLost, 3000);
                    });
                }
            };
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (Exception e) {
            appLog("HATA: ağ dinleyicisi kurulamadı — " + e);
        }
    }

    /* Kara kutu: açılışta sürüm bilgileri ve (Android 11+) sürecin önceki
       kapanma nedenleri — MIUI öldürmesi mi, bellek mi, çökme mi? */
    private static final String[] EXIT_REASONS = {
            "BILINMIYOR", "KENDI_CIKTI", "SINYAL", "DUSUK_BELLEK", "COKME", "NATIVE_COKME",
            "ANR", "BASLATMA_HATASI", "IZIN_DEGISTI", "ASIRI_KAYNAK", "KULLANICI_ISTEDI",
            "KULLANICI_DURDURDU", "BAGIMLILIK_OLDU", "DIGER", "DONDURUCU", "PAKET_DURUMU",
            "PAKET_GUNCELLENDI",
    };

    private void logStartup() {
        String wv = "?", ver = "?";
        try {
            android.content.pm.PackageInfo p = WebView.getCurrentWebViewPackage();
            if (p != null) wv = p.versionName;
        } catch (Exception ignored) {
        }
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        appLog("ACILIS: FireTube v" + ver + ", WebView " + wv + ", Android " + Build.VERSION.RELEASE);
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            android.content.SharedPreferences sp = getSharedPreferences("firetube", MODE_PRIVATE);
            long last = sp.getLong("lastExitTs", 0), newest = last;
            java.util.List<android.app.ApplicationExitInfo> list =
                    am.getHistoricalProcessExitReasons(getPackageName(), 0, 5);
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT);
            for (int i = list.size() - 1; i >= 0; i--) { // eskiden yeniye
                android.app.ApplicationExitInfo x = list.get(i);
                if (x.getTimestamp() <= last) continue;
                newest = Math.max(newest, x.getTimestamp());
                int r = x.getReason();
                String reason = r >= 0 && r < EXIT_REASONS.length ? EXIT_REASONS[r] : String.valueOf(r);
                String desc = x.getDescription();
                appLog("CIKIS: " + f.format(new java.util.Date(x.getTimestamp())) + " neden=" + reason
                        + " önem=" + x.getImportance() + " pss=" + (x.getPss() / 1024) + "MB"
                        + (desc != null ? " (" + desc + ")" : ""));
            }
            sp.edit().putLong("lastExitTs", newest).apply();
        } catch (Exception e) {
            appLog("HATA: çıkış nedenleri okunamadı — " + e);
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
