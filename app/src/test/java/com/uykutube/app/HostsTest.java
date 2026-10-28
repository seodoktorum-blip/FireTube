package com.uykutube.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HostsTest {
    @Test
    public void googleCountryDomainsStayInApp() {
        // giriş sonunda çerez dağıtımı (28 Eki: accounts.google.by Chrome'a gidiyordu)
        assertTrue(Hosts.stayInApp("accounts.google.by"));
        assertTrue(Hosts.stayInApp("accounts.google.com.tr"));
        assertTrue(Hosts.stayInApp("www.google.co.uk"));
        assertTrue(Hosts.stayInApp("accounts.google.com"));
        assertTrue(Hosts.stayInApp("google.de"));
        assertTrue(Hosts.stayInApp("ACCOUNTS.GOOGLE.COM"));
    }

    @Test
    public void youtubeAndMediaHostsStayInApp() {
        assertTrue(Hosts.stayInApp("m.youtube.com"));
        assertTrue(Hosts.stayInApp("youtube.com"));
        assertTrue(Hosts.stayInApp("rr3---sn-abc.googlevideo.com"));
        assertTrue(Hosts.stayInApp("i.ytimg.com"));
        assertTrue(Hosts.stayInApp("lh3.googleusercontent.com"));
    }

    @Test
    public void otherHostsLeave() {
        assertFalse(Hosts.stayInApp(null));
        assertFalse(Hosts.stayInApp(""));
        assertFalse(Hosts.stayInApp("example.com"));
        assertFalse(Hosts.stayInApp("evilgoogle.by"));
        assertFalse(Hosts.stayInApp("google.evil.com"));
        assertFalse(Hosts.stayInApp("notyoutube.com"));
        assertFalse(Hosts.stayInApp("instagram.com"));
    }
}
