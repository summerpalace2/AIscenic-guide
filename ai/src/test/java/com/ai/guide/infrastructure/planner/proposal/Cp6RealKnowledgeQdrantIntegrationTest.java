package com.ai.guide.infrastructure.planner.proposal;

import com.ai.guide.domain.rag.model.KnowledgeScope;
import com.ai.guide.domain.rag.service.KnowledgeDocumentService;
import com.ai.guide.infrastructure.persistence.migration.KnowledgeSchemaInitializer;
import com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter;
import com.ai.guide.infrastructure.rag.importer.ProductionV2ChunkService;
import com.ai.guide.infrastructure.rag.qdrant.ProductionV2QdrantImporter;
import com.ai.guide.infrastructure.rag.repository.JdbcKnowledgeDocumentStoreAdapter;
import com.ai.guide.testing.KnowledgeTestPorts;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Points;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real disposable PostgreSQL and Qdrant; only embedding/reranking are deterministic local suppliers. */
@EnabledIfEnvironmentVariable(named = "CP6_IT_QDRANT_ENABLE", matches = "true",
        disabledReason = "Real isolated Qdrant/PG not explicitly enabled; NOT Qdrant acceptance")
class Cp6RealKnowledgeQdrantIntegrationTest {
    Cp6InfrastructureTarget target;
    JdbcTemplate admin, jdbc;
    JdbcKnowledgeDocumentStoreAdapter store;
    String schema, collection;
    Cp6KnowledgeQdrantFixture fixture;
    KnowledgeDocumentService service;

    @BeforeEach void setup() throws Exception {
        target = new Cp6InfrastructureTarget(System.getenv());
        schema = "cp6_" + UUID.randomUUID().toString().replace("-", "");
        collection = "cp6_" + UUID.randomUUID().toString().replace("-", "");
        admin = new JdbcTemplate(target.adminDataSource()); admin.execute("CREATE SCHEMA " + schema);
        jdbc = new JdbcTemplate(target.dataSource(schema)); new KnowledgeSchemaInitializer().initialize(jdbc, false);
        fixture = new Cp6KnowledgeQdrantFixture(collection, null);
        fixture.client.createCollectionAsync(collection, Collections.VectorParams.newBuilder()
                .setSize(1536).setDistance(Collections.Distance.Cosine).build()).get(10, TimeUnit.SECONDS);
        store = new JdbcKnowledgeDocumentStoreAdapter(jdbc);
        service = KnowledgeTestPorts.service(store, fixture.adapter(fixture.cache(), store));
    }
    @AfterEach void cleanup() throws Exception {
        try {
            // A create reply can be lost after the server created the collection.
            // Cleanup is guarded by our generated name, not by receipt delivery.
            if (fixture != null && collection != null && collection.matches("cp6_[a-f0-9]{32}"))
                fixture.client.deleteCollectionAsync(collection).get(10, TimeUnit.SECONDS);
        } finally {
            try { if (fixture != null) fixture.close(); }
            finally { if (admin != null && schema != null) admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }
        }
    }

    @Test void realCrudFiltersBeforeLimitAndPeerCacheCannotRetainEditedOrDeletedContent() throws Exception {
        for (int i = 0; i < 4; i++) service.createDocument("异城" + i, "guide", "北京市异城资料" + i, "[]", null, "fixture", "CITY", "北京市");
        var doc = service.createDocument("本城", "guide", "杭州旧资料锚点", "[]", null, "fixture", "CITY", "杭州市");
        service.createDocument("通用", "guide", "全国通用资料", "[]", null, "fixture", "GLOBAL", "");
        service.createDocument("未标注", "guide", "不应成为任何城市资料", "[]", null, "fixture");
        var peerCache = fixture.cache(); var peer = fixture.adapter(peerCache, store);
        var city = new KnowledgeScope("CITY", "杭州市");
        var first = peer.searchScoped("知识资料", 2, 2, false, city);
        assertEquals(Set.of("杭州旧资料锚点", "全国通用资料"), contents(first));
        assertEquals(contents(first), contents(peer.searchScoped("知识资料", 2, 2, false, city)));
        assertTrue(((Number) peerCache.getCacheStats().get("l1Hits")).longValue() > 0);
        service.updateDocument(doc.getId(), null, null, "杭州更新资料锚点", null);
        assertEquals(Set.of("杭州更新资料锚点", "全国通用资料"), contents(peer.searchScoped("知识资料", 2, 2, false, city)));
        service.updateDocument(doc.getId(), null, null, null, null, "CITY", "北京市");
        assertEquals(Set.of("全国通用资料"), contents(peer.searchScoped("知识资料", 2, 2, true, city)));
        assertTrue(contents(peer.searchScoped("知识资料", 20, 20, false, new KnowledgeScope("CITY", "北京市")))
                .contains("杭州更新资料锚点"));
        service.deleteDocument(doc.getId());
        assertFalse(contents(peer.searchScoped("知识资料", 20, 20, false, new KnowledgeScope("CITY", "北京市")))
                .contains("杭州更新资料锚点"));
        service.backfillDocumentsFromVectors();
        assertEquals("archived", service.getDocument(doc.getId()).getStatus());
        assertTrue(fixture.points().stream().noneMatch(p -> p.getPayloadOrDefault("source", io.qdrant.client.ValueFactory.value("")).getStringValue().equals(doc.getSourceName())));
    }

