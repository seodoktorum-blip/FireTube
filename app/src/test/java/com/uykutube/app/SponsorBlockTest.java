package com.uykutube.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SponsorBlockTest {
    @Test
    public void hashPrefixIsFirstFourHexOfSha256() {
        // python: hashlib.sha256(b'dQw4w9WgXcQ').hexdigest()[:4]
        assertEquals("5f6b", SponsorBlock.hashPrefix("dQw4w9WgXcQ"));
    }

    @Test
    public void videoIdValidation() {
        assertTrue(SponsorBlock.validId("dQw4w9WgXcQ"));
        assertTrue(SponsorBlock.validId("aQp-iM0bCs4"));
        assertFalse(SponsorBlock.validId(null));
        assertFalse(SponsorBlock.validId("kisa"));
        assertFalse(SponsorBlock.validId("dQw4w9WgXc<"));
    }

    @Test
    public void onlyOurVideoSkipSegmentsInWantedCategoriesSorted() {
        String json = "["
                + "{\"videoID\":\"BASKAVIDEO1\",\"segments\":[{\"category\":\"sponsor\",\"actionType\":\"skip\",\"segment\":[1,50]}]},"
                + "{\"videoID\":\"aQp-iM0bCs4\",\"segments\":["
                + "{\"category\":\"interaction\",\"actionType\":\"skip\",\"segment\":[300.5,310]},"
                + "{\"category\":\"sponsor\",\"actionType\":\"skip\",\"segment\":[0,31.861]},"
                + "{\"category\":\"sponsor\",\"actionType\":\"mute\",\"segment\":[40,45]},"
                + "{\"category\":\"outro\",\"actionType\":\"skip\",\"segment\":[340,361]},"
                + "{\"category\":\"selfpromo\",\"actionType\":\"skip\",\"segment\":[100,100.4]},"
                + "{\"category\":\"selfpromo\",\"segment\":[200,215]}"
                + "]}]";
        assertEquals("[[0.000,31.861],[200.000,215.000],[300.500,310.000]]",
                SponsorBlock.parse(json, "aQp-iM0bCs4"));
    }

    @Test
    public void brokenOrEmptyResponsesGiveEmptyList() {
        assertEquals("[]", SponsorBlock.parse("", "aQp-iM0bCs4"));
        assertEquals("[]", SponsorBlock.parse("<html>", "aQp-iM0bCs4"));
        assertEquals("[]", SponsorBlock.parse("[]", "aQp-iM0bCs4"));
        assertEquals("[]", SponsorBlock.parse("[{\"videoID\":\"aQp-iM0bCs4\",\"segments\":[{\"category\":\"sponsor\",\"segment\":[\"x\",5]}]}]", "aQp-iM0bCs4"));
    }
}
