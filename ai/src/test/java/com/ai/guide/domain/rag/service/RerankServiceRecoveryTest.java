package com.ai.guide.domain.rag.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import java.util.List;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class RerankServiceRecoveryTest {
    private static class Provider extends RestTemplate {
        int calls; boolean fail; String response = "{\"output\":{\"results\":[{\"index\":1}]}}";
        @Override public <T>T postForObject(String url, Object request, Class<T> type, Object...variables) {
            calls++; if (fail) throw new IllegalStateException("fixture provider unavailable"); return type.cast(response);
        }
    }
    @Test void fallbackIsNotCachedAndDeepModeRetriesProviderAfterRecovery() {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var service = new RerankService(executor); var provider = new Provider();
            ReflectionTestUtils.setField(service, "restTemplate", provider); service.setApiKey("fixture"); service.setRerankUrl("https://fixture.invalid");
            var docs = List.of("first raw candidate", "best reranked candidate"); provider.fail = true;
            assertEquals(List.of(docs.get(0)), service.rerankDeep("query", docs, 1, 1000));
            assertEquals(0, service.getCacheStats().get("l1Size")); provider.fail = false;
            assertEquals(List.of(docs.get(1)), service.rerankDeep("query", docs, 1, 1000)); assertEquals(2, provider.calls);
            assertEquals(List.of(docs.get(1)), service.rerankDeep("query", docs, 1, 1000)); assertEquals(2, provider.calls);
        }
    }
    @Test void malformedProviderOutputDoesNotPoisonCacheAndChangedCandidatesCannotReuseOldResult() {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var service = new RerankService(executor); var provider = new Provider();
            ReflectionTestUtils.setField(service, "restTemplate", provider); service.setApiKey("fixture"); service.setRerankUrl("https://fixture.invalid");
            var docs = List.of("old candidate", "current candidate");
            for (String malformed : List.of("{}", "{\"output\":{\"results\":[]}}", "{\"output\":{\"results\":[{\"index\":-1}]}}")) {
                provider.response = malformed;
                assertEquals(List.of(docs.get(0)), service.rerank("query", docs, 1));
                assertEquals(0, service.getCacheStats().get("totalSize"));
            }
            provider.response = "{\"output\":{\"results\":[{\"index\":1}]}}";
            assertEquals(List.of(docs.get(1)), service.rerank("query", docs, 1));
            assertEquals(List.of("new best candidate"), service.rerank("query", List.of("new raw candidate", "new best candidate"), 1));
            assertEquals(5, provider.calls);
        }
    }
}
