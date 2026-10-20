package com.ai.guide.infrastructure.rag.index;

import com.ai.guide.domain.rag.service.KnowledgeDocumentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Opt-in bounded recovery. The document rows are the durable work records. */
@Component
public final class KnowledgeIndexRecoveryJob {
    private final KnowledgeDocumentService documents;
    private final Executor indexExecutor;
    private final boolean enabled;
    private final int batchSize;

    public KnowledgeIndexRecoveryJob(KnowledgeDocumentService documents,
            @Qualifier("knowledgeIndexExecutor") Executor indexExecutor,
            @Value("${knowledge.index.recovery.enabled:false}") boolean enabled,
            @Value("${knowledge.index.recovery.batch-size:8}") int batchSize) {
        this.documents = documents;
        this.indexExecutor = indexExecutor;
        this.enabled = enabled;
        this.batchSize = Math.max(1, Math.min(20, batchSize));
    }

    @Scheduled(fixedDelayString = "${knowledge.index.recovery.interval-ms:60000}",
            initialDelayString = "${knowledge.index.recovery.initial-delay-ms:60000}")
    public void recover() {
        if (!enabled) return;
        try { indexExecutor.execute(() -> documents.recoverDue(batchSize)); }
        catch (RejectedExecutionException ignored) { /* Durable requests remain due for the next tick. */ }
    }
}
