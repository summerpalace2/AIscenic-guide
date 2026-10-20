package com.ai.guide.infrastructure.planner.proposal;

import com.ai.guide.domain.rag.model.KnowledgeDocument;
import com.ai.guide.domain.rag.service.KnowledgeDocumentService;
import com.ai.guide.infrastructure.persistence.migration.KnowledgeSchemaInitializer;
import com.ai.guide.infrastructure.rag.repository.JdbcKnowledgeDocumentStoreAdapter;
import com.ai.guide.testing.KnowledgeTestPorts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Process-death recovery checks against disposable PostgreSQL and real loopback Qdrant. */
@EnabledIfEnvironmentVariable(named = "CP6_IT_QDRANT_ENABLE", matches = "true",
        disabledReason = "Real isolated Qdrant/PG not explicitly enabled; NOT durable recovery acceptance")
class Cp6KnowledgeDurableRecoveryIntegrationTest {
    private Cp6InfrastructureTarget target;
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private JdbcKnowledgeDocumentStoreAdapter store;
    private String schema;
    private String collection;
    private Cp6KnowledgeQdrantFixture fixture;
    private KnowledgeDocumentService deferredService;
    private final Set<Worker> spawnedWorkers = new HashSet<>();

    @BeforeEach void setup() throws Exception {
        target = new Cp6InfrastructureTarget(System.getenv());
        schema = "cp6_" + UUID.randomUUID().toString().replace("-", "");
        collection = "cp6_" + UUID.randomUUID().toString().replace("-", "");
        admin = new JdbcTemplate(target.adminDataSource());
        admin.execute("CREATE SCHEMA " + schema);
        jdbc = new JdbcTemplate(target.dataSource(schema));
        new KnowledgeSchemaInitializer().initialize(jdbc, false);
        fixture = new Cp6KnowledgeQdrantFixture(collection, null);
        fixture.client.createCollectionAsync(collection, io.qdrant.client.grpc.Collections.VectorParams.newBuilder()
                .setSize(1536).setDistance(io.qdrant.client.grpc.Collections.Distance.Cosine).build()).get(10, TimeUnit.SECONDS);
        store = new JdbcKnowledgeDocumentStoreAdapter(jdbc);
        deferredService = KnowledgeTestPorts.service(store, fixture.adapter(fixture.cache(), store), ignored -> {}, () -> {});
    }

    @AfterEach void cleanup() throws Exception {
        for (Worker worker : spawnedWorkers) stop(worker);
        try {
            if (fixture != null && collection != null && collection.matches("cp6_[a-f0-9]{32}"))
                fixture.client.deleteCollectionAsync(collection).get(10, TimeUnit.SECONDS);
        } finally {
            try { if (fixture != null) fixture.close(); }
            finally { if (admin != null && schema != null) admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }
        }
    }

    @Test void killedJvmLeaseExpiresAndSecondJvmPublishesDurableRequest() throws Exception {
        KnowledgeDocument doc = createPending("进程崩溃恢复", "杀进程后必须恢复的知识正文");
        Path run = Files.createTempDirectory("cp6-kb-recovery-killed-");
        Path start = run.resolve("start");
        Worker first = startWorker(doc.getId(), run.resolve("worker-a"), "gate", 1200, start);
        try {
            awaitFile(first.run().resolve("ready"), first);
            Files.createFile(start);
            awaitFile(first.run().resolve("embedding-entered"), first);
            String token = leaseToken(doc.getId());
            assertFalse(token.isBlank(), "worker A must own a persisted lease before it is killed");
            assertEquals("syncing", vectorStatus(doc.getId()));
            first.process().destroyForcibly();
            assertTrue(first.process().waitFor(10, TimeUnit.SECONDS), "worker A did not terminate after forced process kill");
        } finally { stop(first); }

        awaitLeaseExpiry(doc.getId(), 15_000);
        Worker recovery = startWorker(doc.getId(), run.resolve("worker-b"), "recover", 1200, null);
        releaseWhenReady(recovery);
        awaitSuccess(recovery, "CP6_RECOVERY_COMPLETED");

        assertEquals("synced", vectorStatus(doc.getId()));
        assertEquals("TEXT", requestMode(doc.getId()));
        assertEquals("", leaseToken(doc.getId()));
        assertEquals(0, leaseUntil(doc.getId()));
        assertEquals(Set.of("杀进程后必须恢复的知识正文"), indexedContents());
    }

