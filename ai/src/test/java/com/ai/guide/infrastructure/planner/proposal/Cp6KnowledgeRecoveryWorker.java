package com.ai.guide.infrastructure.planner.proposal;

import com.ai.guide.domain.rag.service.KnowledgeDocumentService;
import com.ai.guide.domain.rag.gateway.KnowledgeIndexPort;
import com.ai.guide.domain.rag.model.KnowledgeScope;
import com.ai.guide.infrastructure.rag.repository.JdbcKnowledgeDocumentStoreAdapter;
import com.ai.guide.testing.KnowledgeTestPorts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.Map;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/** Independent process for durable index-request lease, recovery, and fencing probes. */
public final class Cp6KnowledgeRecoveryWorker {
    private Cp6KnowledgeRecoveryWorker() {}

    public static void main(String[] args) throws Exception {
        if (!"true".equals(System.getenv("CP6_IT_QDRANT_ENABLE"))) throw new IllegalStateException("Qdrant probe not enabled");
        if (args.length != 7) throw new IllegalArgumentException("Expected schema, collection, document, run dir, mode, lease millis, start barrier");
        String schema = args[0], collection = args[1], documentId = args[2], mode = args[4];
        Path run = Path.of(args[3]);
        long leaseMillis = Long.parseLong(args[5]);
        Path start = Path.of(args[6]);
        var target = new Cp6InfrastructureTarget(System.getenv());
        var jdbc = new JdbcTemplate(target.dataSource(schema));
        AtomicBoolean embeddingAttempted = new AtomicBoolean();
        Runnable gate = Set.of("gate", "contend").contains(mode) ? () -> {
            try {
                Files.writeString(run.resolve("embedding-entered"), "entered");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
                while (!Files.exists(run.resolve("release"))) {
                    if (System.nanoTime() > deadline) throw new IllegalStateException("CP6_EMBEDDING_GATE_TIMEOUT");
                    Thread.sleep(20);
                }
            } catch (Exception error) { throw new IllegalStateException(error); }
        } : null;

        try (var fixture = new Cp6KnowledgeQdrantFixture(collection, gate)) {
            if ("fail".equals(mode)) {
                doAnswer(call -> {
                    embeddingAttempted.set(true);
                    throw new IllegalStateException("CP6_EXPECTED_EMBEDDING_FAILURE");
                }).when(fixture.embedding).embed(anyString());
            } else {
                doAnswer(call -> {
                    embeddingAttempted.set(true);
                    if (gate != null) gate.run();
                    float[] vector = new float[1536];
                    vector[0] = 1;
                    return vector;
                }).when(fixture.embedding).embed(anyString());
            }
            var store = new JdbcKnowledgeDocumentStoreAdapter(jdbc);
            KnowledgeIndexPort index = fixture.adapter(fixture.cache(), store);
            if ("publish-gate".equals(mode)) index = gateAfterPublish(index, run);
            KnowledgeDocumentService service = KnowledgeTestPorts.service(store, index, Runnable::run, () -> {});
            ReflectionTestUtils.setField(service, "indexLeaseMillis", leaseMillis);
            Files.writeString(run.resolve("ready"), "ready");
            long startDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!Files.exists(start)) {
                if (System.nanoTime() > startDeadline) throw new IllegalStateException("CP6_RECOVERY_START_GATE_TIMEOUT");
                Thread.sleep(20);
            }

            if (Set.of("gate", "contend").contains(mode)) {
                int handled = service.recoverDue(1);
                if ("contend".equals(mode) && handled == 0 && !embeddingAttempted.get()) {
                    System.out.println("CP6_RECOVERY_NO_CLAIM");
                    return;
                }
                if (!embeddingAttempted.get())
                    throw new AssertionError("Expected a claimed recovery to reach embedding; handled=" + handled);
                System.out.println("CP6_RECOVERY_GATE_FINISHED handled=" + handled);
                return;
            }
            if ("publish-gate".equals(mode)) {
                int handled = service.recoverDue(1);
                if (handled != 1) throw new AssertionError("Expected published index request to finish; handled=" + handled);
                System.out.println("CP6_RECOVERY_PUBLISH_GATE_FINISHED");
                return;
            }
            if ("fail".equals(mode)) {
                int handled = service.recoverDue(1);
                if (handled != 0 || !embeddingAttempted.get())
                    throw new AssertionError("Expected the injected failure to be persisted; handled=" + handled);
                MapState state = readState(jdbc, documentId);
                if (!"failed".equals(state.vectorStatus()) || state.attempts() != 1
                        || state.nextAttemptAt() <= System.currentTimeMillis() || !state.leaseToken().isBlank())
                    throw new AssertionError("Expected persisted failure/backoff state, observed=" + state);
                int immediateRetry = service.recoverDue(1);
                if (immediateRetry != 0) throw new AssertionError("Backoff allowed an immediate retry: " + immediateRetry);
                System.out.println("CP6_EXPECTED_EMBEDDING_FAILURE_PERSISTED");
                return;
            }
            if (!"recover".equals(mode)) throw new IllegalArgumentException("Unknown worker mode: " + mode);
            int submitted = service.recoverDue(1);
            if (submitted != 1) throw new AssertionError("Expected one due index request; submitted=" + submitted);
            MapState state = readState(jdbc, documentId);
            if (!"synced".equals(state.vectorStatus()) || !Set.of("TEXT", "FILE", "DELETE").contains(state.requestMode())
                    || !state.leaseToken().isBlank() || state.leaseUntil() != 0)
                throw new AssertionError("Recovery did not finish and release the durable request: " + state);
            int duplicateDrain = service.recoverDue(1);
            if (duplicateDrain != 0) throw new AssertionError("Completed request was claimed again: " + duplicateDrain);
            System.out.println("CP6_RECOVERY_COMPLETED mode=" + state.requestMode() + " embeddingAttempted=" + embeddingAttempted.get());
        }
    }

    private static MapState readState(JdbcTemplate jdbc, String id) {
        return jdbc.queryForObject("SELECT vector_status,index_request_mode,index_attempts,index_next_attempt_at,index_lease_token,index_lease_until FROM kb_document WHERE id=?",
                (rs, row) -> new MapState(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getLong(4), rs.getString(5), rs.getLong(6)), id);
    }

    private record MapState(String vectorStatus, String requestMode, int attempts,
                            long nextAttemptAt, String leaseToken, long leaseUntil) {}

    private static KnowledgeIndexPort gateAfterPublish(KnowledgeIndexPort delegate, Path run) {
        return new KnowledgeIndexPort() {
            @Override public PreparedIndex prepareFile(String storedFilePath, KnowledgeScope scope) throws Exception {
                return gated(delegate.prepareFile(storedFilePath, scope));
            }
            @Override public PreparedIndex prepareText(String sourceName, String content, KnowledgeScope scope) throws Exception {
                return gated(delegate.prepareText(sourceName, content, scope));
            }
            @Override public PreparedIndex prepareText(String sourceName, String content, KnowledgeScope scope, String sourceUrl) throws Exception {
                return gated(delegate.prepareText(sourceName, content, scope, sourceUrl));
            }
            private PreparedIndex gated(PreparedIndex prepared) {
                return new PreparedIndex() {
                    @Override public int publish() { return prepared.publish(); }
                    @Override public int publish(long revision) {
                        int chunks = prepared.publish(revision);
                        try {
                            Files.writeString(run.resolve("qdrant-published"), "Qdrant acknowledged publication");
                            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
                            while (!Files.exists(run.resolve("release"))) {
                                if (System.nanoTime() > deadline) throw new IllegalStateException("CP6_POST_PUBLISH_GATE_TIMEOUT");
                                Thread.sleep(20);
                            }
                            return chunks;
                        } catch (Exception error) { throw new IllegalStateException(error); }
                    }
                };
            }
            @Override public void deleteSource(String sourceName) { delegate.deleteSource(sourceName); }
            @Override public void reindexFile(String storedFilePath) throws Exception { delegate.reindexFile(storedFilePath); }
            @Override public int reindexText(String sourceName, String content) throws Exception { return delegate.reindexText(sourceName, content); }
            @Override public void reindexFile(String storedFilePath, KnowledgeScope scope) throws Exception { delegate.reindexFile(storedFilePath, scope); }
            @Override public int reindexText(String sourceName, String content, KnowledgeScope scope) throws Exception { return delegate.reindexText(sourceName, content, scope); }
            @Override public int countChunks(String sourceName) { return delegate.countChunks(sourceName); }
            @Override public List<Map<String, Object>> listSourceSummaries() { return delegate.listSourceSummaries(); }
        };
    }
}
