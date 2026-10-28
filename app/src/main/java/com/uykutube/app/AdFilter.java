package com.uykutube.app;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;

/* Reklam engelleme verisi: uzak kurallar (rules.json), ağ desenleri, ABP
   topluluk listelerinden domain kümeleri ve uBlock quick-fixes anahtarları.
   Diskteki kopyalar eşzamanlı yüklenir (ilk sayfa güncel açılsın); ağdan
   tazeleme arka planda yapılır ve anahtarlar değişince dinleyiciye haber
   verilir (açık sayfaya itilsin diye). */
final class AdFilter {
    private static final String TAG = "FireTube";
    private static final long DAY_MS = 24L * 3600 * 1000;

    /* Yerlesik varsayilanlar; acilista uzaktan (GitHub rules.json) guncellenir:
       ABP'nin liste guncellemesi gibi — YouTube format degistirdiginde APK
       kurmadan sadece rules.json guncellenir. */
    private static final String RULES_URL =
            "https://raw.githubusercontent.com/seodoktorum-blip/FireTube/main/rules.json";
    private static final String UBO_QUICKFIX_URL =
            "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/quick-fixes.txt";

    /* Ağ seviyesinde engellenecek reklam/izleyici adresleri. Bilinçli olarak
       SADECE cross-domain reklam/izleyici altyapısı ve izlemesiz ping'ler burada:
       reklama ait MEDYA URL'lerini boş yanıtla kesmek video oynatıcıyı kilitliyor
       (simsiyah ekran + sessizlik). Reklamın kendisi inject.js'te oynatıcı
       seviyesinde yakalanıp sessizleştirilip 16x hızla geçilir. */
    private static final Pattern[] DEFAULT_BLOCK_RE = {
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

    /* ABP/EasyList topluluk listeleri: ||domain^ kurallarindan domain kumesi. */
    private static final String[] DEFAULT_FILTER_LISTS = {
            "https://easylist-downloads.adblockplus.org/v3/full/turkish-filters+easylist.txt",
            "https://easylist-downloads.adblockplus.org/v3/full/distraction-control-free.txt",
            "https://easylist-downloads.adblockplus.org/v3/full/fanboy-social.txt",
            "https://easylist-downloads.adblockplus.org/v3/full/fanboy-notifications.txt",
            "https://easylist-downloads.adblockplus.org/v3/full/easyprivacy.txt",
            "https://easylist-downloads.adblockplus.org/v3/full/abp-filters-anti-cv.txt",
    };

    interface Listener {
        void onKeysChanged();
    }

    private final Context ctx;
    private final Listener listener;

    private volatile Pattern[] blockRe = DEFAULT_BLOCK_RE;
    private volatile Rules rules = null; // gecerli kurallar (yalniz dogrulanmis anahtarlar sayfaya gomulur)
    private volatile HashSet<String> adDomains = new HashSet<>();
    private volatile HashSet<String> excDomains = new HashSet<>();
    private volatile boolean listsReady = false;
    // uBlock Origin topluluk anahtarlari (uAssets quick-fixes: youtube json-prune)
    private volatile String uboKeys = ""; // dogrulanmis JSON dizi metni ya da ""

    AdFilter(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    /* İstek engellensin mi? Önce ABP domain kümesi (istisnalar önce), sonra
       rules.json ağ desenleri. Herhangi bir iş parçacığından çağrılabilir. */
    boolean shouldBlock(String host, String url) {
        if (listsReady && !host.isEmpty()
                && !FilterParser.suffixMatch(host, excDomains)
                && FilterParser.suffixMatch(host, adDomains)) {
            return true;
        }
        for (Pattern p : blockRe) {
            if (p.matcher(url).find()) return true;
        }
        return false;
    }

    /* Ön script'ten önce gömülen satır: yalnız doğrulanmış anahtar dizileri
       (ham kural metni asla). */
    String bootScript() {
        Rules r = rules;
        return (r != null ? "window.__FIRE_RULES={adKeys:" + r.adKeysJs + "};" : "")
                + (uboKeys.isEmpty() ? "" : "window.__FIRE_UBO_KEYS=" + uboKeys + ";");
    }

    /* Açık sayfadaki ön script'e güncel anahtarları iten JS. */
    String setKeysScript() {
        Rules r = rules;
        String base = r != null ? r.adKeysJs : "null";
        String ubo = uboKeys.isEmpty() ? "null" : uboKeys;
        return "window.__fireSetKeys && window.__fireSetKeys(" + base + "," + ubo + ")";
    }

    /* Kurallar: (1) GitHub rules.json, (2) adb ile itilen rules.local.json,
       (3) onebellek — SURUM NUMARASI en yuksek olan kazanir (esitlikte bu sira).
       Boylece telefondayken adb'den aninda guncelleme yapilabilir; GitHub daha
       yeni yayinlayinca otomatik ona gecer. Gecersiz (bozuk JSON) dosya yok sayilir
       ve onbellege yazilmaz. */
    void applyStored() {
        Rules best = Rules.best(
                Rules.parse(readFile("rules.local.json"), "adb yerel"),
                Rules.parse(readFile("rules.json"), "onbellek"));
        if (best != null) applyRules(best);
        String ubo = Rules.sanitizeKeys(readFile("ubo-keys.json"));
        if (ubo != null) uboKeys = ubo;
    }

    void refreshRules() {
        new Thread(() -> {
            Rules remote = null;
            String body = httpGet(RULES_URL);
            if (body != null) {
                remote = Rules.parse(body, "uzak (GitHub)");
                if (remote != null) writeFile("rules.json", body);
                else log("KURALLAR: uzak dosya gecersiz — yok sayildi");
            }
            Rules best = Rules.best(remote,
                    Rules.parse(readFile("rules.local.json"), "adb yerel"),
                    Rules.parse(readFile("rules.json"), "onbellek"));
            if (best != null) {
                applyRules(best);
                log("KURALLAR: " + best.source + " (v" + best.version + ")");
                listener.onKeysChanged();
            } else {
                log("KURALLAR: yerlesik varsayilanlar");
            }
        }).start();
    }

    private void applyRules(Rules r) {
        rules = r;
        if (r.blockPatterns.length > 0) {
            blockRe = r.blockPatterns;
            Log.d(TAG, "ag desenleri guncellendi: " + r.blockPatterns.length);
        }
    }

    /* ABP topluluk listelerini indirir/ayiklar (24 saat onebellek).
       ||domain^  -> adDomains;  @@||domain^ -> excDomains. Yalniz domain-duzey
       kurallar alinir (FilterParser); oynatma altyapisi korunur. Kumeler her
       tazelemede SIFIRDAN kurulur: listeden kalkan domain engelli kalmasin. */
    void refreshLists() {
        new Thread(() -> {
            // ~90 bin satir ayiklanir: zayif cihazda (TV kutusu) acilista sayfa
            // yuklemesiyle yarismasin; listeler gelene kadar kurallar zaten etkin
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            // rules.json'da filterLists varsa onu kullan (uzaktan yapilandirilabilir)
            Rules cur = rules;
            String[] urls = cur != null && cur.filterLists.length > 0 ? cur.filterLists : DEFAULT_FILTER_LISTS;

            File dir = new File(ctx.getFilesDir(), "filterlists");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            HashSet<String> ads = new HashSet<>();
            HashSet<String> exc = new HashSet<>();
            HashSet<String> names = new HashSet<>();
            int okCount = 0;
            for (String u : urls) {
                String name = FilterParser.cacheName(u);
                names.add(name);
                File f = new File(dir, name);
                // indirme basarisizsa eski (bayat) onbellek yine kullanilir
                if (!isFresh(f) && downloadToFile(u, f)) {
                    //noinspection ResultOfMethodCallIgnored
                    f.setLastModified(System.currentTimeMillis());
                }
                if (!f.exists()) continue;
                okCount++;
                try (BufferedReader r = reader(f)) {
                    String line;
                    while ((line = r.readLine()) != null) FilterParser.parseLine(line, ads, exc);
                } catch (Exception ignored) {
                }
            }
            // artik listede olmayan eski onbellek dosyalarini temizle
            File[] old = dir.listFiles();
            if (old != null) {
                for (File f : old) {
                    //noinspection ResultOfMethodCallIgnored
                    if (!names.contains(f.getName())) f.delete();
                }
            }
            if (okCount > 0) { // hicbiri yoksa eldeki kumeler korunur
                adDomains = ads;
                excDomains = exc;
                listsReady = true;
            }
            log("LISTELER: " + okCount + "/" + urls.length + " ABP listesi aktif (" +
                    ads.size() + " domain, " + exc.size() + " istisna)");
            Log.d(TAG, "ABP listeleri hazir: " + ads.size() + " domain");
            refreshUboKeys();
        }).start();
    }

    /* uBlock Origin'in gunluk guncellenen quick-fixes listesinden YouTube
       json-prune anahtarlarini ayiklar: topluluk alan degistiginde burasi
       bizi otomatik iyilestirir. */
    private void refreshUboKeys() {
        try {
            File f = new File(ctx.getFilesDir(), "ubo-quickfixes.txt");
            if (!isFresh(f) && downloadToFile(UBO_QUICKFIX_URL, f)) {
                //noinspection ResultOfMethodCallIgnored
                f.setLastModified(System.currentTimeMillis());
            }
            if (!f.exists()) return;
            LinkedHashSet<String> keys = new LinkedHashSet<>();
            try (BufferedReader r = reader(f)) {
                String line;
                while ((line = r.readLine()) != null) FilterParser.uboKeysFromLine(line, keys);
            }
            String js = Rules.sanitizeKeys(new JSONArray(keys));
            if (js != null) {
                uboKeys = js;
                // sonraki soguk acilista ilk sayfaya eszamanli gomulebilsin
                writeFile("ubo-keys.json", js);
                log("UBO: " + keys.size() + " topluluk anahtari alindi (quick-fixes)");
                Log.d(TAG, "UBO anahtarlari: " + keys);
                listener.onKeysChanged();
            }
        } catch (Exception e) {
            Log.w(TAG, "UBO quick-fixes hatasi: " + e.getMessage());
        }
    }

    private void log(String m) {
        AppLog.write(ctx, m);
    }

    private static boolean isFresh(File f) {
        return f.exists() && System.currentTimeMillis() - f.lastModified() < DAY_MS;
    }

    private static BufferedReader reader(File f) throws java.io.IOException {
        return new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
    }

    private String readFile(String name) {
        try {
            File f = new File(ctx.getFilesDir(), name);
            if (!f.exists()) return null;
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private void writeFile(String name, String body) {
        try (FileOutputStream fo = new FileOutputStream(new File(ctx.getFilesDir(), name))) {
            fo.write(body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static String httpGet(String urlStr) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            if (c.getResponseCode() != 200) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "uzak kural cekme hatasi: " + e.getMessage());
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static boolean downloadToFile(String urlStr, File dest) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            if (c.getResponseCode() != 200) return false;
            // once gecici dosyaya: yarida kesilen indirme eski onbellegi bozmasin
            File tmp = new File(dest.getPath() + ".tmp");
            try (InputStream in = c.getInputStream();
                 FileOutputStream fo = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            }
            return tmp.renameTo(dest);
        } catch (Exception e) {
            Log.w(TAG, "liste indirme hatasi: " + urlStr + " -> " + e.getMessage());
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
