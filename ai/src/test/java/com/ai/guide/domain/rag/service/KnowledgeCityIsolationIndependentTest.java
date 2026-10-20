package com.ai.guide.domain.rag.service;

import com.ai.guide.domain.rag.model.KnowledgeScope;
import com.ai.guide.infrastructure.persistence.migration.KnowledgeSchemaInitializer;
import com.ai.guide.infrastructure.rag.repository.JdbcKnowledgeDocumentStoreAdapter;
import com.ai.guide.testing.KnowledgeTestPorts;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Independent acceptance: actual SQLite SQL, no supplier/network or mocked repository. */
class KnowledgeCityIsolationIndependentTest {
    SingleConnectionDataSource source;
    JdbcTemplate jdbc;
    KnowledgeDocumentService service;

    @BeforeEach void setup() {
        source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE kb_document (id TEXT PRIMARY KEY,title TEXT NOT NULL,category TEXT DEFAULT '',content TEXT DEFAULT '',tags TEXT DEFAULT '[]',file_url TEXT DEFAULT '',file_md5 TEXT DEFAULT '',source_name TEXT DEFAULT '',status TEXT DEFAULT 'active',vector_status TEXT DEFAULT 'pending',chunk_count INTEGER DEFAULT 0,created_by TEXT DEFAULT '',created_at TEXT DEFAULT '2026-10-13 00:00:00',updated_at TEXT DEFAULT '2026-10-13 00:00:00')");
        jdbc.update("INSERT INTO kb_document(id,title,content) VALUES('legacy','北京门票价格','北京门票价格旧资料')");
        jdbc.execute("CREATE TABLE attraction_catalog_entry(id TEXT PRIMARY KEY,city TEXT NOT NULL,source_url TEXT NOT NULL,verified_at TEXT NOT NULL)");
        jdbc.update("INSERT INTO attraction_catalog_entry(id,city,source_url,verified_at) VALUES('legacy-attraction','北京','https://example.invalid','2026-10-13')");
        new KnowledgeSchemaInitializer().initialize(jdbc, true);
        assertEquals("", jdbc.queryForObject("SELECT source_url FROM kb_document WHERE id='legacy'", String.class));
        assertEquals("[]", jdbc.queryForObject("SELECT verified_fields FROM attraction_catalog_entry WHERE id='legacy-attraction'", String.class));
        service = KnowledgeTestPorts.service(new JdbcKnowledgeDocumentStoreAdapter(jdbc), KnowledgeTestPorts.noIndex());
    }
    @AfterEach void close() { source.destroy(); }

