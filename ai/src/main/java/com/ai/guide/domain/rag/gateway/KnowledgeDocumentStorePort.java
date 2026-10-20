package com.ai.guide.domain.rag.gateway;

import com.ai.guide.domain.rag.model.KnowledgeDocument;

import java.util.List;
import java.util.Map;

/** Persistence capability for knowledge documents; SQL and database dialects stay in adapters. */
public interface KnowledgeDocumentStorePort {
    <T> T withDocumentLock(String id, java.util.function.Supplier<T> operation);
    void afterCommit(Runnable operation);
    record Page(long total, List<KnowledgeDocument> items) {}
    record CategoryCount(String category, int count) {}
    record ArchivedSource(String sourceName, String fileUrl, String title) {}
    /** A durable request snapshot claimed by one worker for one document revision. */
    record IndexClaim(KnowledgeDocument document, long revision, String token, String mode, int attempt) {}
    enum BackfillOutcome { CREATED, UPDATED, SKIPPED }

    Page list(String category, String keyword, int page, int size);
    List<Map<String, Object>> searchCandidates(List<String> terms, String needle, int excerptChars, int limit);
    List<Map<String, Object>> searchCandidates(List<String> terms, String needle, int excerptChars, int limit,
            com.ai.guide.domain.rag.model.KnowledgeScope scope);

    Integer relativeMatch(String id, int startOffset, String term);
    String contentWindow(String id, int start, int length);
    List<CategoryCount> categories();
    KnowledgeDocument findById(String id);
    boolean activeTitleExists(String title);
    boolean activeFileHashExists(String fileHash);
    boolean activeTitleExists(String title, com.ai.guide.domain.rag.model.KnowledgeScope scope);
    boolean activeFileHashExists(String fileHash, com.ai.guide.domain.rag.model.KnowledgeScope scope);
    void insert(KnowledgeDocument document);
    int update(String id, String title, String category, String content, String tags,
               String sourceName, boolean contentChanged, String updatedAt);
    int update(String id, String title, String category, String content, String tags,
               String sourceName, boolean contentChanged, String updatedAt, com.ai.guide.domain.rag.model.KnowledgeScope scope);
    int updateVectorState(String id, String sourceName, String status, Integer chunkCount);
    int archive(String id, String sourceName, String updatedAt);
    int requestIndex(String id, String mode, boolean bumpRevision);
    IndexClaim claimIndex(String id, long nowMillis, long leaseMillis);
    boolean ownsIndexClaim(String id, long revision, String token, long nowMillis);
    int finishIndexSuccess(String id, long revision, String token, String sourceName, int chunkCount);
    int finishIndexFailure(String id, long revision, String token, long nextAttemptMillis);
    List<String> dueIndexIds(long nowMillis, int limit, boolean includeEmbedding);
    int markSyncing(String id);
    int markSynced(String id, String sourceName, int chunkCount, Long expectedRevision);
    int markFailed(String id, Long expectedRevision);
    Long indexRevision(String id);
    boolean isCurrentIndex(String id, long revision);
    List<ArchivedSource> archivedSources();
    BackfillOutcome backfill(String sourceName, String content, int chunkCount, String updatedAt);
    BackfillOutcome backfill(String sourceName, String content, int chunkCount, String updatedAt, com.ai.guide.domain.rag.model.KnowledgeScope scope);
    default BackfillOutcome backfill(String sourceName, String content, int chunkCount, String updatedAt,
                                    com.ai.guide.domain.rag.model.KnowledgeScope scope, Long revision) {
        return backfill(sourceName, content, chunkCount, updatedAt, scope);
    }
}
