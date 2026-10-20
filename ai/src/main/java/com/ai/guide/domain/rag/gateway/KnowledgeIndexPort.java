package com.ai.guide.domain.rag.gateway;

import java.util.List;
import java.util.Map;

/** Knowledge vector-index capabilities consumed by knowledge-document use cases. */
public interface KnowledgeIndexPort {
    /** Prepared without mutating the index; publish is invoked only while the document revision is locked. */
    @FunctionalInterface interface PreparedIndex {
        int publish();
        default int publish(long revision) { return publish(); }
    }
    PreparedIndex prepareFile(String storedFilePath, com.ai.guide.domain.rag.model.KnowledgeScope scope) throws Exception;
    PreparedIndex prepareText(String sourceName, String content, com.ai.guide.domain.rag.model.KnowledgeScope scope) throws Exception;
    default PreparedIndex prepareText(String sourceName, String content,
                                     com.ai.guide.domain.rag.model.KnowledgeScope scope, String sourceUrl) throws Exception {
        return prepareText(sourceName, content, scope);
    }
    void deleteSource(String sourceName);
    void reindexFile(String storedFilePath) throws Exception;
    int reindexText(String sourceName, String content) throws Exception;
    void reindexFile(String storedFilePath, com.ai.guide.domain.rag.model.KnowledgeScope scope) throws Exception;
    int reindexText(String sourceName, String content, com.ai.guide.domain.rag.model.KnowledgeScope scope) throws Exception;
    int countChunks(String sourceName);
    List<Map<String, Object>> listSourceSummaries();
}
