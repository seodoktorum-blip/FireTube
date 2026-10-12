package com.uykutube.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RulesTest {
    private static final String GOOD = "{\"version\":3,"
            + "\"adKeys\":[\"adSlots\",\"bad key\",\"</script><script>alert(1)\",\"playerAds\"],"
            + "\"blockPatterns\":[\"/pagead/\",\"([unclosed\"],"
            + "\"filterLists\":[\"https://x.org/l.txt\",\"javascript:alert(1)\"]}";

    @Test
    public void validFileIsParsedAndSanitized() {
        Rules r = Rules.parse(GOOD, "test");
        assertEquals(3, r.version);
        assertEquals("[\"adSlots\",\"playerAds\"]", r.adKeysJs);
        assertEquals(1, r.blockPatterns.length);           // bozuk regex atlanır
        assertEquals(1, r.filterLists.length);             // http(s) dışı atlanır
        assertEquals("https://x.org/l.txt", r.filterLists[0]);
    }

    @Test
    public void injectedScriptCanNeverCloseTheTag() {
        Rules r = Rules.parse("{\"adKeys\":[\"adSlots\"],\"not\":\"</script>\"}", "t");
        assertFalse(r.adKeysJs.contains("</"));
        assertTrue(Rules.sanitizeKeys("[\"a</b\",\"adSlots\"]").equals("[\"adSlots\"]"));
    }

    @Test
    public void invalidFilesAreRejected() {
        assertNull(Rules.parse(null, "t"));
        assertNull(Rules.parse("", "t"));
        assertNull(Rules.parse("<html>404</html>", "t"));
        assertNull(Rules.parse("{\"version\":9,\"adKeys\":[\"adSlots\"", "t")); // yarım indirme
        assertNull(Rules.parse("{\"version\":9}", "t"));                       // adKeys yok
        assertNull(Rules.parse("{\"adKeys\":[\"x y\"]}", "t"));                 // geçerli anahtar yok
    }

    @Test
    public void highestVersionWinsAndTiesKeepArgumentOrder() {
        Rules remote = Rules.parse("{\"version\":2,\"adKeys\":[\"adSlots\"]}", "uzak");
        Rules local = Rules.parse("{\"version\":5,\"adKeys\":[\"adSlots\"]}", "adb");
        Rules cache = Rules.parse("{\"version\":7,\"adKeys\":[\"adSlots\"]}", "onbellek");
        assertSame(cache, Rules.best(remote, local, cache));
        assertSame(local, Rules.best(remote, local, null));

        Rules remoteTie = Rules.parse("{\"version\":5,\"adKeys\":[\"adSlots\"]}", "uzak");
        assertSame(remoteTie, Rules.best(remoteTie, local));
        assertNull(Rules.best(null, null));
    }

    @Test
    public void missingVersionCountsAsZero() {
        Rules r = Rules.parse("{\"adKeys\":[\"adSlots\"]}", "t");
        assertEquals(0, r.version);
        assertEquals(0, r.blockPatterns.length);
        assertEquals(0, r.filterLists.length);
    }
}
