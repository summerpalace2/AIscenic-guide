package com.ai.guide.infrastructure.search.baidu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BaiduWebSearchLinkAdapterTest {
    @Test
    void encodesQueryUsingConfiguredProviderLinks() {
        var adapter = new BaiduWebSearchLinkAdapter("https://search.example.test/s/", "https://cdn.example.test/icon.png");

        assertEquals("https://search.example.test/s?wd=%E9%87%8D%E5%BA%86+%E7%81%AB%E9%94%85", adapter.searchUrl("重庆 火锅"));
        assertEquals("https://cdn.example.test/icon.png", adapter.faviconUrl());
    }
}
