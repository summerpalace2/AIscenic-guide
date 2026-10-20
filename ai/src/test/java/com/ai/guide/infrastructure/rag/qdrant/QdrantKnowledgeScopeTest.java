package com.ai.guide.infrastructure.rag.qdrant;

import com.ai.guide.domain.rag.model.KnowledgeScope;
import com.ai.guide.domain.rag.service.RerankService;
import com.ai.guide.infrastructure.rag.index.QdrantScenicKnowledgeAdapter;
import io.qdrant.client.*;
import io.qdrant.client.grpc.Points;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class QdrantKnowledgeScopeTest {
    @Test void managedTextSourceUrlSurvivesIndexPayloadAndScopedRetrieval() throws Exception {
        var client = mock(QdrantClient.class); var embedding = mock(EmbeddingModel.class); var cache = mock(RerankService.class);
        float[] vector = new float[1536]; vector[0] = 1;
        when(embedding.embed(anyString())).thenReturn(vector);
        when(client.scrollAsync(any(Points.ScrollPoints.class)))
                .thenReturn(com.google.common.util.concurrent.Futures.immediateFuture(Points.ScrollResponse.getDefaultInstance()));
        when(client.deleteAsync(anyString(), any(Points.Filter.class)))
                .thenReturn(com.google.common.util.concurrent.Futures.immediateFuture(Points.UpdateResult.getDefaultInstance()));
        when(client.upsertAsync(anyString(), anyList()))
                .thenReturn(com.google.common.util.concurrent.Futures.immediateFuture(Points.UpdateResult.getDefaultInstance()));
        var adapter = new QdrantScenicKnowledgeAdapter(client, embedding, cache);
        adapter.prepareText("city-source", "城市博物馆的静态概述", new KnowledgeScope("CITY", "北京市"),
                "https://example.org/official").publish();
        @SuppressWarnings("unchecked") var points = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(client).upsertAsync(anyString(), points.capture());
        var indexed = ((Points.PointStruct) points.getValue().getFirst()).getPayloadMap();
        assertEquals("https://example.org/official", indexed.get("source_url").getStringValue());

        var hit = Points.ScoredPoint.newBuilder().setId(PointIdFactory.id(1L)).putAllPayload(indexed).build();
        when(client.searchAsync(any(Points.SearchPoints.class)))
                .thenReturn(com.google.common.util.concurrent.Futures.immediateFuture(List.of(hit)));
        when(cache.rerank(anyString(), anyList(), anyInt(), anyLong())).thenAnswer(call -> call.getArgument(1));
        var fragments = adapter.searchScoped("博物馆", 5, 3, false, new KnowledgeScope("CITY", "北京市"));
        assertEquals(1, fragments.size());
        assertEquals("https://example.org/official", fragments.getFirst().sourceUrl());
    }
    @Test void sourceSummaryRequiresUnanimousMetadataAndRemainsUnspecifiedAfterAnyMismatch() throws Exception {
        var client = mock(QdrantClient.class);
        var rows = new ArrayList<Points.RetrievedPoint>();
        String[][] data = {{"city","CITY","杭州市"},{"city","CITY","杭州市"},
                {"global","GLOBAL",""},{"global","GLOBAL",""},
                {"mixed","CITY","杭州市"},{"mixed","CITY","北京市"},{"mixed","CITY","杭州市"},
                {"legacy","",""},{"legacy","CITY","杭州市"},
                {"invalid","INVALID",""},{"invalid","GLOBAL",""}};
        for (var item : data) rows.add(Points.RetrievedPoint.newBuilder().setId(PointIdFactory.id(rows.size()+1L))
                .putPayload("source", ValueFactory.value(item[0])).putPayload("scope", ValueFactory.value(item[1]))
                .putPayload("city", ValueFactory.value(item[2])).putPayload("content", ValueFactory.value("正文不作为城市依据")).build());
        when(client.scrollAsync(any(Points.ScrollPoints.class))).thenReturn(com.google.common.util.concurrent.Futures.immediateFuture(
                Points.ScrollResponse.newBuilder().addAllResult(rows).build()));
        var adapter = new QdrantScenicKnowledgeAdapter(client, mock(EmbeddingModel.class), mock(RerankService.class));
        var summaries = adapter.listSourceSummaries().stream().collect(java.util.stream.Collectors.toMap(x -> x.get("sourceName"), x -> x));
        assertEquals("CITY", summaries.get("city").get("scope"));
        assertEquals("杭州市", summaries.get("city").get("city"));
        assertEquals("GLOBAL", summaries.get("global").get("scope"));
        for (String key : List.of("mixed", "legacy", "invalid")) {
            assertEquals("UNSPECIFIED", summaries.get(key).get("scope"));
            assertEquals("", summaries.get(key).get("city"));
        }
    }

    @Test void productionChunksUseOnlyExplicitDocumentOrEntityMetadataIncludingGlobalOverride(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        java.nio.file.Files.writeString(dir.resolve("source_documents.json"), mapper.writeValueAsString(List.of(Map.of(
                "source_id", "source", "source_url", "https://fixture.invalid/document", "publisher", "fixture", "source_title", "fixture"))));
        java.nio.file.Files.writeString(dir.resolve("entities.json"), mapper.writeValueAsString(List.of(Map.of("entity_id", "city-entity", "city", "杭州"))));
        var documents = new ArrayList<Map<String,Object>>();
        for (String kind : List.of("CITY", "GLOBAL", "UNSPECIFIED")) {
            var doc = new HashMap<String,Object>();
            doc.put("document_id", kind); doc.put("entity_id", "city-entity"); doc.put("source_id", "source");
            doc.put("source_url", "https://fixture.invalid/document"); doc.put("title", "同名文档");
            doc.put("content", "正文提及北京，不得据此推断城市"); doc.put("trust_level", "reviewed");
            doc.put("verification_result", "verified"); doc.put("source_updated_at", "2026-10-13"); doc.put("accessed_at", "2026-10-13");
            doc.put("rag_eligible", true); doc.put("scope", kind); documents.add(doc);
        }
        java.nio.file.Files.writeString(dir.resolve("knowledge_documents.json"), mapper.writeValueAsString(documents));
        var chunks = new com.ai.guide.infrastructure.rag.importer.ProductionV2ChunkService(mapper,
                new com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter());
        var importer = new ProductionV2QdrantImporter(chunks, null, null, mapper, ProductionV2QdrantImporter.DEFAULT_COLLECTION);
        for (var payload : importer.buildPayloads(dir)) {
            var point = (Points.PointStruct) org.springframework.test.util.ReflectionTestUtils.invokeMethod(importer, "toPoint", payload, new float[1536]);
            assertNotNull(point);
            var metadata = point.getPayloadMap();
            assertEquals(payload.scope(), metadata.get("scope").getStringValue());
            assertEquals("CITY".equals(payload.scope()) ? "杭州市" : "", metadata.get("city").getStringValue());
            assertEquals(!"UNSPECIFIED".equals(payload.scope()), metadata.get("rag_eligible").getBoolValue());
        }
    }

    @Test void indexCarriesExplicitScopeEligibilityAndInvalidatesExistingCache() throws Exception {
        var client = mock(QdrantClient.class);
        var embedding = mock(EmbeddingModel.class);
        var cache = mock(RerankService.class);
        float[] vector = new float[1536]; vector[0] = 1;
        when(embedding.embed(anyString())).thenReturn(vector);
        when(client.scrollAsync(any(Points.ScrollPoints.class)))
                .thenReturn(com.google.common.util.concurrent.Futures.immediateFuture(Points.ScrollResponse.getDefaultInstance()));
        when(client.deleteAsync(anyString(), any(Points.Filter.class)))
                .thenReturn(com.google.common.util.concurrent.Futures.immediateFuture(Points.UpdateResult.getDefaultInstance()));
        when(client.upsertAsync(anyString(), anyList()))
                .thenReturn(com.google.common.util.concurrent.Futures.immediateFuture(Points.UpdateResult.getDefaultInstance()));
        var adapter = new QdrantScenicKnowledgeAdapter(client, embedding, cache);
        assertEquals(1, adapter.reindexText("fixture", "正文含北京不据此猜城市", new KnowledgeScope("CITY", "杭州市")));
        @SuppressWarnings("unchecked") var points = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(client).upsertAsync(anyString(), points.capture());
        var payload = ((Points.PointStruct) points.getValue().getFirst()).getPayloadMap();
        assertEquals("CITY", payload.get("scope").getStringValue());
        assertEquals("杭州市", payload.get("city").getStringValue());
        assertEquals("production-v2", payload.get("activation_namespace").getStringValue());
        assertTrue(payload.get("rag_eligible").getBoolValue());
        verify(cache, atLeastOnce()).clearCache();
    }
    @Test void legacySemanticEntryPointsUseOnlyExplicitGlobalAndKeepCallerTimeout() throws Exception {
        var client = mock(QdrantClient.class); var embedding = mock(EmbeddingModel.class); var cache = mock(RerankService.class);
        float[] vector = new float[1536]; vector[0] = 1;
        when(embedding.embed(anyString())).thenReturn(vector);
        var point = Points.ScoredPoint.newBuilder().setId(PointIdFactory.id(1L))
                .putPayload("scope", ValueFactory.value("GLOBAL")).putPayload("city", ValueFactory.value(""))
                .putPayload("content", ValueFactory.value("全国通用须知")).build();
        when(client.searchAsync(any(Points.SearchPoints.class)))
                .thenReturn(com.google.common.util.concurrent.Futures.immediateFuture(List.of(point)));
        when(cache.rerank(anyString(), anyList(), anyInt(), anyLong())).thenAnswer(call -> call.getArgument(1));
        var adapter = new QdrantScenicKnowledgeAdapter(client, embedding, cache);
        assertEquals("全国通用须知", adapter.queryKnowledge("杭州景点", 800));
        verify(cache).rerank(contains("GLOBAL:"), eq(List.of("全国通用须知")), eq(10), eq(800L));
        var query = org.mockito.ArgumentCaptor.forClass(Points.SearchPoints.class);
        verify(client).searchAsync(query.capture());
        var applicability = query.getValue().getFilter().getMustList().stream().filter(Points.Condition::hasFilter).findFirst().orElseThrow().getFilter();
        assertEquals(1, applicability.getShouldCount());
        assertEquals("scope", applicability.getShould(0).getField().getKey());
        assertEquals("GLOBAL", applicability.getShould(0).getField().getMatch().getKeyword());
    }
}
