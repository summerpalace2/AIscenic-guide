package com.ai.guide.domain.rag.service;

import com.ai.guide.domain.rag.gateway.KnowledgeIndexPort;
import com.ai.guide.domain.rag.gateway.KnowledgeFileStoragePort;
import com.ai.guide.domain.rag.gateway.KnowledgeRuntimePolicyPort;
import com.ai.guide.domain.rag.model.KnowledgeDocumentUpload;
import com.ai.guide.domain.rag.model.KnowledgeScope;
import com.ai.guide.infrastructure.persistence.migration.KnowledgeSchemaInitializer;
import com.ai.guide.infrastructure.rag.repository.JdbcKnowledgeDocumentStoreAdapter;
import com.ai.guide.testing.KnowledgeTestPorts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeDurableIndexRecoveryTest {
    private JdbcTemplate db(Path dir) {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("knowledge.db")));
        new KnowledgeSchemaInitializer().initialize(jdbc, true);
        return jdbc;
    }

    @Test void queuedTextRequestSurvivesWorkerLossAndFailureBackoff(@TempDir Path dir) throws Exception {
        var jdbc = db(dir);
        var index = mock(KnowledgeIndexPort.class);
        var attempts = new AtomicInteger();
        when(index.prepareText(anyString(), anyString(), any())).thenAnswer(call -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("temporary local fixture failure");
            return (KnowledgeIndexPort.PreparedIndex) () -> 2;
        });
        var original = KnowledgeTestPorts.service(new JdbcKnowledgeDocumentStoreAdapter(jdbc), index, task -> {}, () -> {});
        var doc = original.createDocument("重启恢复", "guide", "正文", "[]", null, "fixture");
        assertEquals("TEXT", jdbc.queryForObject("SELECT index_request_mode FROM kb_document WHERE id=?", String.class, doc.getId()));
        var restarted = KnowledgeTestPorts.service(new JdbcKnowledgeDocumentStoreAdapter(jdbc), index);
        assertEquals(0, restarted.recoverDue(1));
        assertEquals("failed", restarted.getDocument(doc.getId()).getVectorStatus());
        assertEquals(1, jdbc.queryForObject("SELECT index_attempts FROM kb_document WHERE id=?", Integer.class, doc.getId()));
        assertTrue(jdbc.queryForObject("SELECT index_next_attempt_at FROM kb_document WHERE id=?", Long.class, doc.getId()) > System.currentTimeMillis());
        jdbc.update("UPDATE kb_document SET index_next_attempt_at=0 WHERE id=?", doc.getId());
        assertEquals(1, restarted.recoverDue(1));
        assertEquals("synced", restarted.getDocument(doc.getId()).getVectorStatus());
        assertEquals(2, restarted.getDocument(doc.getId()).getChunkCount());
        assertEquals("TEXT", jdbc.queryForObject("SELECT index_request_mode FROM kb_document WHERE id=?", String.class, doc.getId()));
        assertEquals(0L, jdbc.queryForObject("SELECT index_lease_until FROM kb_document WHERE id=?", Long.class, doc.getId()));
    }

    @Test void archivedDeleteRemainsRecoverableAndDoesNotReviveDocument(@TempDir Path dir) throws Exception {
        var jdbc = db(dir);
        var index = mock(KnowledgeIndexPort.class);
        var queued = new ArrayList<Runnable>();
        var service = KnowledgeTestPorts.service(new JdbcKnowledgeDocumentStoreAdapter(jdbc), index, queued::add, () -> {});
        var doc = service.createDocument("待归档", "guide", "正文", "[]", null, "fixture");
        service.deleteDocument(doc.getId());
        assertEquals("archived", service.getDocument(doc.getId()).getStatus());
        assertEquals("DELETE", jdbc.queryForObject("SELECT index_request_mode FROM kb_document WHERE id=?", String.class, doc.getId()));
        var restarted = KnowledgeTestPorts.service(new JdbcKnowledgeDocumentStoreAdapter(jdbc), index);
        assertEquals(1, restarted.recoverDue(1));
        verify(index).deleteSource(doc.getSourceName());
        assertEquals("archived", restarted.getDocument(doc.getId()).getStatus());
        assertEquals("DELETE", jdbc.queryForObject("SELECT index_request_mode FROM kb_document WHERE id=?", String.class, doc.getId()));
        queued.forEach(Runnable::run);
        verify(index, never()).prepareText(anyString(), anyString(), any());
    }

    @Test void scopeOnlyChangeOfEmptyUploadPersistsFileMode(@TempDir Path dir) throws Exception {
        var jdbc = db(dir);
        var index = mock(KnowledgeIndexPort.class);
        when(index.prepareFile(anyString(), any())).thenReturn(() -> 1);
        var files = mock(KnowledgeFileStoragePort.class);
        when(files.save(anyString(), any())).thenReturn("owned-upload.txt");
        when(files.basename(anyString())).thenReturn("owned-upload.txt");
        var policy = mock(KnowledgeRuntimePolicyPort.class);
        when(policy.syncEnabled()).thenReturn(true);
        var queued = new ArrayList<Runnable>();
        var service = new KnowledgeDocumentService(new JdbcKnowledgeDocumentStoreAdapter(jdbc), index, files,
                queued::add, () -> {}, policy);
        var upload = new KnowledgeDocumentUpload("source.txt", 3, () -> new byte[]{1, 2, 3});
        var doc = service.createDocument("迁城文件", "guide", "", "[]", upload, "fixture", "CITY", "北京市");
        service.updateDocument(doc.getId(), null, null, null, null, "CITY", "杭州市");
        assertEquals("FILE", jdbc.queryForObject("SELECT index_request_mode FROM kb_document WHERE id=?", String.class, doc.getId()));
        queued.get(0).run(); // obsolete Beijing callback cannot claim the newer request
        verify(index, never()).prepareFile(anyString(), any());
        queued.get(1).run();
        verify(index).prepareFile(eq("owned-upload.txt"), eq(new KnowledgeScope("CITY", "杭州市")));
        assertEquals("synced", service.getDocument(doc.getId()).getVectorStatus());
    }

    @Test void editedUploadKeepsTextAsAuthorityWhenOnlyScopeChanges(@TempDir Path dir) throws Exception {
        var jdbc = db(dir);
        var index = mock(KnowledgeIndexPort.class);
        when(index.prepareFile(anyString(), any())).thenReturn(() -> 1);
        when(index.prepareText(anyString(), anyString(), any())).thenReturn(() -> 2);
        var files = mock(KnowledgeFileStoragePort.class);
        when(files.save(anyString(), any())).thenReturn("original-file.txt");
        when(files.basename(anyString())).thenReturn("original-file.txt");
        var policy = mock(KnowledgeRuntimePolicyPort.class);
        when(policy.syncEnabled()).thenReturn(true);
        var service = new KnowledgeDocumentService(new JdbcKnowledgeDocumentStoreAdapter(jdbc), index, files,
                Runnable::run, () -> {}, policy);
        var upload = new KnowledgeDocumentUpload("source.txt", 3, () -> new byte[]{1, 2, 3});
        var doc = service.createDocument("原件后编辑", "guide", "", "[]", upload, "fixture", "CITY", "北京市");
        service.updateDocument(doc.getId(), null, null, "管理员修改的正文B", null);
        service.updateDocument(doc.getId(), null, null, null, null, "CITY", "杭州市");
        assertEquals("TEXT", jdbc.queryForObject("SELECT index_request_mode FROM kb_document WHERE id=?", String.class, doc.getId()));
        assertEquals("管理员修改的正文B", service.getDocument(doc.getId()).getContent());
        verify(index, times(1)).prepareFile(eq("original-file.txt"), eq(new KnowledgeScope("CITY", "北京市")));
        verify(index).prepareText(eq("original-file.txt"), eq("管理员修改的正文B"), eq(new KnowledgeScope("CITY", "北京市")));
        verify(index).prepareText(eq("original-file.txt"), eq("管理员修改的正文B"), eq(new KnowledgeScope("CITY", "杭州市")));
    }
}
