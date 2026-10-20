package com.ai.guide.domain.rag.gateway;

/** Runtime switches and bounded retrieval settings consumed by the knowledge use case. */
public interface KnowledgeRuntimePolicyPort {
    boolean syncEnabled();
    void requireSyncEnabled();
    void checkUploadSize(long bytes);
    int retrievalMaxCandidates();
    int retrievalExcerptChars();
}
