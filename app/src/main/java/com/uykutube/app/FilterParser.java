package com.uykutube.app;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/* ABP/EasyList ve uBlock quick-fixes satır ayrıştırıcıları. Android'e bağımlı
   değil (birim testleri JVM'de koşar). */
final class FilterParser {
    private FilterParser() {
    }

    // Video/oynatma altyapisi: bu domainlere ABP kurali denk gelse bile DOKUNMA
    static final String[] SAFE_SUFFIXES = {
            "googlevideo.com", "youtube.com", "youtube-nocookie.com", "ytimg.com",
            "gvt1.com", "ggpht.com", "googleusercontent.com", "googleapis.com",
            "gstatic.com", "google.com", "youtubeeducation.com", "yt.be", "youtu.be",
    };

    /* Siteye özgü ya da istek türünü değiştiren seçenekler: domain düzeyinde
       global engellemeye çevrilemezler, bu kurallar atlanır. */
    private static final String[] SKIP_OPTIONS = {
            "domain=", "from=", "to=", "method=", "denyallow=", "popup", "csp",
            "redirect", "removeparam", "badfilter", "header=", "permissions=",
            "replace=", "urlskip=", "~third-party", "first-party", "1p",
    };

    private static final Pattern DOMAIN_OK = Pattern.compile("[a-z0-9-]+(\\.[a-z0-9-]+)+");

    // adSlots, adPlacementRenderer, playerAds, isAd, no_ads ... (headers gibi adlar degil)
    private static final Pattern AD_SHAPED_KEY =
            Pattern.compile("^ads?[A-Z_]|^ads?$|[a-z]Ads?$|_ads?$|[a-z]Ad[A-Z]");
    private static final Pattern UBO_KEY_OK = Pattern.compile("[A-Za-z0-9_$]{3,40}");

    /* Yalnız saf domain kuralları alınır:  ||domain^  ||domain^$opts  ||domain^|
       @@ ile başlayanlar istisna kümesine gider. Yol içeren kurallar
       (||x.com/ads/ ya da ^ sonrası joker/yol) atlanır — bütün domaini engellemezler. */
    static void parseLine(String line, Set<String> ads, Set<String> exc) {
        line = line.trim();
        if (line.isEmpty()) return;
        char c0 = line.charAt(0);
        if (c0 == '!' || c0 == '[' || c0 == '#') return;
        if (line.indexOf('#') >= 0) return; // kozmetik kurallar
        boolean isException = line.startsWith("@@");
        if (isException) line = line.substring(2);
        if (!line.startsWith("||")) return;
        String rest = line.substring(2);

        int end = 0;
        while (end < rest.length() && "^/?*$|\", ".indexOf(rest.charAt(end)) < 0) end++;
        String domain = rest.substring(0, end).toLowerCase(Locale.ROOT);
        String tail = rest.substring(end);

        String opts = "";
        if (tail.startsWith("^")) tail = tail.substring(1);
        if (tail.startsWith("|")) tail = tail.substring(1);
        if (tail.startsWith("$")) {
            opts = tail.substring(1);
            tail = "";
        }
        if (!tail.isEmpty()) return; // yol/joker içeren kural
        if (!opts.isEmpty()) {
            for (String o : opts.toLowerCase(Locale.ROOT).split(",")) {
                for (String skip : SKIP_OPTIONS) {
                    if (skip.endsWith("=") ? o.startsWith(skip) : o.equals(skip)) return;
                }
            }
        }

        if (domain.length() < 4 || !DOMAIN_OK.matcher(domain).matches()) return;
        if (isSafe(domain)) return;
        if (isException) exc.add(domain);
        else ads.add(domain);
    }

    static boolean isSafe(String domain) {
        for (String safe : SAFE_SUFFIXES) {
            if (domain.equals(safe) || domain.endsWith("." + safe)) return true;
        }
        return false;
    }

    /* host'un her ust-soneksi kumede mi? (a.b.c.com -> b.c.com, c.com ...) */
    static boolean suffixMatch(String host, Set<String> set) {
        if (set.isEmpty()) return false;
        String h = host;
        while (h != null && !h.isEmpty()) {
            if (set.contains(h)) return true;
            int dot = h.indexOf('.');
            if (dot < 0 || dot >= h.length() - 1) return false;
            h = h.substring(dot + 1);
        }
        return false;
    }

    /* uBlock quick-fixes satırından YouTube json-prune anahtarlarını çıkarır.
       uBO yol siler, biz anahtarı HER derinlikte sileriz: yalnız reklam-şekilli
       adlar alınır (legacyImportant gibi uBO seçenekleri değil). */
    static void uboKeysFromLine(String line, Set<String> out) {
        if (line.startsWith("!")) return; // yorum (devre dışı kural)
        int i = line.indexOf("+js(json-prune");
        if (i < 0) return;
        if (line.indexOf("youtube") < 0 && line.indexOf("googlevideo") < 0) return;
        int comma = line.indexOf(',', i);
        if (comma < 0) return;
        int end = line.indexOf(',', comma + 1);
        if (end < 0) end = line.indexOf(')', comma);
        if (end < 0) end = line.length();
        for (String tok : line.substring(comma + 1, end).split("\\s+")) {
            if (tok.isEmpty()) continue;
            // yalniz duz anahtarlar: parantez/kozeli ayracli yollar atlanir
            if (tok.indexOf('[') >= 0 || tok.indexOf('{') >= 0 || tok.indexOf('(') >= 0) continue;
            int dot = tok.lastIndexOf('.');
            if (dot >= 0) tok = tok.substring(dot + 1);
            if (!UBO_KEY_OK.matcher(tok).matches()) continue;
            if (!AD_SHAPED_KEY.matcher(tok).find()) continue;
            out.add(tok);
        }
    }

    /* Liste önbellek dosya adı URL'den türer: rules.json'da liste sırası
       değişince başka listenin önbelleği kullanılmasın. */
    static String cacheName(String url) {
        long h = 1125899906842597L; // 64-bit dize özeti (çakışma pratikte yok)
        for (int i = 0; i < url.length(); i++) h = 31 * h + url.charAt(i);
        return "list-" + Long.toHexString(h) + ".txt";
    }
}
