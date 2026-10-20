package com.ai.guide.domain.rag.service;

import com.ai.guide.domain.rag.gateway.KnowledgeIndexPort;
import com.ai.guide.domain.rag.model.KnowledgeScope;
import com.ai.guide.infrastructure.persistence.migration.KnowledgeSchemaInitializer;
import com.ai.guide.infrastructure.rag.repository.JdbcKnowledgeDocumentStoreAdapter;
import com.ai.guide.testing.KnowledgeTestPorts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeDocumentServiceAsyncTest {
    @Test void textSyncUsesProvidedExecutorAndWritesSyncedState(@TempDir Path dir) throws Exception {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("knowledge.db")));
        new KnowledgeSchemaInitializer().initialize(jdbc, true);
        var index = mock(KnowledgeIndexPort.class);
        var threadName = new AtomicReference<String>();
        var complete = new CountDownLatch(1);
        when(index.prepareText(anyString(), eq("室内参观与无障碍动线说明"), eq(KnowledgeScope.unspecified())))
                .thenAnswer(call -> {
                    threadName.set(Thread.currentThread().getName());
                    return (KnowledgeIndexPort.PreparedIndex) () -> 3;
                });
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-knowledge-index"));
        try {
            var service = KnowledgeTestPorts.service(new JdbcKnowledgeDocumentStoreAdapter(jdbc), index,
                    task -> executor.execute(() -> { try { task.run(); } finally { complete.countDown(); } }), () -> {});
            var doc = service.createDocument("异步资料", "guide", "室内参观与无障碍动线说明", "[]", null, "fixture");
            assertTrue(complete.await(5, TimeUnit.SECONDS));
            assertEquals("test-knowledge-index", threadName.get());
            assertEquals("synced", service.getDocument(doc.getId()).getVectorStatus());
            assertEquals(3, service.getDocument(doc.getId()).getChunkCount());
        } finally { executor.shutdownNow(); }
    }
}