    @Test void twoJvmConsumersCannotClaimAndEmbedTheSameDurableRequest() throws Exception {
        KnowledgeDocument doc = createPending("跨实例claim", "同一个请求只允许一个有效claim");
        Path run = Files.createTempDirectory("cp6-kb-recovery-contend-");
        Path start = run.resolve("start");
        Worker first = startWorker(doc.getId(), run.resolve("worker-a"), "contend", 5000, start);
        Worker second = startWorker(doc.getId(), run.resolve("worker-b"), "contend", 5000, start);
        try {
            awaitFile(first.run().resolve("ready"), first);
            awaitFile(second.run().resolve("ready"), second);
            Files.createFile(start);
            Worker claimant = awaitSingleEmbeddingGate(first, second);
            Worker nonClaimant = claimant == first ? second : first;
            awaitSuccess(nonClaimant, "CP6_RECOVERY_NO_CLAIM");
            Files.createFile(claimant.run().resolve("release"));
            awaitSuccess(claimant, "CP6_RECOVERY_GATE_FINISHED handled=1");
            assertEquals(Set.of("同一个请求只允许一个有效claim"), indexedContents());
            assertEquals("synced", vectorStatus(doc.getId()));
        } finally { stop(first); stop(second); }
    }

    @Test void embeddingFailurePersistsBackoffAndRetriesAfterDueTime() throws Exception {
        KnowledgeDocument doc = createPending("失败退避", "失败后应按退避时间重试的资料");
        Path run = Files.createTempDirectory("cp6-kb-recovery-backoff-");
        Worker failed = startWorker(doc.getId(), run.resolve("failed"), "fail", 1200, null);
        releaseWhenReady(failed);
        awaitSuccess(failed, "CP6_EXPECTED_EMBEDDING_FAILURE_PERSISTED");
        assertEquals("failed", vectorStatus(doc.getId()));
        assertEquals(1, attempts(doc.getId()));
        long dueAt = nextAttemptAt(doc.getId());
        assertTrue(dueAt > System.currentTimeMillis(), "failure must persist a future retry deadline");
        assertTrue(leaseToken(doc.getId()).isBlank());
        assertTrue(indexedContents().isEmpty());

        awaitRetryDue(doc.getId(), 60_000);
        Worker retry = startWorker(doc.getId(), run.resolve("retry"), "recover", 1200, null);
        releaseWhenReady(retry);
        awaitSuccess(retry, "CP6_RECOVERY_COMPLETED");
        assertEquals("synced", vectorStatus(doc.getId()));
        assertEquals(Set.of("失败后应按退避时间重试的资料"), indexedContents());
    }

    @Test void newRevisionAndDeleteRevokeOldLeaseBeforeOldJvmCanPublish() throws Exception {
        Path run = Files.createTempDirectory("cp6-kb-recovery-fence-");
        KnowledgeDocument doc = createPending("新版隔离旧lease", "旧版内容不能覆盖新版");
        Worker oldRevision = startWorker(doc.getId(), run.resolve("old-revision"), "gate", 15_000, null);
        releaseWhenEmbeddingEntered(oldRevision);
        String oldToken = leaseToken(doc.getId());
        assertFalse(oldToken.isBlank());
        deferredService.updateDocument(doc.getId(), null, null, "最新修订内容", null);
        assertTrue(leaseToken(doc.getId()).isBlank(), "new revision must revoke the old lease token");
        Worker newRevision = startWorker(doc.getId(), run.resolve("new-revision"), "recover", 5000, null);
        releaseWhenReady(newRevision);
        awaitSuccess(newRevision, "CP6_RECOVERY_COMPLETED");
        Files.createFile(oldRevision.run().resolve("release"));
        awaitSuccess(oldRevision, "CP6_RECOVERY_GATE_FINISHED handled=0");
        assertEquals(Set.of("最新修订内容"), indexedContents(), "late old lease must not overwrite the new Qdrant revision");
        assertEquals("synced", vectorStatus(doc.getId()));

        KnowledgeDocument deletedDoc = createPending("删除隔离旧lease", "删除后不能被旧任务复活");
        Worker oldDeleteRace = startWorker(deletedDoc.getId(), run.resolve("old-delete"), "gate", 15_000, null);
        releaseWhenEmbeddingEntered(oldDeleteRace);
        deferredService.deleteDocument(deletedDoc.getId());
        assertEquals("DELETE", requestMode(deletedDoc.getId()));
        assertEquals("archived", status(deletedDoc.getId()));
        Worker deleteRecovery = startWorker(deletedDoc.getId(), run.resolve("delete-recovery"), "recover", 5000, null);
        releaseWhenReady(deleteRecovery);
        awaitSuccess(deleteRecovery, "CP6_RECOVERY_COMPLETED");
        Files.createFile(oldDeleteRace.run().resolve("release"));
        awaitSuccess(oldDeleteRace, "CP6_RECOVERY_GATE_FINISHED handled=0");
        assertEquals("archived", status(deletedDoc.getId()));
        assertEquals("DELETE", requestMode(deletedDoc.getId()));
        assertTrue(indexedContents().stream().noneMatch(value -> value.contains("删除后不能被旧任务复活")));
        assertEquals(Set.of("最新修订内容"), indexedContents());
    }

