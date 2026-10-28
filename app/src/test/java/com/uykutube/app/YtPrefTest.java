package com.uykutube.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class YtPrefTest {
    @Test
    public void extractsPrefFromCookieHeader() {
        assertEquals("tz=Europe.Istanbul&f5=30000",
                YtPref.prefValue("VISITOR_INFO1_LIVE=x; PREF=tz=Europe.Istanbul&f5=30000; YSC=y"));
        assertEquals("", YtPref.prefValue("YSC=y"));
        assertEquals("", YtPref.prefValue(null));
    }

    @Test
    public void addsDarkBitWhenThemeUnset() {
        // TV kutusu (28 Eki): f6 yokken sayfa açık temadaydı
        assertEquals("tz=Europe.Istanbul&f5=30000&f6=400",
                YtPref.withDarkTheme("tz=Europe.Istanbul&f5=30000"));
        assertEquals("f6=400", YtPref.withDarkTheme(""));
        assertEquals("f6=400", YtPref.withDarkTheme(null));
        // başka bitler korunur
        assertEquals("f6=40000400&hl=tr", YtPref.withDarkTheme("f6=40000000&hl=tr"));
    }

    @Test
    public void respectsExplicitThemeChoice() {
        assertNull(YtPref.withDarkTheme("tz=Europe.Istanbul&f6=400"));
        assertNull(YtPref.withDarkTheme("f6=80000&f5=30000"));      // açıkça açık tema
        assertNull(YtPref.withDarkTheme("f6=40080000"));
        assertNull(YtPref.withDarkTheme("f6=zz"));                  // bilinmeyen biçim
    }
}
