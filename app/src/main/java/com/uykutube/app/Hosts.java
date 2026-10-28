package com.uykutube.app;

import java.util.Locale;
import java.util.regex.Pattern;

/* WebView'de kalacak alan adları. Geri kalanlar sistemde (tarayıcıda) açılır. */
final class Hosts {
    private Hosts() {
    }

    // Google'ın ülke alan adları (accounts.google.com.tr, accounts.google.by,
    // google.co.uk...): giriş sonunda oturum çerezleri bunlar üzerinden
    // dağıtılıyor. Dışarı gönderilince giriş Chrome'da bitiyor, oradan da
    // youtube.com bağlantısı YouTube uygulamasını açıyordu (28 Eki, TV kutusu).
    private static final Pattern GOOGLE = Pattern.compile("(^|\\.)google\\.(com?\\.)?[a-z]{2,3}$");

    private static final String[] SUFFIXES = {
            "youtube.com", "youtube-nocookie.com", "googlevideo.com", "googleusercontent.com",
            "ytimg.com", "gstatic.com", "googleapis.com", "gvt1.com", "youtubeeducation.com",
    };

    static boolean stayInApp(String host) {
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        if (GOOGLE.matcher(host).find()) return true;
        for (String s : SUFFIXES) {
            if (host.equals(s) || host.endsWith("." + s)) return true;
        }
        return false;
    }
}