    @Test void repeatedRealSeedImportPreservesManagedDocumentAndOnlyReconcilesItsOwnSnapshot(@TempDir Path source) throws Exception {
        var managed = service.createDocument("管理资料", "guide", "管理员上传知识必须保留", "[]", null, "fixture", "GLOBAL", "");
        writeSeed(source, "初始种子知识正文");
        var mapper = new ObjectMapper();
        var importer = new ProductionV2QdrantImporter(new ProductionV2ChunkService(mapper, new JsonTaxonomyAdapter()),
                fixture.client, fixture.embedding, mapper, collection);
        assertEquals("PASS", importer.importData(source, false, null).status());
        writeSeed(source, "更新后的种子知识正文");
        assertEquals("PASS", importer.importData(source, false, null).status());
        assertEquals(2L, fixture.client.countAsync(collection).get(10, TimeUnit.SECONDS));
        var points = fixture.points();
        assertTrue(points.stream().anyMatch(p -> p.getPayloadOrDefault("source", io.qdrant.client.ValueFactory.value("")).getStringValue().equals(managed.getSourceName())));
        assertTrue(points.stream().anyMatch(p -> p.getPayloadOrThrow("content").getStringValue().contains("更新后的种子")));
        assertTrue(points.stream().noneMatch(p -> p.getPayloadOrThrow("content").getStringValue().contains("初始种子")));
        assertEquals(Set.of("管理员上传知识必须保留", "更新后的种子知识正文"), contents(fixture.adapter(fixture.cache(), store)
                .searchScoped("知识", 20, 20, false, KnowledgeScope.global())));
        Files.writeString(source.resolve("knowledge_documents.json"), "[]");
        assertEquals("PASS", importer.importData(source, false, null).status());
        assertEquals(1L, fixture.client.countAsync(collection).get(10, TimeUnit.SECONDS));
        assertEquals("managed-document", fixture.points().getFirst().getPayloadOrThrow("index_owner").getStringValue());
    }

    @Test void archivedDocumentCannotBeResurrectedByAnotherJvmEmbeddingTask(@TempDir Path run) throws Exception { crossJvm(run, false); }
    @Test void cityChangeCannotBeRevertedByAnotherJvmEmbeddingTask(@TempDir Path run) throws Exception { crossJvm(run, true); }

    @Test void realOldIndexSurvivesEmbeddingFailure() throws Exception {
        var doc = service.createDocument("失败保全", "guide", "已有可用知识资料", "[]", null, "fixture", "GLOBAL", "");
        when(fixture.embedding.embed(anyString())).thenThrow(new IllegalStateException("fixture provider failed"));
        assertThrows(RuntimeException.class, () -> service.triggerSync(doc.getId()));
        assertEquals("failed", service.getDocument(doc.getId()).getVectorStatus());
        assertEquals(1L, fixture.client.countAsync(collection).get(10, TimeUnit.SECONDS));
        assertEquals("已有可用知识资料", fixture.points().getFirst().getPayloadOrThrow("content").getStringValue());
    }

    @Test void backfillRestoresPublishedRevisionInsteadOfHidingExistingVectors() throws Exception {
        var doc = service.createDocument("回填版本", "guide", "第一版知识资料", "[]", null, "fixture", "GLOBAL", "");
        service.updateDocument(doc.getId(), null, null, "第二版知识资料", null);
        long publishedRevision = store.indexRevision(doc.getId());
        assertTrue(publishedRevision > 0);
        assertEquals(1, jdbc.update("DELETE FROM kb_document WHERE id = ?", doc.getId()));

        var outcome = service.backfillDocumentsFromVectors();
        assertEquals(1, outcome.get("created"));
        assertEquals(publishedRevision, jdbc.queryForObject(
                "SELECT index_revision FROM kb_document WHERE source_name = ?", Long.class, doc.getSourceName()));
        assertEquals(Set.of("第二版知识资料"), contents(fixture.adapter(fixture.cache(), store)
                .searchScoped("知识资料", 10, 10, false, KnowledgeScope.global())));
    }

