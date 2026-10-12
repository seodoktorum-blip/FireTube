package com.uykutube.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/* SponsorBlock (sponsor.ajay.app) topluluk veritabanı: videoya gömülü sponsor,
   tanıtım ve "abone ol" bölümleri. Gizlilik: sunucuya video kimliği değil,
   yalnız sha256 özetinin ilk 4 karakteri gider (k-anonimlik); dönen listeden
   bizim video yerelde seçilir. Ayrıştırma Android'e bağımlı değil (JVM testi). */
final class SponsorBlock {
    private static final String API = "https://sponsor.ajay.app/api/skipSegments/";
    // Uyku için: reklam niteliğindeki bölümler. intro/outro/müzik dışı hariç.
    static final Set<String> CATEGORIES = new HashSet<>(Arrays.asList("sponsor", "selfpromo", "interaction"));
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    interface Callback {
        void on(String videoId, String segmentsJs);
    }

    // Son 50 videonun bölümleri (aynı videoya dönüşte yeniden sorulmasın)
    private final Map<String, String> cache = new LinkedHashMap<String, String>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> e) {
            return size() > 50;
        }
    };

    static boolean validId(String id) {
        return id != null && VIDEO_ID.matcher(id).matches();
    }

    static String hashPrefix(String videoId) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(videoId.getBytes(StandardCharsets.UTF_8));
            return String.format("%02x%02x", d[0], d[1]);
        } catch (Exception e) {
            return null;
        }
    }

    /* Yanıttan bizim videonun "skip" bölümlerini [[başlangıç, bitiş], ...]
       JS dizisi olarak döndürür (sıralı; 1 sn'den kısalar ve bozuklar atlanır). */
    static String parse(String json, String videoId) {
        List<double[]> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject v = arr.optJSONObject(i);
                if (v == null || !videoId.equals(v.optString("videoID"))) continue;
                JSONArray segs = v.optJSONArray("segments");
                if (segs == null) continue;
                for (int j = 0; j < segs.length(); j++) {
                    JSONObject s = segs.optJSONObject(j);
                    if (s == null || !"skip".equals(s.optString("actionType", "skip"))) continue;
                    if (!CATEGORIES.contains(s.optString("category"))) continue;
                    JSONArray se = s.optJSONArray("segment");
                    if (se == null || se.length() < 2) continue;
                    double a = se.optDouble(0, -1), b = se.optDouble(1, -1);
                    if (!(a >= 0) || !(b - a >= 1)) continue;
                    out.add(new double[]{a, b});
                }
            }
        } catch (Exception ignored) {
        }
        out.sort((x, y) -> Double.compare(x[0], y[0]));
        StringBuilder sb = new StringBuilder("[");
        for (double[] s : out) {
            if (sb.length() > 1) sb.append(',');
            sb.append('[').append(fmt(s[0])).append(',').append(fmt(s[1])).append(']');
        }
        return sb.append(']').toString();
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.3f", d);
    }

    /* Arka planda sorar; sonuç (bölüm yoksa "[]") cb ile döner. */
    void lookup(String videoId, Callback cb) {
        if (!validId(videoId)) return;
        String cached;
        synchronized (cache) {
            cached = cache.get(videoId);
        }
        if (cached != null) {
            cb.on(videoId, cached);
            return;
        }
        new Thread(() -> {
            String segs = fetch(videoId);
            if (segs == null) return; // ağ hatası: önbelleğe yazma, sonra yeniden denenir
            synchronized (cache) {
                cache.put(videoId, segs);
            }
            cb.on(videoId, segs);
        }).start();
    }

    private static String fetch(String videoId) {
        String prefix = hashPrefix(videoId);
        if (prefix == null) return null;
        HttpURLConnection c = null;
        try {
            String cats = URLEncoder.encode(new JSONArray(CATEGORIES).toString(), "UTF-8");
            String acts = URLEncoder.encode("[\"skip\"]", "UTF-8");
            c = (HttpURLConnection) new URL(API + prefix + "?categories=" + cats + "&actionTypes=" + acts).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            int code = c.getResponseCode();
            if (code == 404) return "[]"; // bu önekte hiç bölüm yok
            if (code != 200) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            }
            return parse(new String(bos.toByteArray(), StandardCharsets.UTF_8), videoId);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