    @Test void processDeathAfterQdrantPublishBeforeSqlCommitRecoversAndReconcilesGeneration() throws Exception {
        Path run = Files.createTempDirectory("cp6-kb-recovery-publish-window-");
        KnowledgeDocument doc = createPending("Qdrant先写SQL后提交", "原始已发布版本");
        Worker initial = startWorker(doc.getId(), run.resolve("initial"), "recover", 1200, null);
        releaseWhenReady(initial);
        awaitSuccess(initial, "CP6_RECOVERY_COMPLETED");
        assertEquals(Set.of("原始已发布版本"), indexedContents());
        long oldRevision = indexRevision(doc.getId());

        deferredService.updateDocument(doc.getId(), null, null, "Qdrant写成但SQL完成前进程死亡", null);
        assertTrue(indexRevision(doc.getId()) > oldRevision);
        Worker interrupted = startWorker(doc.getId(), run.resolve("interrupted"), "publish-gate", 1200, null);
        releaseWhenReady(interrupted);
        awaitFile(interrupted.run().resolve("qdrant-published"), interrupted);

        assertEquals("syncing", vectorStatus(doc.getId()), "the durable claim must remain unfinished while child is gated inside SQL transaction");
        assertFalse(leaseToken(doc.getId()).isBlank());
        assertEquals(Set.of("Qdrant写成但SQL完成前进程死亡"), indexedContents(), "Qdrant publish must have completed before the crash gate");
        assertTrue(fixture.adapter(fixture.cache(), store).searchScoped("知识", 10, 10, false,
                com.ai.guide.domain.rag.model.KnowledgeScope.global()).isEmpty(),
                "SQL syncing state must keep the uncommitted generation invisible to readers");
        interrupted.process().destroyForcibly();
        assertTrue(interrupted.process().waitFor(10, TimeUnit.SECONDS), "worker did not die in the Qdrant/SQL commit window");

        awaitLeaseExpiry(doc.getId(), 15_000);
        Worker recovery = startWorker(doc.getId(), run.resolve("recovery"), "recover", 1200, null);
        releaseWhenReady(recovery);
        awaitSuccess(recovery, "CP6_RECOVERY_COMPLETED");

        assertEquals("synced", vectorStatus(doc.getId()));
        assertEquals(Set.of("Qdrant写成但SQL完成前进程死亡"), indexedContents());
        assertEquals(1, fixture.points().size(), "recovery must remove the abandoned pre-crash generation");
        String revision = fixture.points().getFirst().getPayloadOrThrow("index_revision").getStringValue();
        assertEquals(Long.toString(indexRevision(doc.getId())), revision);
    }

    private KnowledgeDocument createPending(String title, String content) throws Exception {
        KnowledgeDocument document = deferredService.createDocument(title, "guide", content, "[]", null,
                "cp6-test", "GLOBAL", "");
        assertEquals("TEXT", requestMode(document.getId()), "create must persist indexing intent before asynchronous work");
        assertEquals("pending", vectorStatus(document.getId()));
        return document;
    }

    private Worker startWorker(String documentId, Path run, String mode, long leaseMillis, Path start) throws Exception {
        Files.createDirectories(run);
        Path startFile = start == null ? run.resolve("start") : start;
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-cp", classpath, Cp6KnowledgeRecoveryWorker.class.getName(), schema, collection, documentId,
                run.toString(), mode, Long.toString(leaseMillis), startFile.toString());
        Path log = run.resolve("worker.log");
        builder.directory(run.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        var inherited = new HashMap<>(System.getenv());
        builder.environment().clear();
        inherited.forEach((key, value) -> {
            if (key.startsWith("CP6_IT_") || Set.of("SystemRoot", "WINDIR", "TEMP", "TMP").contains(key))
                builder.environment().put(key, value);
        });
        Worker worker = new Worker(builder.start(), run, log);
        spawnedWorkers.add(worker);
        return worker;
    }

