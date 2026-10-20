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
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual SQLite revision/transaction lifecycle; Qdrant and embedding are controlled ports. */
class KnowledgePublicationLifecycleTest {
    JdbcTemplate db(Path directory) {
        return new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("knowledge.db")));
    }
    @Test void directExecutorPreparationDoesNotInheritCommittedTransaction(@TempDir Path dir) throws Exception {
        var jdbc = db(dir); new KnowledgeSchemaInitializer().initialize(jdbc, true);
        var index = mock(KnowledgeIndexPort.class);
        var preparations = new AtomicInteger();
        when(index.prepareText(anyString(), anyString(), any())).thenAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),
                    "Model preparation must not inherit the committed transaction's thread-bound connection");
            int count = preparations.incrementAndGet();
            return (KnowledgeIndexPort.PreparedIndex) () -> {
                assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                return count;
            };
        });
        var service = KnowledgeTestPorts.service(new JdbcKnowledgeDocumentStoreAdapter(jdbc), index);
        var doc = service.createDocument("直接执行器", "guide", "原始有效知识正文", "[]", null, "fixture", "GLOBAL", "");
        service.updateDocument(doc.getId(), null, null, "更新的有效知识正文", null);
        assertEquals(2, preparations.get());
        assertEquals("synced", service.getDocument(doc.getId()).getVectorStatus());
        assertEquals(2, service.getDocument(doc.getId()).getChunkCount());
    }
    @Test void archiveDuringPreparationCannotBeUndoneByLatePublication(@TempDir Path dir) throws Exception {
        var jdbcA = db(dir); new KnowledgeSchemaInitializer().initialize(jdbcA, true);
        var jdbcB = db(dir);
        var index = mock(KnowledgeIndexPort.class);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var completed = new CountDownLatch(1); var publications = new AtomicInteger();
        when(index.prepareText(anyString(), anyString(), any())).thenAnswer(call -> {
            entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
            return (KnowledgeIndexPort.PreparedIndex) () -> { publications.incrementAndGet(); return 1; };
        });
        var pool = Executors.newSingleThreadExecutor();
        try {
            var a = KnowledgeTestPorts.service(new JdbcKnowledgeDocumentStoreAdapter(jdbcA), index,
                    task -> pool.execute(() -> { try { task.run(); } finally { completed.countDown(); } }), () -> {});
            var b = KnowledgeTestPorts.service(new JdbcKnowledgeDocumentStoreAdapter(jdbcB), index);
            var doc = a.createDocument("准备中删除", "guide", "有效的待索引知识正文", "[]", null, "fixture", "GLOBAL", "");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            // This must finish while preparation is gated: embedding holds no DB lock.
            b.deleteDocument(doc.getId());
            assertEquals("archived", b.getDocument(doc.getId()).getStatus());
            release.countDown(); assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertEquals(0, publications.get());
            assertEquals("archived", a.getDocument(doc.getId()).getStatus());
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void newerScopePublishesOnceWhilePreparedOldScopeIsDiscarded(@TempDir Path dir) throws Exception {
        var jdbc = db(dir); new KnowledgeSchemaInitializer().initialize(jdbc, true);
        var index = mock(KnowledgeIndexPort.class);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var complete = new CountDownLatch(1);
        var oldPublications = new AtomicInteger(); var newPublications = new AtomicInteger();
        when(index.prepareText(anyString(), anyString(), any())).thenAnswer(call -> {
            KnowledgeScope scope = call.getArgument(2);
            if (scope.city().equals("北京市")) {
                entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
                return (KnowledgeIndexPort.PreparedIndex) () -> { oldPublications.incrementAndGet(); return 1; };
            }
            assertEquals("杭州市", scope.city());
            return (KnowledgeIndexPort.PreparedIndex) () -> { newPublications.incrementAndGet(); return 2; };
        });
        var pool = Executors.newSingleThreadExecutor();
        try {
            var a = KnowledgeTestPorts.service(new JdbcKnowledgeDocumentStoreAdapter(jdbc), index,
                    task -> pool.execute(() -> { try { task.run(); } finally { complete.countDown(); } }), () -> {});
            var b = KnowledgeTestPorts.service(new JdbcKnowledgeDocumentStoreAdapter(db(dir)), index);
            var doc = a.createDocument("变更城市", "guide", "有效的城市知识正文", "[]", null, "fixture", "CITY", "北京市");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            b.updateDocument(doc.getId(), null, null, null, null, "CITY", "杭州市");
            assertEquals("synced", b.getDocument(doc.getId()).getVectorStatus());
            release.countDown(); assertTrue(complete.await(5, TimeUnit.SECONDS));
            assertEquals(0, oldPublications.get()); assertEquals(1, newPublications.get());
            assertEquals("杭州市", a.getDocument(doc.getId()).getCity());
            assertEquals(2, a.getDocument(doc.getId()).getChunkCount());
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void manualSyncDuringArchiveDoesNotReturnFalseSuccess(@TempDir Path dir) throws Exception {
        var jdbc = db(dir); new KnowledgeSchemaInitializer().initialize(jdbc, true);
        var index = mock(KnowledgeIndexPort.class);
        var queued = new ArrayList<Runnable>();
        var service = KnowledgeTestPorts.service(new JdbcKnowledgeDocumentStoreAdapter(jdbc), index, queued::add, () -> {});
        var other = KnowledgeTestPorts.service(new JdbcKnowledgeDocumentStoreAdapter(db(dir)), index);
        var doc = service.createDocument("同步中删除", "guide", "有效的原始知识正文", "[]", null, "fixture", "GLOBAL", "");
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var prepared = mock(KnowledgeIndexPort.PreparedIndex.class);
        when(index.prepareText(anyString(), anyString(), any())).thenAnswer(call -> {
            entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return prepared;
        });
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> sync = pool.submit(() -> service.triggerSync(doc.getId()));
            assertTrue(entered.await(5, TimeUnit.SECONDS)); other.deleteDocument(doc.getId()); release.countDown();
            assertThrows(ExecutionException.class, () -> sync.get(5, TimeUnit.SECONDS));
            verify(prepared, never()).publish();
            assertEquals("archived", service.getDocument(doc.getId()).getStatus());
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void manualPublicationFailureIsFailedAndRetryCanRecover(@TempDir Path dir) throws Exception {
        var jdbc = db(dir); new KnowledgeSchemaInitializer().initialize(jdbc, true);
        var index = mock(KnowledgeIndexPort.class);
        var service = KnowledgeTestPorts.service(new JdbcKnowledgeDocumentStoreAdapter(jdbc), index, task -> {}, () -> {});
        var doc = service.createDocument("同步重试", "guide", "有效的知识正文", "[]", null, "fixture", "GLOBAL", "");
        when(index.prepareText(anyString(), anyString(), any())).thenReturn(
                () -> { throw new IllegalStateException("fixture Qdrant upsert refused"); }, () -> 2);
        assertThrows(RuntimeException.class, () -> service.triggerSync(doc.getId()));
        assertEquals("failed", service.getDocument(doc.getId()).getVectorStatus());
        assertEquals("synced", service.triggerSync(doc.getId()).get("vectorStatus"));
        assertEquals(2, service.getDocument(doc.getId()).getChunkCount());
    }
}
