package com.ai.guide.domain.rag.service;

import com.ai.guide.domain.rag.gateway.ScenicKnowledgePort;
import com.ai.guide.domain.rag.gateway.KnowledgeIndexPort;

import com.ai.guide.domain.admin.service.RuntimeSettingsPolicy;
import com.ai.guide.infrastructure.admin.repository.AppConfigRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeRuntimePolicyTest {
    @Test
    void disabledSyncStoresPendingContentWithoutProviderAndRejectsExplicitRefresh() throws Exception {
        var source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        try {
            var jdbc = schema(source);
            var repo = new AppConfigRepository(jdbc);
            repo.writeConfig("settings_", Map.of("knowledgeSyncEnabled", false));
            var provider = mock(ScenicKnowledgePort.class);
            var service = new KnowledgeDocumentService(new com.ai.guide.infrastructure.rag.repository.JdbcKnowledgeDocumentStoreAdapter(jdbc), provider, noFileStorage(), Runnable::run, () -> {}, new com.ai.guide.infrastructure.rag.config.KnowledgeRuntimePolicyAdapter(new RuntimeSettingsPolicy(repo,"test","zh",true,true,50), 200, 4096));
            var doc = service.createDocument("资料", "other", "新正文", "[]", null, "fixture");
            assertEquals("pending", doc.getVectorStatus());
            assertEquals("新正文", doc.getContent());
            verifyNoInteractions(provider);
            assertThrows(IllegalStateException.class, () -> service.triggerSync(doc.getId()));
            service.updateDocument(doc.getId(), null, null, "第二版", null);
            verifyNoInteractions(provider);
            service.deleteDocument(doc.getId());
            verify(provider).deleteSource(doc.getSourceName());
        } finally { source.destroy(); }
    }

    @Test
    void uploadLimitRejectsBeforeReadingBytesOrCreatingDocument() throws Exception {
        var source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        try {
            var jdbc = schema(source);
            var repo = new AppConfigRepository(jdbc);
            repo.writeConfig("settings_", Map.of("maxUploadSizeMb", 1));
            var service = new KnowledgeDocumentService(new com.ai.guide.infrastructure.rag.repository.JdbcKnowledgeDocumentStoreAdapter(jdbc), mock(KnowledgeIndexPort.class), noFileStorage(), Runnable::run, () -> {}, new com.ai.guide.infrastructure.rag.config.KnowledgeRuntimePolicyAdapter(new RuntimeSettingsPolicy(repo,"test","zh",true,true,50), 200, 4096));
            var file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(false);
            when(file.getSize()).thenReturn(1024L * 1024 + 1);
            var upload = new com.ai.guide.domain.rag.model.KnowledgeDocumentUpload(
                    "large.pdf", file.getSize(), file::getBytes);
            assertThrows(IllegalArgumentException.class, () -> service.createDocument("过大", "other", "", "[]", upload, "fixture"));
            verify(file, never()).getBytes();
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM kb_document", Integer.class));
        } finally { source.destroy(); }
    }

    private JdbcTemplate schema(SingleConnectionDataSource source) {
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE app_config(key TEXT PRIMARY KEY,value TEXT,updated_at TEXT)");
        jdbc.execute("CREATE TABLE kb_document(id TEXT PRIMARY KEY,title TEXT,category TEXT,content TEXT,tags TEXT,file_url TEXT,file_md5 TEXT,source_name TEXT,source_url TEXT NOT NULL DEFAULT '',index_request_mode VARCHAR(16) NOT NULL DEFAULT '',index_attempts INTEGER NOT NULL DEFAULT 0,index_next_attempt_at BIGINT NOT NULL DEFAULT 0,index_lease_token VARCHAR(64) NOT NULL DEFAULT '',index_lease_until BIGINT NOT NULL DEFAULT 0,status TEXT,vector_status TEXT,chunk_count INTEGER,created_by TEXT,created_at TEXT,updated_at TEXT,index_revision INTEGER NOT NULL DEFAULT 0,scope TEXT NOT NULL DEFAULT 'UNSPECIFIED',city TEXT NOT NULL DEFAULT '')");
        return jdbc;
    }

    private static com.ai.guide.domain.rag.gateway.KnowledgeFileStoragePort noFileStorage() {
        return new com.ai.guide.domain.rag.gateway.KnowledgeFileStoragePort() {
            public String save(String filename, byte[] bytes) { throw new UnsupportedOperationException(); }
            public String basename(String path) { return java.nio.file.Path.of(path).getFileName().toString(); }
            public void deleteOwned(String path) {}
        };
    }
}