    private Worker awaitSingleEmbeddingGate(Worker first, Worker second) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            boolean firstEntered = Files.exists(first.run().resolve("embedding-entered"));
            boolean secondEntered = Files.exists(second.run().resolve("embedding-entered"));
            assertFalse(firstEntered && secondEntered, "two JVMs entered embedding for the same request");
            if (firstEntered) return first;
            if (secondEntered) return second;
            if (!first.process().isAlive() && !second.process().isAlive()) fail("Neither worker claimed the request: " + logs(first, second));
            Thread.sleep(25);
        }
        fail("No worker claimed request before timeout: " + logs(first, second));
        return null;
    }

    private void releaseWhenReady(Worker worker) throws Exception {
        awaitFile(worker.run().resolve("ready"), worker);
        Files.createFile(worker.run().resolve("start"));
    }

    private void releaseWhenEmbeddingEntered(Worker worker) throws Exception {
        releaseWhenReady(worker);
        awaitFile(worker.run().resolve("embedding-entered"), worker);
    }

    private void awaitFile(Path file, Worker worker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline && worker.process().isAlive() && !Files.exists(file)) Thread.sleep(25);
        assertTrue(Files.exists(file), "worker failed to reach " + file + "; " + log(worker));
    }

    private void awaitSuccess(Worker worker, String marker) throws Exception {
        assertTrue(worker.process().waitFor(60, TimeUnit.SECONDS), "worker timed out; " + log(worker));
        assertEquals(0, worker.process().exitValue(), "worker failed; " + log(worker));
        assertTrue(Files.readString(worker.log()).contains(marker), "worker did not report expected result " + marker + "; " + log(worker));
    }

    private void awaitLeaseExpiry(String id, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (leaseUntil(id) <= System.currentTimeMillis()) return;
            Thread.sleep(25);
        }
        fail("lease did not expire within " + timeoutMillis + "ms; until=" + leaseUntil(id));
    }

    private void awaitRetryDue(String id, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (nextAttemptAt(id) <= System.currentTimeMillis()) return;
            Thread.sleep(50);
        }
        fail("persisted retry did not become due within " + timeoutMillis + "ms; next=" + nextAttemptAt(id));
    }

    private String status(String id) { return jdbc.queryForObject("SELECT status FROM kb_document WHERE id=?", String.class, id); }
    private String vectorStatus(String id) { return jdbc.queryForObject("SELECT vector_status FROM kb_document WHERE id=?", String.class, id); }
    private long indexRevision(String id) { return jdbc.queryForObject("SELECT index_revision FROM kb_document WHERE id=?", Long.class, id); }
    private String requestMode(String id) { return jdbc.queryForObject("SELECT index_request_mode FROM kb_document WHERE id=?", String.class, id); }
    private int attempts(String id) { return jdbc.queryForObject("SELECT index_attempts FROM kb_document WHERE id=?", Integer.class, id); }
    private long nextAttemptAt(String id) { return jdbc.queryForObject("SELECT index_next_attempt_at FROM kb_document WHERE id=?", Long.class, id); }
    private String leaseToken(String id) { return jdbc.queryForObject("SELECT index_lease_token FROM kb_document WHERE id=?", String.class, id); }
    private long leaseUntil(String id) { return jdbc.queryForObject("SELECT index_lease_until FROM kb_document WHERE id=?", Long.class, id); }

    private Set<String> indexedContents() throws Exception {
        Set<String> values = new HashSet<>();
        for (var point : fixture.points()) values.add(point.getPayloadOrThrow("content").getStringValue());
        return values;
    }

    private void stop(Worker worker) throws Exception {
        if (worker != null && worker.process().isAlive()) {
            worker.process().destroyForcibly();
            worker.process().waitFor(10, TimeUnit.SECONDS);
        }
    }

    private String log(Worker worker) throws Exception {
        return "log=" + (Files.exists(worker.log()) ? Files.readString(worker.log()) : "<missing>");
    }

    private String logs(Worker... workers) throws Exception {
        var result = new StringBuilder();
        for (Worker worker : workers) result.append(worker.run()).append(':').append(log(worker)).append('\n');
        return result.toString();
    }

    private record Worker(Process process, Path run, Path log) {}
}
