package com.uykutube.app;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class HtmlHeadTest {
    private static int end(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return HtmlHead.end(b, b.length);
    }

    @Test
    public void findsHeadTagEnd() {
        assertEquals(20, end("<!doctype html><head><title>"));
        assertEquals(34, end("<html lang=\"tr\"><head class=\"yt\" x>"));
        assertEquals(5, end("<head>"));
    }

    @Test
    public void skipsHeaderAndIncompleteTag() {
        assertEquals(-1, end("<header>x</header>"));
        assertEquals(-1, end("<!doctype html><head class=\"y"));  // parça sınırı: daha okunmalı
        assertEquals(-1, end("<html>"));
    }

    @Test
    public void respectsLengthLimit() {
        byte[] b = "<html><head>".getBytes(StandardCharsets.UTF_8);
        assertEquals(-1, HtmlHead.end(b, 9));
        assertEquals(11, HtmlHead.end(b, b.length));
    }

    @Test
    public void multibyteBeforeHeadIsHarmless() {
        // "ğ" iki bayt: konum bayt cinsinden
        assertEquals(14, end("<!--ğ--><head>"));
    }
}
