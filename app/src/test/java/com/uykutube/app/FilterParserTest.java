package com.uykutube.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

public class FilterParserTest {
    private final Set<String> ads = new HashSet<>();
    private final Set<String> exc = new HashSet<>();

    private void parse(String... lines) {
        for (String l : lines) FilterParser.parseLine(l, ads, exc);
    }

    @Test
    public void domainRulesAreTaken() {
        parse("||ads.example.com^", "||track.example.net^$third-party", "||pixel.example.org^|",
                "||bare.example.io");
        assertEquals(new HashSet<>(Arrays.asList(
                "ads.example.com", "track.example.net", "pixel.example.org", "bare.example.io")), ads);
    }

    @Test
    public void exceptionsGoToExceptionSet() {
        parse("@@||ok.example.com^");
        assertTrue(exc.contains("ok.example.com"));
        assertTrue(ads.isEmpty());
    }

    @Test
    public void pathRulesDoNotBlockWholeDomain() {
        parse("||cdn.example.com/ads/", "||cdn.example.com^*/banner", "||cdn.example.com^/x");
        assertTrue(ads.isEmpty());
    }

    @Test
    public void siteSpecificOptionsAreSkipped() {
        parse("||a.example.com^$domain=foo.com", "||b.example.com^$popup",
                "||c.example.com^$~third-party", "||d.example.com^$script,domain=x.com",
                "||e.example.com^$badfilter");
        assertTrue(ads.isEmpty());
    }

    @Test
    public void commentsCosmeticAndJunkAreIgnored() {
        parse("! yorum", "[Adblock Plus 2.0]", "example.com##.ad", "||x.com##.ad",
                "", "   ", "||abc^", "||Bad_Domain!.com^", "/regex/");
        assertTrue(ads.isEmpty());
        assertTrue(exc.isEmpty());
    }

    @Test
    public void videoInfrastructureIsNeverBlocked() {
        parse("||googlevideo.com^", "||rr1.googlevideo.com^", "||i.ytimg.com^", "||youtube.com^",
                "||google.com^");
        assertTrue(ads.isEmpty());
        // benzer ad ama farklı domain → engellenebilir
        parse("||notyoutube.com^");
        assertTrue(ads.contains("notyoutube.com"));
    }

    @Test
    public void domainsAreLowercased() {
        parse("||Ads.Example.COM^");
        assertTrue(ads.contains("ads.example.com"));
    }

    @Test
    public void suffixMatchWalksParentDomains() {
        Set<String> set = new HashSet<>(Arrays.asList("doubleclick.net"));
        assertTrue(FilterParser.suffixMatch("googleads.g.doubleclick.net", set));
        assertTrue(FilterParser.suffixMatch("doubleclick.net", set));
        assertFalse(FilterParser.suffixMatch("notdoubleclick.net", set));
        assertFalse(FilterParser.suffixMatch("example.com", set));
        assertFalse(FilterParser.suffixMatch("x.com", new HashSet<>()));
    }

    @Test
    public void uboKeysAreExtractedFromYoutubeJsonPrune() {
        Set<String> keys = new LinkedHashSet<>();
        FilterParser.uboKeysFromLine("youtube.com##+js(json-prune, playerResponse.adPlacements "
                + "playerResponse.playerAds playerResponse.adSlots adPlacements legacyImportant)", keys);
        assertEquals(Arrays.asList("adPlacements", "playerAds", "adSlots"), Arrays.asList(keys.toArray()));
    }

    @Test
    public void uboCommentsPathsAndOtherSitesAreSkipped() {
        Set<String> keys = new LinkedHashSet<>();
        FilterParser.uboKeysFromLine("!www.youtube.com##+js(json-prune-fetch-response, adFoo no_ads)", keys);
        FilterParser.uboKeysFromLine("example.com##+js(json-prune, adFoo)", keys);
        FilterParser.uboKeysFromLine("youtube.com##+js(json-prune, entries.[-].command.adClientParams.isAd)", keys);
        FilterParser.uboKeysFromLine("youtube.com##+js(set-constant, adFoo, true)", keys);
        assertTrue(keys.isEmpty());
    }

    @Test
    public void uboOnlyAdShapedKeys() {
        Set<String> keys = new LinkedHashSet<>();
        FilterParser.uboKeysFromLine("youtube.com##+js(json-prune, a.isAd b.no_ads c.headerRenderer "
                + "d.adBreakParams e.trackingParams f.playerAdsX)", keys);
        assertEquals(Arrays.asList("isAd", "no_ads", "adBreakParams"), Arrays.asList(keys.toArray()));
    }

    @Test
    public void cacheNameDependsOnUrl() {
        String a = FilterParser.cacheName("https://example.com/a.txt");
        assertEquals(a, FilterParser.cacheName("https://example.com/a.txt"));
        assertNotEquals(a, FilterParser.cacheName("https://example.com/b.txt"));
        assertTrue(a.matches("list-[0-9a-f]+\\.txt"));
    }
}
