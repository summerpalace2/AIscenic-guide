package com.ai.guide.infrastructure.search.baidu;

import com.ai.guide.domain.search.gateway.WebSearchLinkPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Provider-specific URL construction for Baidu's public web search. */
@Component
public class BaiduWebSearchLinkAdapter implements WebSearchLinkPort {
    private final String searchUrl;
    private final String faviconUrl;

    public BaiduWebSearchLinkAdapter(
            @Value("${web-search.baidu.search-url}") String searchUrl,
            @Value("${web-search.baidu.favicon-url}") String faviconUrl) {
        this.searchUrl = trimTrailingSlash(searchUrl);
        this.faviconUrl = faviconUrl == null ? "" : faviconUrl.trim();
    }

    @Override
    public String searchUrl(String query) {
        String encoded = URLEncoder.encode(query == null ? "" : query, StandardCharsets.UTF_8);
        return searchUrl + "?wd=" + encoded;
    }

    @Override
    public String faviconUrl() {
        return faviconUrl;
    }

    private static String trimTrailingSlash(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }
}
