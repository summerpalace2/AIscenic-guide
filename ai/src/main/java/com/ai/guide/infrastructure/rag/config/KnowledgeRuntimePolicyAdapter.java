package com.ai.guide.infrastructure.rag.config;

import com.ai.guide.domain.admin.service.RuntimeSettingsPolicy;
import com.ai.guide.domain.rag.gateway.KnowledgeRuntimePolicyPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Adapts operator settings and deployment properties to the RAG policy boundary. */
@Component
public final class KnowledgeRuntimePolicyAdapter implements KnowledgeRuntimePolicyPort {
    private final RuntimeSettingsPolicy runtimeSettings;
    private final int maxCandidates;
    private final int excerptChars;

    public KnowledgeRuntimePolicyAdapter(
            RuntimeSettingsPolicy runtimeSettings,
            @Value("${knowledge.retrieval.max-candidates:200}") int maxCandidates,
            @Value("${knowledge.retrieval.excerpt-chars:4096}") int excerptChars) {
        this.runtimeSettings = runtimeSettings;
        this.maxCandidates = clamp(maxCandidates, 1, 2000, 200);
        this.excerptChars = clamp(excerptChars, 256, 16384, 4096);
    }

    @Override public boolean syncEnabled() { return runtimeSettings.isKnowledgeSyncEnabled(); }
    @Override public void requireSyncEnabled() { runtimeSettings.requireKnowledgeSyncEnabled(); }
    @Override public void checkUploadSize(long bytes) { runtimeSettings.checkUploadSize(bytes); }
    @Override public int retrievalMaxCandidates() { return maxCandidates; }
    @Override public int retrievalExcerptChars() { return excerptChars; }

    private static int clamp(int value, int min, int max, int fallback) {
        return value < min || value > max ? fallback : value;
    }
}