    @Test void staleBackfillCannotHideNewerPublishedRevision() throws Exception {
        var doc = service.createDocument("并发回填", "guide", "第一版知识资料", "[]", null, "fixture", "GLOBAL", "");
        long oldRevision = store.indexRevision(doc.getId());
        service.updateDocument(doc.getId(), null, null, "第二版知识资料", null);
        assertTrue(store.indexRevision(doc.getId()) > oldRevision);
        assertEquals(com.ai.guide.domain.rag.gateway.KnowledgeDocumentStorePort.BackfillOutcome.SKIPPED,
                store.backfill(doc.getSourceName(), "第一版知识资料", 1, "2026-10-13 18:00:00",
                        KnowledgeScope.global(), oldRevision));
        assertEquals("synced", service.getDocument(doc.getId()).getVectorStatus());
        assertEquals(Set.of("第二版知识资料"), contents(fixture.adapter(fixture.cache(), store)
                .searchScoped("知识资料", 10, 10, false, KnowledgeScope.global())));
    }

    @Test void concurrentBackfillCreatesOnlyOneActiveSource() throws Exception {
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 2; i++) tasks.add(workers.submit(() -> {
                ready.countDown();
                try { start.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new RuntimeException(interrupted); }
                new JdbcKnowledgeDocumentStoreAdapter(new JdbcTemplate(target.dataSource(schema)))
                        .backfill("same-source", "正文", 1, "2026-10-13 18:00:00", KnowledgeScope.global(), 0L);
            }));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM kb_document WHERE source_name = 'same-source'", Integer.class));
        } finally { workers.shutdownNow(); }
    }

    @Test void backfillDoesNotMergeSameTitleLegacyRowIntoPublishedSource() throws Exception {
        var doc = service.createDocument("正常来源", "guide", "可检索的知识资料", "[]", null, "fixture", "GLOBAL", "");
        jdbc.update("INSERT INTO kb_document (id,title,source_name,scope,city,vector_status) VALUES (?,?, '', 'GLOBAL', '', 'pending')",
                UUID.randomUUID().toString(), doc.getSourceName());
        long revision = store.indexRevision(doc.getId());
        assertEquals(com.ai.guide.domain.rag.gateway.KnowledgeDocumentStorePort.BackfillOutcome.UPDATED,
                store.backfill(doc.getSourceName(), "可检索的知识资料", 1, "2026-10-13 18:00:00",
                        KnowledgeScope.global(), revision));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM kb_document WHERE source_name = ?", Integer.class, doc.getSourceName()));
        assertEquals(Set.of("可检索的知识资料"), contents(fixture.adapter(fixture.cache(), store)
                .searchScoped("知识资料", 10, 10, false, KnowledgeScope.global())));
    }

    @Test void failedPublicationWithOldAndNewQdrantPointsCannotEnterRetrieval() throws Exception {
        var doc = service.createDocument("故障隔离", "guide", "旧版知识资料", "[]", null, "fixture", "GLOBAL", "");
        var reader = fixture.adapter(fixture.cache(), store);
        assertEquals(Set.of("旧版知识资料"), contents(reader.searchScoped("知识资料", 10, 10, false, KnowledgeScope.global())));
        String oldRevision = fixture.points().getFirst().getPayloadOrThrow("index_revision").getStringValue();

        String revised = "新版知识资料";
        assertEquals(1, store.update(doc.getId(), null, null, revised, null, doc.getSourceName(), true,
                java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));
        float[] vector = fixture.embedding.embed(revised);
        var coordinates = new ArrayList<Float>(vector.length);
        for (float value : vector) coordinates.add(value);
        var newerPoint = Points.PointStruct.newBuilder()
                .setId(PointIdFactory.id(UUID.randomUUID()))
                .setVectors(VectorsFactory.vectors(coordinates))
                .putPayload("content", ValueFactory.value(revised))
                .putPayload("source", ValueFactory.value(doc.getSourceName()))
                .putPayload("scope", ValueFactory.value("GLOBAL"))
                .putPayload("city", ValueFactory.value(""))
                .putPayload("activation_namespace", ValueFactory.value("production-v2"))
                .putPayload("index_owner", ValueFactory.value("managed-document"))
                .putPayload("rag_eligible", ValueFactory.value(true)).build();
        fixture.client.upsertAsync(collection, List.of(newerPoint)).get(10, TimeUnit.SECONDS);
        assertEquals(2L, fixture.client.countAsync(collection).get(10, TimeUnit.SECONDS));
        assertTrue(reader.searchScoped("知识资料", 10, 10, false, KnowledgeScope.global()).isEmpty(),
                "Pending SQL publication must hide both Qdrant generations");
        assertEquals(1, store.markFailed(doc.getId(), store.indexRevision(doc.getId())));
        assertTrue(reader.searchScoped("知识资料", 10, 10, false, KnowledgeScope.global()).isEmpty());

        service.triggerSync(doc.getId());
        assertEquals("synced", service.getDocument(doc.getId()).getVectorStatus());
        assertEquals(1L, fixture.client.countAsync(collection).get(10, TimeUnit.SECONDS));
        assertEquals(Set.of(revised), contents(reader.searchScoped("知识资料", 10, 10, false, KnowledgeScope.global())));

        // A search may already have captured an old point when SQL becomes synced.
        // Reintroduce that point to verify revision fencing, then deny an unowned point.
        var stale = newerPoint.toBuilder().setId(PointIdFactory.id(UUID.randomUUID()))
                .putPayload("content", ValueFactory.value("旧版知识资料"))
                .putPayload("index_revision", ValueFactory.value(oldRevision)).build();
        var unowned = newerPoint.toBuilder().setId(PointIdFactory.id(UUID.randomUUID()))
                .putPayload("content", ValueFactory.value("伪造的无主知识"))
                .putPayload("source", ValueFactory.value(""))
                .putPayload("index_owner", ValueFactory.value("unknown-owner")).build();
        fixture.client.upsertAsync(collection, List.of(stale, unowned)).get(10, TimeUnit.SECONDS);
        assertEquals(Set.of(revised), contents(reader.searchScoped("知识资料", 10, 10, false, KnowledgeScope.global())));
    }

    void crossJvm(Path run, boolean changeCity) throws Exception {
        var doc = service.createDocument("跨进程", "guide", "跨进程受控知识正文", "[]", null, "fixture", "CITY", "北京市");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path log = run.resolve("worker.log");
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-cp", classpath, Cp6KnowledgeQdrantWorker.class.getName(), schema, collection, doc.getId(), run.toString());
        builder.directory(run.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        var inherited = new HashMap<>(System.getenv()); builder.environment().clear();
        inherited.forEach((key, value) -> { if (key.startsWith("CP6_IT_") || Set.of("SystemRoot", "WINDIR", "TEMP", "TMP").contains(key)) builder.environment().put(key, value); });
        Process child = builder.start();
        try {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            while (!Files.exists(run.resolve("ready")) && child.isAlive() && System.nanoTime() < until) Thread.sleep(20);
            assertTrue(Files.exists(run.resolve("ready")), "Separate JVM did not reach embedding gate; log: " + log);
            if (changeCity) service.updateDocument(doc.getId(), null, null, null, null, "CITY", "杭州市");
            else service.deleteDocument(doc.getId());
            Files.writeString(run.resolve("release"), "release old preparation");
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Separate JVM timed out; worker output: " + readWorkerLog(log));
            String workerOutput = readWorkerLog(log);
            assertEquals(0, child.exitValue(), "Separate JVM rejected contract; worker output: " + workerOutput);
            assertTrue(workerOutput.contains("CP6_STALE_PUBLICATION_REJECTED"), "Worker did not report the expected stale-publication result: " + workerOutput);
            var points = fixture.points();
            assertEquals(changeCity ? 1 : 0, points.size());
            if (changeCity) {
                assertEquals("杭州市", points.getFirst().getPayloadOrThrow("city").getStringValue());
                assertEquals("synced", service.getDocument(doc.getId()).getVectorStatus());
            } else assertEquals("archived", service.getDocument(doc.getId()).getStatus());
        } finally { if (child.isAlive()) { child.destroy(); if (!child.waitFor(2, TimeUnit.SECONDS)) child.destroyForcibly(); } }
    }

    Set<String> contents(java.util.List<com.ai.guide.domain.rag.model.KnowledgeFragment> rows) {
        var result = new HashSet<String>(); rows.forEach(r -> result.add(r.content())); return result;
    }
    String readWorkerLog(Path log) throws Exception {
        return Files.exists(log) ? Files.readString(log) : "<worker log missing at " + log + ">";
    }
    void writeSeed(Path source, String content) throws Exception {
        var mapper = new ObjectMapper();
        Files.writeString(source.resolve("source_documents.json"), mapper.writeValueAsString(List.of(Map.of(
                "source_id", "fixture-source", "source_url", "https://fixture.invalid/source", "publisher", "fixture", "source_title", "fixture"))));
        Files.writeString(source.resolve("entities.json"), "[]");
        var document = new LinkedHashMap<String,Object>();
        document.put("document_id", "fixture-document"); document.put("entity_id", "fixture-entity"); document.put("source_id", "fixture-source");
        document.put("source_url", "https://fixture.invalid/source"); document.put("title", "种子资料");
        document.put("content", content); document.put("trust_level", "reviewed"); document.put("verification_result", "verified");
        document.put("source_updated_at", "2026-10-13"); document.put("accessed_at", "2026-10-13");
        document.put("rag_eligible", true); document.put("scope", "GLOBAL");
        Files.writeString(source.resolve("knowledge_documents.json"), mapper.writeValueAsString(List.of(document)));
    }
}
