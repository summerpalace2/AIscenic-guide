package com.ai.guide.domain.rag.service;

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
            var provider = mock(ScenicDataImportService.class);
            var service = new KnowledgeDocumentService(jdbc, provider);
            service.setRuntimePolicy(new RuntimeSettingsPolicy(repo));
            var doc = service.createDocument("资料", "other", "新正文", "[]", null, "fixture");
            assertEquals("pending", doc.getVectorStatus());
            assertEquals("新正文", doc.getContent());
            verifyNoInteractions(provider);
            assertThrows(IllegalStateException.class, () -> service.triggerSync(doc.getId()));
            service.updateDocument(doc.getId(), null, null, "第二版", null);
            verifyNoInteractions(provider);
            service.deleteDocument(doc.getId());
            verify(provider).deleteDocumentVectors(doc.getSourceName());
        } finally { source.destroy(); }
    }

    @Test
    void uploadLimitRejectsBeforeReadingBytesOrCreatingDocument() throws Exception {
        var source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        try {
            var jdbc = schema(source);
            var repo = new AppConfigRepository(jdbc);
            repo.writeConfig("settings_", Map.of("maxUploadSizeMb", 1));
            var service = new KnowledgeDocumentService(jdbc, mock(ScenicDataImportService.class));
            service.setRuntimePolicy(new RuntimeSettingsPolicy(repo));
            var file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(false);
            when(file.getSize()).thenReturn(1024L * 1024 + 1);
            assertThrows(IllegalArgumentException.class, () -> service.createDocument("过大", "other", "", "[]", file, "fixture"));
            verify(file, never()).getBytes();
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM kb_document", Integer.class));
        } finally { source.destroy(); }
    }

    private JdbcTemplate schema(SingleConnectionDataSource source) {
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE app_config(key TEXT PRIMARY KEY,value TEXT,updated_at TEXT)");
        jdbc.execute("CREATE TABLE kb_document(id TEXT PRIMARY KEY,title TEXT,category TEXT,content TEXT,tags TEXT,file_url TEXT,file_md5 TEXT,source_name TEXT,status TEXT,vector_status TEXT,chunk_count INTEGER,created_by TEXT,created_at TEXT,updated_at TEXT,index_revision INTEGER NOT NULL DEFAULT 0)");
        return jdbc;
    }
}
