package com.ai.guide.testing;

import com.ai.guide.domain.rag.gateway.KnowledgeIndexPort;
import com.ai.guide.domain.rag.gateway.KnowledgeDocumentStorePort;
import com.ai.guide.domain.rag.gateway.KnowledgeFileStoragePort;
import com.ai.guide.domain.rag.gateway.KnowledgeRuntimePolicyPort;
import com.ai.guide.domain.rag.gateway.RerankCacheInvalidationPort;
import com.ai.guide.domain.rag.service.KnowledgeDocumentService;
import java.io.IOException;

import java.util.List;
import java.util.Map;

public final class KnowledgeTestPorts {
    private KnowledgeTestPorts() {}

    public static KnowledgeIndexPort noIndex() {
        return new KnowledgeIndexPort() {
            public PreparedIndex prepareFile(String path, com.ai.guide.domain.rag.model.KnowledgeScope scope) { return () -> 0; }
            public PreparedIndex prepareText(String source, String content, com.ai.guide.domain.rag.model.KnowledgeScope scope) { return () -> 0; }
            public void deleteSource(String sourceName) {}
            public void reindexFile(String storedFilePath) {}
            public int reindexText(String sourceName, String content) { return 0; }
            public void reindexFile(String path, com.ai.guide.domain.rag.model.KnowledgeScope scope) {}
            public int reindexText(String source, String content, com.ai.guide.domain.rag.model.KnowledgeScope scope) { return 0; }
            public int countChunks(String sourceName) { return 0; }
            public List<Map<String, Object>> listSourceSummaries() { return List.of(); }
        };
    }

    public static KnowledgeDocumentService service(KnowledgeDocumentStorePort store, KnowledgeIndexPort index) {
        return service(store, index, Runnable::run, () -> {});
    }

    public static KnowledgeDocumentService service(KnowledgeDocumentStorePort store, KnowledgeIndexPort index,
                                                   java.util.concurrent.Executor executor,
                                                   RerankCacheInvalidationPort cache) {
        KnowledgeFileStoragePort fileStorage = new KnowledgeFileStoragePort() {
            public String save(String name, byte[] content) throws IOException { throw new IOException("No file fixture configured"); }
            public String basename(String path) { return path == null || path.isBlank() ? "" : java.nio.file.Path.of(path).getFileName().toString(); }
            public void deleteOwned(String path) {}
        };
        KnowledgeRuntimePolicyPort policy = new KnowledgeRuntimePolicyPort() {
            public boolean syncEnabled() { return true; }
            public void requireSyncEnabled() {}
            public void checkUploadSize(long bytes) { if (bytes < 0 || bytes > 50L * 1024 * 1024) throw new IllegalArgumentException("test upload too large"); }
            public int retrievalMaxCandidates() { return 200; }
            public int retrievalExcerptChars() { return 4096; }
        };
        return new KnowledgeDocumentService(store, index, fileStorage, executor, cache, policy);
    }
}
