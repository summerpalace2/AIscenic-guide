package com.ai.guide.domain.rag.service;

import com.ai.guide.domain.rag.gateway.ScenicKnowledgePort;
import com.ai.guide.domain.rag.model.*;
import com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter;
import com.ai.guide.infrastructure.persistence.migration.*;
import com.ai.guide.infrastructure.rag.repository.JdbcKnowledgeDocumentStoreAdapter;
import com.ai.guide.testing.KnowledgeTestPorts;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KnowledgeCityScopeRegressionTest {
    @Test void vectorBackfillCopiesMetadataOnlyForNewDocumentsAndNeverReclassifiesExistingOnes() {
        var source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        try {
            var jdbc = new JdbcTemplate(source); new KnowledgeSchemaInitializer().initialize(jdbc, true);
            var index = mock(com.ai.guide.domain.rag.gateway.KnowledgeIndexPort.class);
            when(index.listSourceSummaries()).thenReturn(List.of(
                    Map.of("sourceName", "city", "content", "内容", "chunkCount", 1, "scope", "CITY", "city", "杭州市"),
                    Map.of("sourceName", "global", "content", "内容", "chunkCount", 1, "scope", "GLOBAL", "city", ""),
                    Map.of("sourceName", "mixed", "content", "内容", "chunkCount", 2, "scope", "UNSPECIFIED", "city", ""),
                    Map.of("sourceName", "existing", "content", "内容", "chunkCount", 1, "scope", "CITY", "city", "杭州市")));
            jdbc.update("INSERT INTO kb_document(id,title,source_name,status,scope,city) VALUES('admin','existing','existing','active','GLOBAL','')");
            var service = KnowledgeTestPorts.service(new JdbcKnowledgeDocumentStoreAdapter(jdbc), index);
            assertEquals(3, service.backfillDocumentsFromVectors().get("created"));
            assertEquals("杭州市", jdbc.queryForObject("SELECT city FROM kb_document WHERE source_name='city'", String.class));
            assertEquals("GLOBAL", jdbc.queryForObject("SELECT scope FROM kb_document WHERE source_name='global'", String.class));
            assertEquals("UNSPECIFIED", jdbc.queryForObject("SELECT scope FROM kb_document WHERE source_name='mixed'", String.class));
            assertEquals("GLOBAL", jdbc.queryForObject("SELECT scope FROM kb_document WHERE id='admin'", String.class));
            assertEquals(0, service.backfillDocumentsFromVectors().get("created"));
            assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM kb_document", Integer.class));
        } finally { source.destroy(); }
    }
    @Test void sameQueryRoutesScopeThroughSemanticAndLocalFallbackWithoutUnscopedCalls() {
        var vector = mock(ScenicKnowledgePort.class);
        var local = mock(KnowledgeDocumentService.class);
        // This verifies city propagation, not live model quality. Supply an explicit semantic fixture.
        var semantic = mock(com.ai.guide.domain.rag.gateway.SemanticEvidencePort.class);
        when(semantic.analyze(anyString(), anyString())).thenReturn(new SemanticEvidence.Analysis(List.of(
                new SemanticEvidence.Requirement("r1", "", "常规资料", "STATIC", "REGULAR"))));
        when(semantic.assess(anyString(), any(), anyList())).thenAnswer(call -> {
            List<SemanticEvidence.Evidence> evidence = call.getArgument(2);
            var first = evidence.get(0);
            return new SemanticEvidence.Verdict(true, List.of(new SemanticEvidence.Support(
                    "r1", "SUPPORTED", first.id(), first.text(), 0, first.text().length(),null,false,List.of(),"SUPPORTED_VALUE","UNKNOWN")));
        });
        var service = new RagRetrievalService(vector, local, new RagQueryExpander(new JsonTaxonomyAdapter()), semantic, null);
        when(vector.searchScoped(anyString(), eq(25), eq(12), anyBoolean(), any()))
                .thenAnswer(call -> List.of(new KnowledgeFragment("id", "source", "开放时间09:00-17:00",
                        call.getArgument(4))));
        for (String city : List.of("北京", "杭州")) {
            assertEquals("RELEVANT", service.retrieve("开放时间", city, Map.of(), true).get("retrievalStatus"));
            verify(vector).searchScoped(anyString(), eq(25), eq(12), eq(true), eq(new KnowledgeScope("CITY", city + "市")));
        }
        when(vector.searchScoped(anyString(), anyInt(), anyInt(), anyBoolean(), any())).thenThrow(new IllegalStateException("offline"));
        when(local.searchLocalKnowledge(anyString(), eq(5), eq(KnowledgeScope.global())))
                .thenReturn(List.of(Map.of("title", "全国须知", "content", "请留意现场公告", "scope", "GLOBAL", "city", "")));
        assertEquals("RELEVANT", service.retrieve("须知", "", Map.of(), false).get("retrievalStatus"));
        verify(local).searchLocalKnowledge(anyString(), eq(5), eq(KnowledgeScope.global()));
        verify(vector, never()).searchFragments(anyString(), anyInt(), anyInt());
        verify(local, never()).searchLocalKnowledge(anyString(), anyInt());
    }
    @Test void packagedSeedMetadataIsUsableAndRepeatedSeedPreservesAdminAndLegacyChanges() throws Exception {
        var source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        try {
            var jdbc = new JdbcTemplate(source);
            new KnowledgeSchemaInitializer().initialize(jdbc, true);
            var seeds = new KnowledgeSeedDataInitializer();
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(seeds, "initKnowledgeDocuments", jdbc);
            int count = jdbc.queryForObject("SELECT COUNT(*) FROM kb_document", Integer.class);
            try (var resource = getClass().getResourceAsStream("/knowledge_documents.jsonl")) {
                assertNotNull(resource);
                assertEquals(new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().filter(line -> !line.isBlank()).count(), count);
            }
            assertEquals(count - 4, jdbc.queryForObject("SELECT COUNT(*) FROM kb_document WHERE scope='CITY' AND city='重庆市'", Integer.class));
            for (String city : List.of("北京市", "杭州市", "西安市", "武汉市"))
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM kb_document WHERE scope='CITY' AND city=?", Integer.class, city));
            var local = KnowledgeTestPorts.service(new JdbcKnowledgeDocumentStoreAdapter(jdbc), KnowledgeTestPorts.noIndex());
            assertFalse(local.searchLocalKnowledge("洪崖洞 无障碍", 5, new KnowledgeScope("CITY", "重庆市")).isEmpty());
            assertTrue(local.searchLocalKnowledge("洪崖洞 无障碍", 5, new KnowledgeScope("CITY", "杭州市")).isEmpty());
            assertFalse(local.searchLocalKnowledge("西湖风景名胜区", 5, new KnowledgeScope("CITY", "杭州市")).isEmpty());
            assertTrue(local.searchLocalKnowledge("西湖风景名胜区", 5, new KnowledgeScope("CITY", "北京市")).isEmpty());
            Map<String, String> cityQueries = Map.of(
                    "北京市", "故宫博物院",
                    "杭州市", "西湖风景名胜区",
                    "西安市", "秦始皇帝陵博物院",
                    "武汉市", "湖北省博物馆");
            Map<String, String> cityDocumentIds = Map.of(
                    "北京市", "bj-palace-museum-official-001",
                    "杭州市", "hz-west-lake-official-001",
                    "西安市", "xa-qin-mausoleum-official-001",
                    "武汉市", "wh-hubei-museum-official-001");
            for (var entry : cityQueries.entrySet()) {
                assertTrue(local.searchLocalKnowledge(entry.getValue(), 5,
                        new KnowledgeScope("CITY", entry.getKey())).stream()
                        .anyMatch(row -> cityDocumentIds.get(entry.getKey()).equals(row.get("id"))), entry.getKey());
                for (String otherCity : cityQueries.keySet()) {
                    if (!otherCity.equals(entry.getKey()))
                        assertTrue(local.searchLocalKnowledge(entry.getValue(), 5,
                                new KnowledgeScope("CITY", otherCity)).stream()
                                .noneMatch(row -> cityDocumentIds.get(entry.getKey()).equals(row.get("id"))),
                                entry.getKey() + " must not leak to " + otherCity);
                }
            }
            assertTrue(jdbc.queryForObject("SELECT content FROM kb_document WHERE id='hz-west-lake-official-001'", String.class)
                    .contains("https://sjfw.mct.gov.cn/"));
            assertEquals("https://sjfw.mct.gov.cn/site/dataservice/culdetails?curId=10&id=34878",
                    jdbc.queryForObject("SELECT source_url FROM kb_document WHERE id='hz-west-lake-official-001'", String.class));
            jdbc.update("UPDATE kb_document SET source_url='', content='管理员编辑后的西湖简介' WHERE id='hz-west-lake-official-001'");
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(seeds, "initKnowledgeDocuments", jdbc);
            assertEquals("", jdbc.queryForObject("SELECT source_url FROM kb_document WHERE id='hz-west-lake-official-001'", String.class),
                    "edited text must not inherit the original official URL");
            String id = jdbc.queryForObject("SELECT id FROM kb_document ORDER BY id LIMIT 1", String.class);
            jdbc.update("UPDATE kb_document SET scope='UNSPECIFIED',city='',content='管理员自定义正文' WHERE id=?", id);
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(seeds, "initKnowledgeDocuments", jdbc);
            assertEquals(count, jdbc.queryForObject("SELECT COUNT(*) FROM kb_document", Integer.class));
            assertEquals("UNSPECIFIED", jdbc.queryForObject("SELECT scope FROM kb_document WHERE id=?", String.class, id));
            assertEquals("管理员自定义正文", jdbc.queryForObject("SELECT content FROM kb_document WHERE id=?", String.class, id));
        } finally { source.destroy(); }
    }
    @Test void scopedUploadAndUpdateExposeCanonicalMetadataAndRejectInvalidFields() throws Exception {
        var taxonomy = new JsonTaxonomyAdapter();
        assertEquals(new KnowledgeScope("CITY", "杭州市"), KnowledgeScope.input("CITY", "杭州", taxonomy));
        assertEquals(new KnowledgeScope("CITY", "新增目的地"), KnowledgeScope.input("CITY", " 新增目的地 ", taxonomy));
        assertThrows(IllegalArgumentException.class, () -> KnowledgeScope.input("GLOBAL", "杭州", taxonomy));
        assertThrows(IllegalArgumentException.class, () -> KnowledgeScope.input("CITY", "", taxonomy));
        assertThrows(IllegalArgumentException.class, () -> KnowledgeScope.input("CITY", "城\u0000市", taxonomy));
    }
}
