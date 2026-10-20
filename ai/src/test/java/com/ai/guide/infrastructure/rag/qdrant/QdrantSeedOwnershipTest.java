package com.ai.guide.infrastructure.rag.qdrant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.Futures;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Points;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QdrantSeedOwnershipTest {
    @Test void emptySnapshotWithoutCollectionIsSuccessfulWithoutCreatingOrMutatingAnything() throws Exception {
        var client = mock(QdrantClient.class);
        when(client.collectionExistsAsync(anyString())).thenReturn(Futures.immediateFuture(false));
        var importer = spy(new ProductionV2QdrantImporter(null, client, mock(EmbeddingModel.class), new ObjectMapper(), "cp6-fixture"));
        doReturn(List.of()).when(importer).buildPayloads(any(Path.class));
        var report = importer.importData(Path.of("unused"), false, null);
        assertEquals("PASS", report.status()); assertEquals(0, report.expectedPoints());
        assertFalse(report.collectionCreated());
        verify(client, never()).createCollectionAsync(anyString(), any(Collections.VectorParams.class));
        verify(client, never()).scrollAsync(any(Points.ScrollPoints.class));
        verify(client, never()).upsertAsync(anyString(), anyList());
    }

    @Test void emptySnapshotRevokesExistingSeedPointsAfterValidatingCollectionContract() throws Exception {
        var client = mock(QdrantClient.class);
        when(client.collectionExistsAsync(anyString())).thenReturn(Futures.immediateFuture(true));
        var info = mock(Collections.CollectionInfo.class, RETURNS_DEEP_STUBS);
        when(info.getConfig().getParams().getVectorsConfig().getConfigCase()).thenReturn(Collections.VectorsConfig.ConfigCase.PARAMS);
        when(info.getConfig().getParams().getVectorsConfig().getParams().getSize()).thenReturn(1536L);
        when(info.getConfig().getParams().getVectorsConfig().getParams().getDistance()).thenReturn(Collections.Distance.Cosine);
        when(client.getCollectionInfoAsync(anyString())).thenReturn(Futures.immediateFuture(info));
        var old = io.qdrant.client.PointIdFactory.id(7L);
        when(client.scrollAsync(any(Points.ScrollPoints.class))).thenReturn(Futures.immediateFuture(
                Points.ScrollResponse.newBuilder().addResult(Points.RetrievedPoint.newBuilder().setId(old)).build()));
        when(client.deleteAsync(anyString(), anyList(), any(java.time.Duration.class)))
                .thenReturn(Futures.immediateFuture(Points.UpdateResult.getDefaultInstance()));
        when(client.countAsync(anyString(), any(Points.Filter.class), eq(true))).thenReturn(Futures.immediateFuture(0L));
        var importer = spy(new ProductionV2QdrantImporter(null, client, mock(EmbeddingModel.class), new ObjectMapper(), "cp6-fixture"));
        doReturn(List.of()).when(importer).buildPayloads(any(Path.class));
        var report = importer.importData(Path.of("unused"), false, null);
        assertEquals("PASS", report.status()); assertEquals(0, report.expectedPoints());
        assertEquals(1, report.stalePointsReconciled());
        verify(client).getCollectionInfoAsync("cp6-fixture");
        verify(client).deleteAsync(eq("cp6-fixture"), eq(List.of(old)), any(java.time.Duration.class));
        verify(client, never()).upsertAsync(anyString(), anyList());
    }

    @Test void snapshotReconciliationAndVerificationAreScopedToSeedProducer() throws Exception {
        var client = mock(QdrantClient.class);
        var embedding = mock(EmbeddingModel.class);
        float[] vector = new float[1536]; vector[0] = 1;
        when(embedding.embed(anyString())).thenReturn(vector);
        when(client.collectionExistsAsync(anyString())).thenReturn(Futures.immediateFuture(true));
        var info = mock(Collections.CollectionInfo.class, RETURNS_DEEP_STUBS);
        when(info.getConfig().getParams().getVectorsConfig().getConfigCase()).thenReturn(Collections.VectorsConfig.ConfigCase.PARAMS);
        when(info.getConfig().getParams().getVectorsConfig().getParams().getSize()).thenReturn(1536L);
        when(info.getConfig().getParams().getVectorsConfig().getParams().getDistance()).thenReturn(Collections.Distance.Cosine);
        when(client.getCollectionInfoAsync(anyString())).thenReturn(Futures.immediateFuture(info));
        when(client.upsertAsync(anyString(), anyList())).thenReturn(Futures.immediateFuture(Points.UpdateResult.getDefaultInstance()));
        when(client.scrollAsync(any(Points.ScrollPoints.class)))
                .thenReturn(Futures.immediateFuture(Points.ScrollResponse.getDefaultInstance()));
        // A managed document shares this collection. It must not affect seed verification.
        when(client.countAsync(anyString())).thenReturn(Futures.immediateFuture(2L));
        when(client.countAsync(anyString(), any(Points.Filter.class), eq(true))).thenReturn(Futures.immediateFuture(1L));
        var importer = spy(new ProductionV2QdrantImporter(null, client, embedding, new ObjectMapper(), "cp6-fixture"));
        var payload = new ProductionV2QdrantImporter.ProvenancePayload(
                "chunk", "entity", "doc", "source-doc", "https://fixture.invalid/source", "fixture", "source", "title",
                "城市已明确标注的种子知识", "reviewed", "verified", true, "v1", "fixture", "2026-10-13", "2026-10-13", 0, 1, "CITY", "杭州市");
        doReturn(List.of(payload)).when(importer).buildPayloads(any(Path.class));

        assertEquals("PASS", importer.importData(Path.of("unused-fixture"), false, null).status());
        var scroll = org.mockito.ArgumentCaptor.forClass(Points.ScrollPoints.class);
        verify(client).scrollAsync(scroll.capture());
        Points.Filter filter = scroll.getValue().getFilter();
        assertEquals("activation_namespace", filter.getMust(0).getField().getKey());
        var owners = filter.getMust(1).getFilter().getShouldList();
        assertEquals("index_owner", owners.get(0).getField().getKey());
        assertEquals("seed-snapshot", owners.get(0).getField().getMatch().getKeyword());
        var legacy = owners.get(1).getFilter();
        assertEquals("index_owner", legacy.getMust(0).getIsEmpty().getKey());
        assertEquals("source", legacy.getMust(1).getIsEmpty().getKey());
        assertEquals("knowledge_document_id", legacy.getMustNot(0).getIsEmpty().getKey());
        verify(client).countAsync(eq("cp6-fixture"), eq(filter), eq(true));
        verify(client, never()).countAsync(anyString());
        @SuppressWarnings("unchecked") var points = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(client).upsertAsync(anyString(), points.capture());
        assertEquals("seed-snapshot", ((Points.PointStruct) points.getValue().getFirst()).getPayloadOrThrow("index_owner").getStringValue());
    }
}
