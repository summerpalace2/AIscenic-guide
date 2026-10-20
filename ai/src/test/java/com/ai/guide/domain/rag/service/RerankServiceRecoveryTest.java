package com.ai.guide.domain.rag.service;

import com.ai.guide.domain.rag.gateway.RerankProviderPort;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class RerankServiceRecoveryTest {
    private static class Provider implements RerankProviderPort {
        int calls; boolean fail; List<Integer> indices = List.of(1);
        @Override public List<Integer> rerank(String query, List<String> documents, int topN) {
            calls++;
            if (fail) throw new IllegalStateException("fixture provider unavailable");
            return indices;
        }
    }
    @Test void fallbackIsNotCachedAndDeepModeRetriesProviderAfterRecovery() {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var provider = new Provider(); var service = new RerankService(executor, provider, new com.fasterxml.jackson.databind.ObjectMapper());
            var docs = List.of("first raw candidate", "best reranked candidate"); provider.fail = true;
            assertEquals(List.of(docs.get(0)), service.rerankDeep("query", docs, 1, 1000));
            assertEquals(0, service.getCacheStats().get("l1Size")); provider.fail = false;
            assertEquals(List.of(docs.get(1)), service.rerankDeep("query", docs, 1, 1000)); assertEquals(2, provider.calls);
            assertEquals(List.of(docs.get(1)), service.rerankDeep("query", docs, 1, 1000)); assertEquals(2, provider.calls);
        }
    }
    @Test void malformedProviderOutputDoesNotPoisonCacheAndChangedCandidatesCannotReuseOldResult() {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var provider = new Provider(); var service = new RerankService(executor, provider, new com.fasterxml.jackson.databind.ObjectMapper());
            var docs = List.of("old candidate", "current candidate");
            for (List<Integer> malformedIndices : List.<List<Integer>>of(List.of(), List.of(-1), List.of(9))) {
                provider.indices = malformedIndices;
                assertEquals(List.of(docs.get(0)), service.rerank("query", docs, 1));
                assertEquals(0, service.getCacheStats().get("totalSize"));
            }
            provider.indices = List.of(1);
            assertEquals(List.of(docs.get(1)), service.rerank("query", docs, 1));
            assertEquals(List.of("new best candidate"), service.rerank("query", List.of("new raw candidate", "new best candidate"), 1));
            assertEquals(5, provider.calls);
        }
    }
}
