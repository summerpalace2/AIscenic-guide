package com.ai.guide.domain.rag.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParallelRagServiceTest {

    @Test
    void searchUsesDedicatedExecutorBoundaryAndKeepsMergedResult() {
        ScenicDataImportService dataImportService = mock(ScenicDataImportService.class);
        AtomicReference<String> threadName = new AtomicReference<>();
        when(dataImportService.searchFragmentsDeep("重庆博物馆", 25, 15)).thenAnswer(invocation -> {
            threadName.set(Thread.currentThread().getName());
            return List.of("重庆博物馆提供历史展陈与室内参观动线，适合雨天室内参观。");
        });

        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "test-parallel-rag");
            thread.setDaemon(true);
            return thread;
        });
        try {
            ParallelRagService service = new ParallelRagService(dataImportService, executor);

            assertEquals("重庆博物馆提供历史展陈与室内参观动线，适合雨天室内参观。", service.search(List.of("重庆博物馆")));
            assertEquals("test-parallel-rag", threadName.get());
        } finally {
            executor.shutdownNow();
        }
    }
}
