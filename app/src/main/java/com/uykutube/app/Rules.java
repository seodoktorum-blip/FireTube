package com.uykutube.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.regex.Pattern;

/* rules.json (GitHub / adb / önbellek) ayrıştırıcısı. Dosya JSON olarak
   doğrulanır; sayfaya ham metin ASLA gömülmez, yalnızca doğrulanmış anahtar
   dizisi (adKeysJs) gider. Geçersiz dosya null döner ve kullanılmaz. */
final class Rules {
    private static final Pattern KEY_OK = Pattern.compile("[A-Za-z0-9_$]{2,60}");

    final int version;
    final String source;
    final String adKeysJs;         // ör. ["adSlots","adPods"] — JSONArray metni
    final Pattern[] blockPatterns; // boş olabilir
    final String[] filterLists;    // boş olabilir

    private Rules(int version, String source, String adKeysJs,
                  Pattern[] blockPatterns, String[] filterLists) {
        this.version = version;
        this.source = source;
        this.adKeysJs = adKeysJs;
        this.blockPatterns = blockPatterns;
        this.filterLists = filterLists;
    }

    static Rules parse(String json, String source) {
        if (json == null) return null;
        try {
            JSONObject o = new JSONObject(json);
            String keys = sanitizeKeys(o.optJSONArray("adKeys"));
            if (keys == null) return null; // adKeys olmayan dosya kural dosyası değil

            ArrayList<Pattern> pats = new ArrayList<>();
            JSONArray bp = o.optJSONArray("blockPatterns");
            if (bp != null) {
                for (int i = 0; i < bp.length(); i++) {
                    String p = bp.optString(i, "");
                    if (p.isEmpty()) continue;
                    try {
                        pats.add(Pattern.compile(p));
                    } catch (Exception ignored) {
                    }
                }
            }

            ArrayList<String> lists = new ArrayList<>();
            JSONArray fl = o.optJSONArray("filterLists");
            if (fl != null) {
                for (int i = 0; i < fl.length(); i++) {
                    String u = fl.optString(i, "");
                    if (u.startsWith("https://") || u.startsWith("http://")) lists.add(u);
                }
            }
            return new Rules(o.optInt("version", 0), source, keys,
                    pats.toArray(new Pattern[0]), lists.toArray(new String[0]));
        } catch (Exception e) {
            return null;
        }
    }

    /* Anahtar dizisini doğrular; geçerli anahtar yoksa null. Dönen metin
       org.json çıktısıdır ("/" kaçışlı) — <script> içine güvenle gömülür. */
    static String sanitizeKeys(JSONArray a) {
        if (a == null) return null;
        JSONArray out = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            String k = a.optString(i, "");
            if (KEY_OK.matcher(k).matches()) out.put(k);
        }
        return out.length() > 0 ? out.toString() : null;
    }

    static String sanitizeKeys(String jsonArray) {
        if (jsonArray == null) return null;
        try {
            return sanitizeKeys(new JSONArray(jsonArray));
        } catch (Exception e) {
            return null;
        }
    }

    /* En yüksek sürüm kazanır; eşitlikte argüman sırası önceliklidir. */
    static Rules best(Rules... candidates) {
        Rules best = null;
        for (Rules r : candidates) {
            if (r != null && (best == null || r.version > best.version)) best = r;
        }
        return best;
    }
}
