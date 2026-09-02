package com.ai.guide.domain.rag.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeDocumentServiceAsyncTest {

    @Test
    void textSyncUsesProvidedExecutorAndWritesSyncedState() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ScenicDataImportService dataImportService = mock(ScenicDataImportService.class);
        AtomicReference<String> threadName = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        when(dataImportService.reindexTextDocument("text-doc-1", "室内参观与无障碍动线说明"))
                .thenAnswer(invocation -> {
                    threadName.set(Thread.currentThread().getName());
                    completed.countDown();
                    return 3;
                });

        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "test-knowledge-index");
            thread.setDaemon(true);
            return thread;
        });
        try {
            KnowledgeDocumentService service = new KnowledgeDocumentService(
                    jdbcTemplate, dataImportService, executor);

            ReflectionTestUtils.invokeMethod(service, "triggerTextSyncAsync",
                    "doc-1", "text-doc-1", "室内参观与无障碍动线说明");

            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertEquals("test-knowledge-index", threadName.get());
            verify(jdbcTemplate).update(
                    contains("vector_status = 'synced'"),
                    eq("text-doc-1"), eq(3), eq("doc-1"));
        } finally {
            executor.shutdownNow();
        }
    }
}
