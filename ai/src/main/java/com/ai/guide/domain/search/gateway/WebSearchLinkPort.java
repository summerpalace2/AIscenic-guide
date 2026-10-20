package com.ai.guide.domain.search.gateway;

/** Creates outbound search links without exposing a provider URL to search policy. */
public interface WebSearchLinkPort {
    String searchUrl(String query);

    String faviconUrl();
}
