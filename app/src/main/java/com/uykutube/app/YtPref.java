package com.uykutube.app;

/* YouTube'un PREF çerezi ("tz=Europe.Istanbul&f5=30000&f6=400" gibi). f6 onaltılık
   bir bit alanı: 0x400 koyu tema, 0x80000 açıkça seçilmiş açık tema; ikisi de
   yoksa YouTube cihaz temasını izler (TV kutusundaki eski WebView'de: açık). */
final class YtPref {
    static final long DARK = 0x400L;
    static final long LIGHT = 0x80000L;

    private YtPref() {
    }

    // CookieManager.getCookie çıktısından ("a=1; PREF=...; b=2") PREF değeri; yoksa ""
    static String prefValue(String cookieHeader) {
        if (cookieHeader == null) return "";
        for (String part : cookieHeader.split(";")) {
            String p = part.trim();
            if (p.startsWith("PREF=")) return p.substring(5);
        }
        return "";
    }

    // Koyu tema biti eklenmiş yeni değer; değişiklik gerekmiyorsa null
    static String withDarkTheme(String pref) {
        if (pref == null) pref = "";
        String[] parts = pref.isEmpty() ? new String[0] : pref.split("&");
        StringBuilder out = new StringBuilder();
        boolean found = false;
        for (String part : parts) {
            if (part.isEmpty()) continue;
            String item = part;
            if (part.startsWith("f6=")) {
                found = true;
                long bits;
                try {
                    bits = Long.parseLong(part.substring(3), 16);
                } catch (NumberFormatException e) {
                    return null; // tanımadığımız biçim: dokunma
                }
                if ((bits & (DARK | LIGHT)) != 0) return null;
                item = "f6=" + Long.toHexString(bits | DARK);
            }
            if (out.length() > 0) out.append('&');
            out.append(item);
        }
        if (!found) {
            if (out.length() > 0) out.append('&');
            out.append("f6=").append(Long.toHexString(DARK));
        }
        return out.toString();
    }
}