    void doc(String id, String scope, String city, String title) {
        jdbc.update("INSERT INTO kb_document(id,title,content,scope,city,source_name) VALUES(?,?,?,?,?,?)",
                id, title, "门票价格测试资料 " + id, scope, city, id);
    }
    Set<String> ids(List<Map<String,Object>> rows) {
        var ids = new LinkedHashSet<String>(); rows.forEach(r -> ids.add(String.valueOf(r.get("id")))); return ids;
    }
    @Test void legacyMigrationDoesNotAuthorizeContentMentionAndIsIdempotent() {
        assertEquals("UNSPECIFIED", jdbc.queryForObject("SELECT scope FROM kb_document WHERE id='legacy'", String.class));
        assertEquals("", jdbc.queryForObject("SELECT city FROM kb_document WHERE id='legacy'", String.class));
        assertEquals("北京门票价格旧资料", jdbc.queryForObject("SELECT content FROM kb_document WHERE id='legacy'", String.class));
        new KnowledgeSchemaInitializer().initialize(jdbc, true);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM schema_migration WHERE version=9", Integer.class));
        assertTrue(service.searchLocalKnowledge("北京门票价格", 20, new KnowledgeScope("CITY", "北京市")).isEmpty());
    }
    @Test void sameQueryReturnsOnlyRequestedCityAndExplicitGlobal() {
        doc("beijing", "CITY", "北京市", "门票价格");
        doc("hangzhou", "CITY", "杭州市", "门票价格");
        doc("global", "GLOBAL", "", "门票价格");
        assertEquals(Set.of("beijing", "global"), ids(service.searchLocalKnowledge("门票价格", 20, new KnowledgeScope("CITY", "北京市"))));
        assertEquals(Set.of("hangzhou", "global"), ids(service.searchLocalKnowledge("门票价格", 20, new KnowledgeScope("CITY", "杭州市"))));
        assertEquals(Set.of("global"), ids(service.searchLocalKnowledge("门票价格", 20)));
    }
    @Test void otherCityCannotStarveSameCityBeforeCandidateLimit() {
        for (int i=0;i<220;i++) doc("wrong-"+i, "CITY", "北京市", "门票价格 门票价格 门票价格");
        doc("right", "CITY", "杭州市", "资料");
        assertEquals(Set.of("right"), ids(service.searchLocalKnowledge("门票价格", 1, new KnowledgeScope("CITY", "杭州市"))));
    }
    @Test void authoritativeLocalScopeChangesImmediatelyAndArchivedNeverReturns() {
        doc("moving", "CITY", "北京市", "门票价格");
        assertEquals(Set.of("moving"), ids(service.searchLocalKnowledge("门票价格", 20, new KnowledgeScope("CITY", "北京市"))));
        jdbc.update("UPDATE kb_document SET city='杭州市' WHERE id='moving'");
        assertTrue(service.searchLocalKnowledge("门票价格", 20, new KnowledgeScope("CITY", "北京市")).isEmpty());
        assertEquals(Set.of("moving"), ids(service.searchLocalKnowledge("门票价格", 20, new KnowledgeScope("CITY", "杭州市"))));
        jdbc.update("UPDATE kb_document SET status='archived' WHERE id='moving'");
        assertTrue(service.searchLocalKnowledge("门票价格", 20, new KnowledgeScope("CITY", "杭州市")).isEmpty());
    }
    @Test void scopeChangePersistsBeforeFailedReindexAndHidesOldVectors() throws Exception {
        doc("blocked", "CITY", "北京市", "门票价格");
        var index = mock(com.ai.guide.domain.rag.gateway.KnowledgeIndexPort.class);
        when(index.prepareText(anyString(), anyString(), any())).thenThrow(new IllegalStateException("isolated reindex failure"));
        service = KnowledgeTestPorts.service(new JdbcKnowledgeDocumentStoreAdapter(jdbc), index);
        service.updateDocument("blocked", null, null, null, null, "CITY", "杭州市");
        assertEquals("杭州市", service.getDocument("blocked").getCity());
        assertEquals("failed", service.getDocument("blocked").getVectorStatus());
        assertEquals(1L, jdbc.queryForObject("SELECT index_revision FROM kb_document WHERE id='blocked'", Long.class));
        assertEquals("TEXT", jdbc.queryForObject("SELECT index_request_mode FROM kb_document WHERE id='blocked'", String.class));
        assertTrue(new JdbcKnowledgeDocumentStoreAdapter(jdbc).visibleSources(Set.of("blocked")).isEmpty());
        verify(index, never()).deleteSource("blocked");
    }
    @Test void changingScopeAndClearingContentNeverReindexesOldContent() throws Exception {
        doc("clear", "CITY", "北京市", "门票价格");
        var index = mock(com.ai.guide.domain.rag.gateway.KnowledgeIndexPort.class);
        service = KnowledgeTestPorts.service(new JdbcKnowledgeDocumentStoreAdapter(jdbc), index);
        var updated = service.updateDocument("clear", null, null, "", null, "CITY", "杭州市");
        assertEquals("", updated.getContent());
        assertEquals("杭州市", updated.getCity());
        verify(index, never()).reindexText(anyString(), anyString(), any());
        assertEquals("pending", updated.getVectorStatus());
        assertEquals("synced", service.getDocument("clear").getVectorStatus());
        assertEquals(0, service.getDocument("clear").getChunkCount());
        verify(index).deleteSource("clear");
    }
    @Test void scopeWriteFailureDoesNotPartiallyCommitContentUnderOldCity() {
        doc("atomic", "CITY", "北京市", "门票价格");
        var before = service.getDocument("atomic");
        jdbc.execute("CREATE TRIGGER reject_scope_change BEFORE UPDATE ON kb_document WHEN NEW.id='atomic' AND NEW.city='杭州市' BEGIN SELECT RAISE(ABORT,'isolated scope update failure'); END");
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> service.updateDocument("atomic", null, null, "杭州新正文", null, "CITY", "杭州市"));
        var after = service.getDocument("atomic");
        assertEquals(before.getContent(), after.getContent());
        assertEquals("北京市", after.getCity());
        assertEquals(0L, jdbc.queryForObject("SELECT index_revision FROM kb_document WHERE id='atomic'", Long.class));
    }
    @Test void queuedTaskCannotRecreateArchivedOrPreviousScope() throws Exception {
        var tasks = new ArrayList<Runnable>();
        var index = mock(com.ai.guide.domain.rag.gateway.KnowledgeIndexPort.class);
        var prepared = mock(com.ai.guide.domain.rag.gateway.KnowledgeIndexPort.PreparedIndex.class);
        when(index.prepareText(anyString(), anyString(), any())).thenReturn(prepared);
        when(prepared.publish(anyLong())).thenReturn(1);
        service = KnowledgeTestPorts.service(new JdbcKnowledgeDocumentStoreAdapter(jdbc), index, tasks::add, () -> {});
        var created = service.createDocument("queued", "guide", "门票价格", "[]", null, "tester", "CITY", "北京市");
        service.updateDocument(created.getId(), null, null, null, null, "CITY", "杭州市");
        assertEquals(2, tasks.size());
        tasks.get(0).run();
        verify(index, never()).prepareText(anyString(), anyString(), any());
        tasks.get(1).run();
        verify(index).prepareText(eq(created.getSourceName()), eq("门票价格"), eq(new KnowledgeScope("CITY", "杭州市")));
        verify(prepared).publish(1L);
        assertEquals("synced", service.getDocument(created.getId()).getVectorStatus());
        var archived = service.createDocument("archived-queued", "guide", "正文", "[]", null, "tester", "CITY", "北京市");
        service.deleteDocument(archived.getId());
        tasks.get(2).run();
        verify(index, never()).prepareText(eq(archived.getSourceName()), anyString(), any());
        assertEquals("archived", service.getDocument(archived.getId()).getStatus());
    }
    @Test void identicalTitleAndFileHashAreDeduplicatedWithinScopeNotAcrossCities() throws Exception {
        var store = new JdbcKnowledgeDocumentStoreAdapter(jdbc);
        var index = mock(com.ai.guide.domain.rag.gateway.KnowledgeIndexPort.class);
        var files = mock(com.ai.guide.domain.rag.gateway.KnowledgeFileStoragePort.class);
        when(files.save(anyString(), any())).thenReturn("first.txt", "second.txt");
        when(files.basename(anyString())).thenAnswer(call -> call.getArgument(0));
        var policy = mock(com.ai.guide.domain.rag.gateway.KnowledgeRuntimePolicyPort.class);
        when(policy.syncEnabled()).thenReturn(false);
        service = new KnowledgeDocumentService(store, index, files, Runnable::run, () -> {}, policy);
        var file = new com.ai.guide.domain.rag.model.KnowledgeDocumentUpload("same.txt", 3, () -> new byte[]{1,2,3});
        service.createDocument("同名指南", "guide", "", "[]", file, "tester", "CITY", "北京市");
        assertDoesNotThrow(() -> service.createDocument("同名指南", "guide", "", "[]", file, "tester", "CITY", "杭州市"));
        assertThrows(IllegalArgumentException.class, () -> service.createDocument("另一标题", "guide", "", "[]", file, "tester", "CITY", "杭州市"));
        assertThrows(IllegalArgumentException.class, () -> service.createDocument("同名指南", "guide", "正文", "[]", null, "tester", "CITY", "杭州市"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM kb_document WHERE title='同名指南'", Integer.class));
    }
    @Test void aliasesShareScopeWithoutTurningTaxonomyIntoCityAdmissionWhitelist() {
        var taxonomy = mock(com.ai.guide.domain.taxonomy.gateway.RagTaxonomyPort.class);
        when(taxonomy.getCityAliases()).thenReturn(Map.of("杭州", "杭州", "杭州市", "杭州"));
        assertEquals(KnowledgeScope.input("CITY", "杭州", taxonomy), KnowledgeScope.query("杭州市", taxonomy));
        assertEquals(new KnowledgeScope("CITY", "新测试城市"), KnowledgeScope.input("CITY", "新测试城市", taxonomy));
        assertEquals(new KnowledgeScope("CITY", "新测试城市"), KnowledgeScope.query(" 新测试城市 ", taxonomy));
        assertEquals(KnowledgeScope.global(), KnowledgeScope.query("", taxonomy));
        assertThrows(IllegalArgumentException.class, () -> KnowledgeScope.input("CITY", "", taxonomy));
        assertThrows(IllegalArgumentException.class, () -> KnowledgeScope.input("GLOBAL", "杭州", taxonomy));
        assertFalse(KnowledgeScope.query("杭州", taxonomy).permits(KnowledgeScope.unspecified()));
    }
    @Test void vectorStandardAndDeepFilterBeforeLimitAndRejectForeignOrLegacyPayload() {
        var client = mock(io.qdrant.client.QdrantClient.class);
        var embedding = mock(org.springframework.ai.embedding.EmbeddingModel.class);
        var rerank = mock(RerankService.class);
        var vector = new float[1536]; vector[0] = 1;
        when(embedding.embed(anyString())).thenReturn(vector);
        var points = new ArrayList<io.qdrant.client.grpc.Points.ScoredPoint>();
        for (String city : List.of("北京市", "杭州市", "")) {
            points.add(io.qdrant.client.grpc.Points.ScoredPoint.newBuilder()
                    .setId(io.qdrant.client.PointIdFactory.id(points.size()+1L))
                    .putPayload("scope", io.qdrant.client.ValueFactory.value(city.isEmpty() ? "GLOBAL" : "CITY"))
                    .putPayload("city", io.qdrant.client.ValueFactory.value(city))
                    .putPayload("content", io.qdrant.client.ValueFactory.value(city.isEmpty() ? "全国资料" : city+"资料")).build());
        }
        points.add(io.qdrant.client.grpc.Points.ScoredPoint.newBuilder()
                .setId(io.qdrant.client.PointIdFactory.id(4L))
                .putPayload("content", io.qdrant.client.ValueFactory.value("未标注旧资料")).build());
        when(client.searchAsync(any(io.qdrant.client.grpc.Points.SearchPoints.class)))
                .thenReturn(com.google.common.util.concurrent.Futures.immediateFuture(points));
        when(rerank.rerank(anyString(), anyList(), anyInt(), anyLong())).thenAnswer(call -> call.getArgument(1));
        when(rerank.rerankDeep(anyString(), anyList(), anyInt(), anyLong())).thenAnswer(call -> call.getArgument(1));
        var adapter = new com.ai.guide.infrastructure.rag.index.QdrantScenicKnowledgeAdapter(client, embedding, rerank);
        for (boolean deep : List.of(false, true)) {
            var result = adapter.searchScoped("相同问题", 2, 2, deep, new KnowledgeScope("CITY", "杭州市"));
            assertEquals(Set.of("杭州市资料", "全国资料"), new HashSet<>(result.stream().map(com.ai.guide.domain.rag.model.KnowledgeFragment::content).toList()));
        }
        var captor = org.mockito.ArgumentCaptor.forClass(io.qdrant.client.grpc.Points.SearchPoints.class);
        verify(client, times(2)).searchAsync(captor.capture());
        for (var request : captor.getAllValues()) {
            assertEquals(8, request.getLimit());
            var applicability = request.getFilter().getMustList().stream().filter(c -> c.hasFilter()).findFirst().orElseThrow().getFilter();
            assertEquals(2, applicability.getShouldCount());
            var global = applicability.getShouldList().stream().filter(c -> c.hasField()).findFirst().orElseThrow().getField();
            assertEquals("scope", global.getKey());
            assertEquals("GLOBAL", global.getMatch().getKeyword());
            var cityConditions = applicability.getShouldList().stream().filter(c -> c.hasFilter()).findFirst().orElseThrow().getFilter().getMustList();
            assertEquals(2, cityConditions.size());
            assertEquals("CITY", cityConditions.stream().filter(c -> c.getField().getKey().equals("scope")).findFirst().orElseThrow().getField().getMatch().getKeyword());
            assertEquals("杭州市", cityConditions.stream().filter(c -> c.getField().getKey().equals("city")).findFirst().orElseThrow().getField().getMatch().getKeyword());
        }
        verify(rerank).rerank(contains("CITY:杭州市"), eq(List.of("杭州市资料", "全国资料")), eq(2), anyLong());
        verify(rerank).rerankDeep(contains("CITY:杭州市"), eq(List.of("杭州市资料", "全国资料")), eq(2), anyLong());
    }
    @Test void packagedSeedInitializationPreservesExistingUnspecifiedDocument() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        List<String> lines;
        try (var stream = getClass().getClassLoader().getResourceAsStream("knowledge_documents.jsonl")) {
            assertNotNull(stream);
            lines = new java.io.BufferedReader(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8))
                    .lines().filter(line -> !line.isBlank()).toList();
        }
        var first = mapper.readTree(lines.getFirst());
        String retainedId = first.path("docId").asText();
        jdbc.update("INSERT INTO kb_document(id,title,content,scope,city) VALUES(?,?,?,'UNSPECIFIED','')", retainedId, "原编辑标题", "原编辑正文");
        var seed = new com.ai.guide.infrastructure.persistence.migration.KnowledgeSeedDataInitializer();
        seed.initialize(jdbc, true);
        seed.initialize(jdbc, true);
        assertEquals("UNSPECIFIED", service.getDocument(retainedId).getScope());
        assertEquals("原编辑正文", service.getDocument(retainedId).getContent());
        assertEquals(lines.size()-1-4, jdbc.queryForObject("SELECT COUNT(*) FROM kb_document WHERE scope='CITY' AND city='重庆市'", Integer.class));
        for (String city : List.of("北京市", "杭州市", "西安市", "武汉市"))
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM kb_document WHERE scope='CITY' AND city=?", Integer.class, city));
        assertEquals(lines.size()+1, jdbc.queryForObject("SELECT COUNT(*) FROM kb_document", Integer.class));
        var second = mapper.readTree(lines.get(1));
        assertTrue(ids(service.searchLocalKnowledge(second.path("title").asText(), 20, new KnowledgeScope("CITY", "重庆市")))
                .contains(second.path("docId").asText()));
        assertFalse(ids(service.searchLocalKnowledge(second.path("title").asText(), 20,
                new KnowledgeScope("CITY", "杭州市"))).contains(second.path("docId").asText()));
    }
}
